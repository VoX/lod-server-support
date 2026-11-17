#!/usr/bin/env python3
"""Recover the LSS Modrinth uploads of a release run that went red AFTER its GitHub release step.

Never re-run such a run. This uploads the exact GitHub-attached jars with the metadata
release.yml would have sent: name, version number, loaders and game versions from the tagged
line.env, the changelog from the GitHub release body, featured for every platform but Sponge,
Fabric dependencies mapped from fabric.mod.json, then the same "unfeature older covered
versions" step. An existing version number is never posted twice.

Usage: recover_modrinth.py --version X.Y.Z <line>            (prints the plan)
       recover_modrinth.py --version X.Y.Z <line> --publish
"""
import argparse
import hashlib
import json
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

import release_common as rc

# Modrinth project ids for the fabric.mod.json ids release.yml resolves; unknown ids are dropped.
DEPENDENCY_PROJECTS = {'fabric-api': 'P7dR8mSH', 'sodium': 'AANobbMI', 'modmenu': 'mOgUt4GM',
                       'voxy': 'fxxUqruK', 'journeymap': 'lfHFW1mp'}


def call(path, data=None, method='GET', headers=None):
    request = urllib.request.Request(rc.MODRINTH_API + path, data=data, method=method,
                                     headers={**rc.USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(request, timeout=300) as response:
        body = response.read()
        return json.loads(body) if body else None


def fabric_dependencies(jar):
    with zipfile.ZipFile(jar) as archive:
        meta = json.loads(archive.read('fabric.mod.json'))
    out = [dict(project_id=DEPENDENCY_PROJECTS[m], dependency_type='required')
           for m in meta.get('depends', {}) if m in DEPENDENCY_PROJECTS]
    out += [dict(project_id=DEPENDENCY_PROJECTS[m], dependency_type='optional')
            for m in meta.get('suggests', {}) if m in DEPENDENCY_PROJECTS]
    return out


def plan(args, line, workdir):
    _, reference = rc.known_lines(args.repo)
    tag = rc.tag_for(args.version, line, reference)
    env = rc.line_env(tag, args.repo)
    release = json.loads(subprocess.run(['gh', 'release', 'view', tag, '-R', rc.REPO_SLUG, '--json', 'body,assets'],
                                        capture_output=True, text=True, check=True).stdout)
    subprocess.run(['gh', 'release', 'download', tag, '-R', rc.REPO_SLUG, '-D', str(workdir), '--clobber'],
                   capture_output=True, check=True)
    names = {'fabric': 'Fabric (MC ' + env['LINE_MC_FABRIC'] + ')',
             'paper': rc.paper_display(env) + ' (MC ' + env['LINE_MC_PAPER'] + ')',
             'neoforge': env.get('LINE_NEOFORGE_NAME', ''), 'sponge': env.get('LINE_SPONGE_NAME', '')}
    rows = []
    for platform in rc.platforms(env):
        jars = [a['name'] for a in release['assets']
                if a['name'].startswith('lod-server-support-' + platform + '-' + args.version)]
        assert len(jars) == 1, (platform, jars)
        path = workdir / jars[0]
        payload = dict(project_id=rc.LSS_PROJECT, name='v' + args.version + ' - ' + names[platform],
                       version_number=rc.modrinth_number(args.version, platform, env),
                       changelog=release['body'],
                       dependencies=fabric_dependencies(path) if platform == 'fabric' else [],
                       game_versions=env['LINE_GAME_VERSIONS_' + platform.upper()].split(),
                       version_type=rc.modrinth_version_type(args.version),
                       loaders=env['LINE_PAPER_LOADERS'].split() if platform == 'paper' else [platform],
                       featured=platform != 'sponge' and not rc.is_prerelease(args.version),
                       status='listed', file_parts=['file'], primary_file='file')
        rows.append(dict(platform=platform, jar=str(path),
                         sha512=hashlib.sha512(path.read_bytes()).hexdigest(), payload=payload))
    return rows


def publish(args, rows):
    auth = {'Authorization': rc.secret('MODRINTH_PAT')}
    for row in rows:
        payload = row['payload']
        try:  # a lookup by number is fresh, unlike the cached project list
            existing = call('project/' + rc.LSS_PROJECT + '/version/'
                            + urllib.parse.quote(payload['version_number'], safe=''))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            existing = None
        if existing:
            version = existing
            print(payload['version_number'], 'already exists', version['id'])
        else:
            body, content_type = rc.multipart('data', payload, Path(row['jar']).name, Path(row['jar']).read_bytes())
            version = call('version', body, 'POST', {**auth, 'Content-Type': content_type})
            print(payload['version_number'], 'published', version['id'])
        assert version['files'][0]['hashes']['sha512'] == row['sha512'], 'uploaded file differs from the GitHub asset'
        if payload['featured']:
            for old in call('project/' + rc.LSS_PROJECT + '/version'):
                if (old['id'] != version['id'] and old['featured']
                        and not old['version_number'].startswith('v' + args.version + '+')
                        and set(old['loaders']) <= set(payload['loaders'])
                        and set(old['game_versions']) <= set(payload['game_versions'])):
                    call('version/' + old['id'], json.dumps({'featured': False}).encode(), 'PATCH',
                         {**auth, 'Content-Type': 'application/json'})
                    print('  unfeatured', old['version_number'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    rc.add_common_arguments(parser)
    parser.add_argument('line')
    parser.add_argument('--publish', action='store_true')
    arguments = rc.resolve(parser.parse_args())
    work = Path(tempfile.mkdtemp(prefix='lss-recover-' + arguments.line + '-'))
    planned = plan(arguments, arguments.line, work)
    for planned_row in planned:
        shown = dict(planned_row['payload'])
        shown['changelog'] = shown['changelog'][:40] + '...'
        print(json.dumps(shown))
    if arguments.publish:
        publish(arguments, planned)
