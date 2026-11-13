#!/usr/bin/env python3
"""Three-way apply a range of main onto a support-line worktree.

Run it inside the support line's worktree. For every file main changed between --from and --to
it merges main's change into the line's own version and reports what happened:

  identical_base   the line's file equalled main's old version: it now equals main's new version
  merged_clean     a three-way merge without conflicts (still read each one: a clean merge can be wrong)
  conflict         conflict markers were written; resolve by hand
  added            a new file, copied
  added_exists     main added a file the line already has: compare by hand
  target_missing   main changed a file this line does not have: decide whether it applies
  deleted          main deleted the file (removed here only if the line's copy was unchanged)

--from defaults to the line's recorded sync point (config/lines/sync.json). After a real run the
sync point is moved to --to, so `python3 tools/lines/lines.py status` shows what still differs.

Usage: port_apply.py --to <main commit> [--from <main commit>] [--dry]
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

CATEGORIES = ('identical_base', 'merged_clean', 'conflict', 'added', 'added_exists', 'target_missing', 'deleted')


def git(root, *args, check=True):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=check)


def show(root, commit, path):
    result = git(root, 'show', commit + ':' + path, check=False)
    return result.stdout if result.returncode == 0 else None


def apply_range(root, base, head, dry=False):
    """Returns {category: [paths]}. Writes into the worktree unless dry."""
    root = Path(root)
    out = {category: [] for category in CATEGORIES}
    rows = git(root, 'diff', '--name-status', '--no-renames', base, head).stdout.decode().splitlines()
    for row in rows:
        status, path = row.split('\t', 1)
        target = root / path
        old, new = show(root, base, path), show(root, head, path)
        if status == 'A':
            if target.exists():
                out['added_exists'].append(path)
            else:
                out['added'].append(path)
                if not dry:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(new)
            continue
        if status == 'D':
            out['deleted'].append(path)
            if not dry and target.exists() and target.read_bytes() == old:
                target.unlink()
            continue
        if not target.exists():
            out['target_missing'].append(path)
            continue
        current = target.read_bytes()
        if current == old:
            out['identical_base'].append(path)
            if not dry:
                target.write_bytes(new)
            continue
        with tempfile.TemporaryDirectory() as scratch:
            ours, theirs_old, theirs_new = (Path(scratch) / name for name in ('line', 'main-old', 'main-new'))
            ours.write_bytes(current); theirs_old.write_bytes(old); theirs_new.write_bytes(new)
            merged = subprocess.run(['git', 'merge-file', '-p', '-L', 'this line', '-L', 'main before',
                                     '-L', 'main after', str(ours), str(theirs_old), str(theirs_new)],
                                    capture_output=True)
        out['merged_clean' if merged.returncode == 0 else 'conflict'].append(path)
        if not dry:
            target.write_bytes(merged.stdout)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--to', required=True, help='the main commit to sync to')
    parser.add_argument('--from', dest='base', help='the main commit the line is synced to now')
    parser.add_argument('--dry', action='store_true', help='classify only; write nothing')
    parser.add_argument('--root', type=Path, default=Path.cwd())
    args = parser.parse_args()
    sync_path = args.root / 'config/lines/sync.json'
    sync = json.loads(sync_path.read_text()) if sync_path.exists() else None
    base = args.base or (sync or {}).get('reference_commit')
    if not base:
        raise SystemExit('no --from and no config/lines/sync.json on this line')
    head = git(args.root, 'rev-parse', '--verify', args.to + '^{commit}').stdout.decode().strip()
    result = apply_range(args.root, base, head, args.dry)
    for category in CATEGORIES:
        print('## ' + category + ': ' + str(len(result[category])))
        for path in result[category]:
            print('    ' + path)
    if sync is not None and not args.dry:
        sync['reference_commit'] = head
        sync_path.write_text(json.dumps(sync, indent=2) + '\n')
        print('sync point moved to ' + head)


if __name__ == '__main__':
    main()
