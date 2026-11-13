#!/usr/bin/env python3
"""Publish the exact gated Voxy Server Side jars of a release to the VSS Modrinth project.

VSS conventions: empty changelog, bare version number, unversioned file names, no dependencies,
not featured. Each (line, loader) copies its loaders and game versions from the previous VSS
release's receipt when --previous-state has one, and is seeded from the tagged line facts
otherwise. Sponge rows exist only where the line ships Sponge. The row count is computed.

Publishes only after every line of the release passed verify_lss.py. Stable releases only.

Usage: publish_vss.py --version X.Y.Z --previous-state <dir>            (prints the plan)
       publish_vss.py --version X.Y.Z --previous-state <dir> --publish
"""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path

import release_common as rc

LOADERS = ('paper', 'neoforge', 'sponge', 'fabric')  # fabric last: it is the one players see first


def save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def get(path):
    return json.loads(rc.fetch(rc.MODRINTH_API + path, timeout=60))


def target(args, line, loader, env):
    for directory in args.previous_state or ():
        receipt = directory / ('vss-' + line + '-' + loader + '.json')
        if receipt.exists():
            old = json.loads(receipt.read_text())
            assert old['project_id'] == rc.VSS_PROJECT, receipt
            return old['game_versions'], old['loaders']
    if loader == 'paper':
        return env['LINE_GAME_VERSIONS_PAPER'].split(), env['LINE_PAPER_LOADERS'].split()
    return env['LINE_GAME_VERSIONS_' + loader.upper()].split(), [loader]


def plan(args):
    manifest = json.loads((args.gates / 'manifest.json').read_text())
    rows = []
    for line, tag in rc.release_lines(args.version, args.repo, args.only):
        env = rc.line_env(tag, args.repo)
        artifacts = manifest['lines'][line]['jars_sha256']
        for loader in LOADERS:
            if loader == 'sponge' and env.get('LINE_SHIP_SPONGE') != 'true':
                continue
            matches = [name for name in artifacts if name.startswith('voxy-server-side-' + loader + '-')]
            assert len(matches) == 1, (line, loader, matches)
            source = args.gates / line / 'jars' / matches[0]
            data = source.read_bytes()
            assert hashlib.sha256(data).hexdigest() == artifacts[matches[0]], source
            game_versions, loaders = target(args, line, loader, env)
            payload = dict(project_id=rc.VSS_PROJECT, name='Voxy Server Side ' + args.version,
                           version_number=args.version, changelog='', featured=False, version_type='release',
                           dependencies=[], game_versions=game_versions, loaders=loaders,
                           file_parts=['file'], primary_file='file', status='listed')
            rows.append(dict(line=line, loader=loader, source=str(source),
                             filename='voxy-server-side-' + loader + '.jar', sha256=artifacts[matches[0]],
                             sha512=hashlib.sha512(data).hexdigest(), payload=payload))
    return rows


def verify(version, row):
    payload = row['payload']
    for key in ('project_id', 'name', 'version_number', 'changelog', 'featured', 'version_type', 'dependencies'):
        assert version[key] == payload[key], (row['line'], row['loader'], key)
    for key in ('game_versions', 'loaders'):
        assert sorted(version[key]) == sorted(payload[key]), key
    assert version['status'] == 'listed' and len(version['files']) == 1
    published = version['files'][0]
    assert published['filename'] == row['filename'] and published['primary']
    assert published['hashes']['sha512'] == row['sha512']
    assert hashlib.sha256(rc.fetch(published['url'], timeout=180)).hexdigest() == row['sha256'], 'download hash'


def publish(args, rows):
    for line in sorted({row['line'] for row in rows}):
        gate = args.state / ('lss-verified-' + line + '.json')
        assert gate.exists() and json.loads(gate.read_text())['problems'] == [], \
            'LSS ' + line + ' is not verified yet (run verify_lss.py)'
    token = rc.secret('MODRINTH_PAT')
    for row in rows:
        # The project list is cached for minutes: a receipt left by an interrupted run is authoritative.
        receipt = args.state / ('vss-' + row['line'] + '-' + row['loader'] + '.json')
        if receipt.exists() and json.loads(receipt.read_text()).get('version_number') == args.version:
            version = get('version/' + json.loads(receipt.read_text())['id'])
            verify(version, row)
            print(row['line'], row['loader'], version['id'], 'already published (receipt) and verified', flush=True)
            continue
        existing = [v for v in get('project/' + rc.VSS_PROJECT + '/version')
                    if v['version_number'] == args.version
                    and sorted(v['loaders']) == sorted(row['payload']['loaders'])
                    and sorted(v['game_versions']) == sorted(row['payload']['game_versions'])]
        assert len(existing) <= 1, 'duplicate entries need investigation'
        if existing:
            version = existing[0]
        else:
            body, content_type = rc.multipart('data', row['payload'], row['filename'], Path(row['source']).read_bytes())
            request = urllib.request.Request(rc.MODRINTH_API + 'version', data=body, method='POST', headers={
                'Authorization': token, 'Content-Type': content_type, **rc.USER_AGENT})
            with urllib.request.urlopen(request, timeout=300) as response:
                version = json.load(response)
        save(receipt, version)
        verify(version, row)
        print(row['line'], row['loader'], version['id'], 'published and download hash verified', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    rc.add_common_arguments(parser)
    parser.add_argument('--previous-state', type=Path, action='append',
                        help="the previous release's publish directory (repeatable)")
    parser.add_argument('--publish', action='store_true')
    arguments = rc.resolve(parser.parse_args())
    if rc.is_prerelease(arguments.version):
        raise SystemExit('VSS takes stable releases only')
    planned = plan(arguments)
    save(arguments.state / 'vss-publication-plan.json', planned)
    if arguments.publish:
        publish(arguments, planned)
    else:
        for planned_row in planned:
            print(planned_row['line'], planned_row['loader'], planned_row['payload']['game_versions'],
                  planned_row['payload']['loaders'], planned_row['filename'], planned_row['sha256'][:12])
