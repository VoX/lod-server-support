#!/usr/bin/env python3
"""Keep a support line in step with main.

Main is the reference. A support line (a "follower") records the main commit it is synced to in
config/lines/sync.json, and lists the paths that are meant to differ from main:

    {"schema_version": 2,
     "reference_commit": "<main commit>",
     "adapted": ["path", ...],   differ from main on purpose, or exist on one side only
     "absent":  ["glob", ...]}   main-only paths this line never carries

Every other path must be identical to main at that commit. Docs and line-local config are exempt.

Run these in the line's worktree:

    lines.py status                 what differs from the reference right now (working tree)
    lines.py port --to <commit>     three-way apply main's changes since the sync point
    lines.py adapt <path>...        list paths as adapted
    lines.py tidy                   drop adapted entries that are identical to main again
    lines.py check [--base <ref>]   the CI check on HEAD; --base also checks the port itself
    lines.py init --reference <commit>   write the first sync.json from a real comparison

Rules a follower lives by:
  - A fix that a follower needs in a shared file goes to main first, then the line syncs.
    `adapt` is for behaviour that must differ, never for a fix main lacks.
  - Main merges before a port: the sync point is always a commit on main.
  - Three things need a written reason, as a commit trailer on the line's branch:
        Adapted: <path>: <why>             a shared file becomes line-specific
        Not-ported: <path>: <why>          main changed an adapted file and the line left it alone
        Corpus-regenerated: <path>: <why>  a captured wire corpus changed and main's did not
"""
from __future__ import annotations

import argparse
import difflib
import fnmatch
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SYNC = 'config/lines/sync.json'
LINES = 'config/lines/lines.json'
LINE = 'config/compatibility/line.json'
# Line-local everywhere: never compared with main.
EXEMPT = ('*.md', 'LICENSE', 'docs/*', 'config/compatibility/*', 'config/lines/*')
# Never copied by a port, whatever main did to them.
NEVER_PORTED = ('config/compatibility/*', 'config/lines/*')
CORPUS = ('*-corpus/*',)
TRAILER = re.compile(r'^(Adapted|Not-ported|Corpus-regenerated): (\S+): (\S.*)$', re.M)
ROLES = ('reference', 'follower', 'frozen')
PORT_CATEGORIES = ('identical_base', 'merged_clean', 'conflict', 'added', 'added_exists',
                   'target_missing', 'deleted', 'never_ported')


class Invalid(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def run(root, *args, env=None, check=True, text=True):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=text, env=env)
    if check and result.returncode:
        stderr = result.stderr if text else result.stderr.decode(errors='replace')
        raise Invalid('git ' + ' '.join(args[:3]) + ': ' + stderr.strip()[:300])
    return result


def matches(path, patterns):
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def exempt(path):
    return matches(path, EXEMPT)


def commit_exists(root, commit):
    return run(root, 'cat-file', '-e', commit + '^{commit}', check=False).returncode == 0


def is_ancestor(root, older, newer):
    """None when either object is missing (git answers 128), else True or False."""
    code = run(root, 'merge-base', '--is-ancestor', older, newer, check=False).returncode
    return None if code not in (0, 1) else code == 0


def entries(root, tree_ish):
    """{path: (mode, blob)} for every file in a commit or tree."""
    out = {}
    for row in run(root, 'ls-tree', '-r', '-z', tree_ish).stdout.split('\0'):
        if row:
            meta, path = row.split('\t', 1)
            mode, _, blob = meta.split()
            out[path] = (mode, blob)
    return out


def worktree_tree(root):
    """A tree object for the working tree as it is now, without touching the real index."""
    with tempfile.TemporaryDirectory() as scratch:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(scratch) / 'index'))
        run(root, 'read-tree', 'HEAD', env=env)
        run(root, 'add', '-A', env=env)
        return run(root, 'write-tree', env=env).stdout.strip()


def content(root, blob):
    return run(root, 'cat-file', 'blob', blob, text=False).stdout


def show(root, commit, path):
    result = run(root, 'show', commit + ':' + path, check=False)
    return result.stdout if result.returncode == 0 else None


def role(root):
    value = json.loads((Path(root) / LINE).read_text()).get('role')
    require(value in ROLES, LINE + ' needs "role": one of ' + ', '.join(ROLES))
    return value


def validate_sync(sync):
    require(isinstance(sync, dict) and sync.get('schema_version') == 2, 'unsupported sync.json schema')
    require(re.fullmatch('[0-9a-f]{40}', str(sync.get('reference_commit', ''))), 'reference_commit must be a full commit id')
    for key in ('adapted', 'absent'):
        value = sync.get(key, [])
        require(isinstance(value, list) and all(isinstance(item, str) and item for item in value),
                key + ' must be a list of paths')
        require(value == sorted(set(value)), key + ' must be sorted and free of duplicates')
    require(set(sync) <= {'schema_version', 'reference_commit', 'adapted', 'absent'}, 'unknown key in sync.json')
    return sync


def load_sync(root):
    path = Path(root) / SYNC
    require(path.exists(), 'this line has no ' + SYNC)
    return validate_sync(json.loads(path.read_text()))


def save_sync(root, sync):
    sync['adapted'] = sorted(set(sync.get('adapted', [])))
    sync['absent'] = sorted(set(sync.get('absent', [])))
    (Path(root) / SYNC).write_text(json.dumps(validate_sync(sync), indent=2) + '\n')


def must_match(root, reference):
    """Prefixes main forbids a line to adapt, read from main so a line cannot edit them away."""
    text = show(root, reference, LINES)
    return tuple(json.loads(text).get('must_match', [])) if text else ()


def describe(on_main, here):
    if here is None:
        return 'only on main'
    if on_main is None:
        return 'only on this line'
    return 'file mode differs' if on_main[1] == here[1] else 'content differs'


def compare(root, sync, tree_ish):
    """The always-on rules: every path equals main unless it is listed, and every listed path
    really differs."""
    reference, line = entries(root, sync['reference_commit']), entries(root, tree_ish)
    adapted, absent = set(sync.get('adapted', [])), sync.get('absent', [])
    locked = must_match(root, sync['reference_commit'])
    issues = []
    for path in sorted(set(reference) | set(line)):
        if exempt(path):
            continue
        on_main, here = reference.get(path), line.get(path)
        if matches(path, absent):
            if here is not None:
                issues.append(dict(path=path, result='absent-but-present',
                                   detail='matches an `absent` pattern but exists on this line'))
            continue
        if path in adapted:
            if on_main == here:
                issues.append(dict(path=path, result='stale-adapted',
                                   detail='listed as adapted but identical to main; run `lines.py tidy`'))
            elif any(path.startswith(prefix) for prefix in locked):
                issues.append(dict(path=path, result='must-match',
                                   detail='main forbids adapting this path (must_match in ' + LINES + ')'))
        elif on_main != here:
            issues.append(dict(path=path, result='differs',
                               detail=describe(on_main, here) + '; differs from main and is not listed as adapted'))
    for path in sorted(adapted):
        if exempt(path):
            issues.append(dict(path=path, result='exempt-listed', detail='exempt paths are never listed'))
        elif path not in reference and path not in line:
            issues.append(dict(path=path, result='stale-adapted', detail='exists on neither side; run `lines.py tidy`'))
    return issues


def trailers(root, base, head):
    """{kind: {path: why}} from the commit messages of base..head."""
    out = {'Adapted': {}, 'Not-ported': {}, 'Corpus-regenerated': {}}
    for kind, path, why in TRAILER.findall(run(root, 'log', '--format=%B', base + '..' + head).stdout):
        out[kind][path] = why.strip()
    return out


def replay(root, line_blob, main_old, main_new):
    """(clean, bytes) of main's change applied to the line's version."""
    with tempfile.TemporaryDirectory() as scratch:
        ours, old, new = (Path(scratch) / name for name in ('line', 'main-old', 'main-new'))
        ours.write_bytes(content(root, line_blob))
        old.write_bytes(content(root, main_old))
        new.write_bytes(content(root, main_new))
        merged = subprocess.run(['git', 'merge-file', '-p', '-L', 'this line', '-L', 'main before',
                                 '-L', 'main after', str(ours), str(old), str(new)], capture_output=True)
    return merged.returncode == 0, merged.stdout


def residue_diff(replayed, actual, limit=120):
    rows = list(difflib.unified_diff(replayed.decode(errors='replace').splitlines(),
                                     actual.decode(errors='replace').splitlines(),
                                     'mechanical port', 'this line', lineterm='', n=2))
    return rows[:limit] + (['... (' + str(len(rows) - limit) + ' more lines)'] if len(rows) > limit else [])


def check_port(root, sync, base, head='HEAD'):
    """The rules that need the change itself: what the branch did between base and head.
    Returns (issues, residue, mechanical). `residue` is each adapted file's hand-made part,
    shown as a diff against a mechanical replay of main's change, for the reviewer."""
    issues, residue, mechanical = [], [], 0
    notes = trailers(root, base, head)
    line_base, line_head = entries(root, base), entries(root, head)
    adapted = set(sync.get('adapted', []))
    base_text = show(root, base, SYNC)
    base_sync = json.loads(base_text) if base_text else None
    old, new = (base_sync or {}).get('reference_commit'), sync['reference_commit']

    if base_sync is not None:
        for path in sorted(adapted - set(base_sync.get('adapted', []))):
            if path in line_base and path not in notes['Adapted']:
                issues.append(dict(path=path, result='adapted-without-reason',
                                   detail='newly adapted; add a commit trailer "Adapted: ' + path + ': <why>"'))

    main_changed = set()
    if old and old != new:
        forward = is_ancestor(root, old, new)
        require(forward is not None, 'the previous sync point ' + old[:9] + ' is not available; fetch it')
        if not forward:
            issues.append(dict(path=SYNC, result='sync-moved-backwards',
                               detail=old[:9] + ' is not an ancestor of ' + new[:9]))
        else:
            main_old, main_new = entries(root, old), entries(root, new)
            main_changed = {path for path in set(main_old) | set(main_new) if main_old.get(path) != main_new.get(path)}
            for path in sorted(main_changed):
                if exempt(path) or path not in adapted or path not in line_base:
                    continue
                before, after = line_base[path], line_head.get(path)
                was, now = main_old.get(path), main_new.get(path)
                mechanical_result = None
                if was and now:
                    clean, merged = replay(root, before[1], was[1], now[1])
                    mechanical_result = merged if clean else None
                if after == before:
                    if mechanical_result is not None and mechanical_result == content(root, before[1]):
                        continue  # main's change is already in the line's version
                    if path not in notes['Not-ported']:
                        issues.append(dict(path=path, result='not-ported',
                                           detail='main changed this adapted file and the line left it alone; port it or '
                                                  'add a commit trailer "Not-ported: ' + path + ': <why>"'))
                elif after is None:
                    residue.append(dict(path=path, kind='removed on this line', diff=[]))
                elif mechanical_result is not None and content(root, after[1]) == mechanical_result:
                    mechanical += 1
                elif mechanical_result is not None:
                    residue.append(dict(path=path, kind='hand-edited',
                                        diff=residue_diff(mechanical_result, content(root, after[1]))))
                else:
                    residue.append(dict(path=path, kind='no mechanical port exists (conflict, or added or deleted on main)',
                                        diff=[]))

    for path in sorted(set(line_base) & set(line_head) | set(line_base) - set(line_head)):
        if matches(path, CORPUS) and line_base.get(path) != line_head.get(path) and path not in main_changed \
                and path not in notes['Corpus-regenerated']:
            issues.append(dict(path=path, result='corpus-changed',
                               detail='a captured corpus changed and main\'s did not; add a commit trailer '
                                      '"Corpus-regenerated: ' + path + ': <why>"'))
    return issues, residue, mechanical


def check(root, base=None, fetch=False, head='HEAD'):
    """The follower check. Returns the report dict; report['passed'] decides."""
    sync = load_sync(root)
    reference = sync['reference_commit']
    if not commit_exists(root, reference) or fetch:
        require(fetch or commit_exists(root, reference), 'the sync point ' + reference[:9] + ' is not in this clone; fetch main')
        run(root, 'fetch', '--no-tags', 'origin', 'main', check=False)
        if not commit_exists(root, reference):
            run(root, 'fetch', '--no-tags', 'origin', reference)
    issues = []
    has_main = run(root, 'rev-parse', '-q', '--verify', 'origin/main', check=False).returncode == 0
    if has_main or fetch:
        require(has_main, 'origin/main is not available after a fetch')
        if not is_ancestor(root, reference, 'origin/main'):
            issues.append(dict(path=SYNC, result='not-on-main',
                               detail='the sync point ' + reference[:9] + ' is not on origin/main; main merges before a port'))
    issues += compare(root, sync, head)
    residue, mechanical = [], 0
    if base:
        port_issues, residue, mechanical = check_port(root, sync, base, head)
        issues += port_issues
    return dict(reference_commit=reference, base=base, issues=issues, residue=residue,
                mechanical_ports=mechanical, passed=not issues)


def port(root, old, new, dry=False):
    """Three-way apply main's old..new onto the working tree. Returns {category: [paths]}."""
    root = Path(root)
    out = {category: [] for category in PORT_CATEGORIES}
    main_old, main_new = entries(root, old), entries(root, new)
    for path in sorted(set(main_old) | set(main_new)):
        was, now = main_old.get(path), main_new.get(path)
        if was == now:
            continue
        if matches(path, NEVER_PORTED):
            out['never_ported'].append(path)
            continue
        target = root / path
        if was is None:
            if target.exists():
                out['added_exists'].append(path)
            else:
                out['added'].append(path)
                if not dry:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(content(root, now[1]))
                    target.chmod(0o755 if now[0] == '100755' else 0o644)
            continue
        if now is None:
            out['deleted'].append(path)
            if not dry and target.exists() and target.read_bytes() == content(root, was[1]):
                target.unlink()
            continue
        if not target.exists():
            out['target_missing'].append(path)
            continue
        current = target.read_bytes()
        if current == content(root, was[1]):
            out['identical_base'].append(path)
            if not dry:
                target.write_bytes(content(root, now[1]))
                target.chmod(0o755 if now[0] == '100755' else 0o644)
            continue
        if was[1] == now[1]:
            continue  # a mode-only change on main to a file the line adapted
        with tempfile.TemporaryDirectory() as scratch:
            ours, older, newer = (Path(scratch) / name for name in ('line', 'main-old', 'main-new'))
            ours.write_bytes(current)
            older.write_bytes(content(root, was[1]))
            newer.write_bytes(content(root, now[1]))
            merged = subprocess.run(['git', 'merge-file', '-p', '-L', 'this line', '-L', 'main before',
                                     '-L', 'main after', str(ours), str(older), str(newer)], capture_output=True)
        out['merged_clean' if merged.returncode == 0 else 'conflict'].append(path)
        if not dry:
            target.write_bytes(merged.stdout)
    return out


def initial_sync(root, reference, tree_ish='HEAD'):
    """The first sync.json: every non-exempt path that differs from main today is adapted."""
    main, line = entries(root, reference), entries(root, tree_ish)
    adapted = sorted(path for path in set(main) | set(line) if not exempt(path) and main.get(path) != line.get(path))
    return dict(schema_version=2, reference_commit=reference, adapted=adapted, absent=[])


def print_issues(issues):
    for issue in issues:
        print('  ' + issue['result'] + ': ' + issue['path'] + ' (' + issue['detail'] + ')')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('status')
    sub.add_parser('tidy')
    checker = sub.add_parser('check')
    checker.add_argument('--base')
    checker.add_argument('--fetch', action='store_true')
    checker.add_argument('--report', type=Path)
    porter = sub.add_parser('port')
    porter.add_argument('--to', required=True)
    porter.add_argument('--from', dest='old')
    porter.add_argument('--dry', action='store_true')
    adapter = sub.add_parser('adapt')
    adapter.add_argument('paths', nargs='+')
    starter = sub.add_parser('init')
    starter.add_argument('--reference', required=True)
    args = parser.parse_args(argv)
    root = args.root
    try:
        if args.command == 'init':
            require(not (root / SYNC).exists(), SYNC + ' already exists')
            reference = run(root, 'rev-parse', '--verify', args.reference + '^{commit}').stdout.strip()
            (root / SYNC).parent.mkdir(parents=True, exist_ok=True)
            sync = initial_sync(root, reference, worktree_tree(root))
            save_sync(root, sync)
            print('wrote ' + SYNC + ': ' + str(len(sync['adapted'])) + ' adapted paths against ' + reference[:9])
            return 0
        sync = load_sync(root)
        if args.command == 'status':
            issues = compare(root, sync, worktree_tree(root))
            print('synced to main ' + sync['reference_commit'][:9] + ', ' + str(len(sync['adapted'])) + ' adapted paths')
            print_issues(issues)
            print('clean' if not issues else str(len(issues)) + ' to resolve')
            return 0 if not issues else 1
        if args.command == 'tidy':
            reference, line = entries(root, sync['reference_commit']), entries(root, worktree_tree(root))
            keep = [path for path in sync['adapted'] if reference.get(path) != line.get(path)]
            print('dropped ' + str(len(sync['adapted']) - len(keep)) + ' entries')
            sync['adapted'] = keep
            save_sync(root, sync)
            return 0
        if args.command == 'adapt':
            for path in args.paths:
                require(not exempt(path), path + ' is exempt and is never listed')
            sync['adapted'] = sorted(set(sync['adapted']) | set(args.paths))
            save_sync(root, sync)
            print('remember the commit trailer: Adapted: <path>: <why>')
            return 0
        if args.command == 'port':
            old = args.old or sync['reference_commit']
            new = run(root, 'rev-parse', '--verify', args.to + '^{commit}').stdout.strip()
            result = port(root, old, new, args.dry)
            for category in PORT_CATEGORIES:
                print('## ' + category + ': ' + str(len(result[category])))
                for path in result[category]:
                    print('    ' + path)
            if not args.dry:
                sync['reference_commit'] = new
                save_sync(root, sync)
                print('sync point moved to ' + new[:9] + '; now run `lines.py status`')
            return 0
        report = check(root, args.base, args.fetch)
        if args.report:
            args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print('synced to main ' + report['reference_commit'][:9] + '; mechanical ports: ' + str(report['mechanical_ports'])
              + '; hand-edited: ' + str(len(report['residue'])))
        for item in report['residue']:
            print('  residue: ' + item['path'] + ' (' + item['kind'] + ')')
        print_issues(report['issues'])
        return 0 if report['passed'] else 1
    except (Invalid, OSError, ValueError, KeyError) as error:
        print('lines: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
