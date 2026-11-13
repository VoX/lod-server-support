#!/usr/bin/env python3
"""Verify one published LSS line.

Checks the GitHub release (body equals the tag notes, draft and prerelease flags, the Latest
flag, the asset set) and every Modrinth version (exists exactly once, changelog equals the
notes, version type, and its file is byte-identical to the GitHub asset). Published jars are
compared entry by entry with the locally gated jars: CI rebuilds them, so only entry timestamps
may differ.

Usage: verify_lss.py --version X.Y.Z <line>
Exit 0 when nothing is wrong. The result is also written to <state>/lss-verified-<line>.json.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import urllib.error
import urllib.parse

import release_common as rc


def gh_json(*args):
    result = subprocess.run(['gh', *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def verify(args, line):
    lines, reference = rc.known_lines(args.repo)
    tag = rc.tag_for(args.version, line, reference)
    env = rc.line_env(tag, args.repo)
    notes = rc.tag_notes(tag, args.repo)
    prerelease = rc.is_prerelease(args.version)
    release = gh_json('release', 'view', tag, '-R', rc.REPO_SLUG, '--json',
                      'tagName,body,isDraft,isPrerelease,assets,url')
    problems = []
    if rc.published_notes(release['body'], tag) != notes:
        problems.append('GitHub body != tag notes')
    if release['isDraft']:
        problems.append('GitHub release is a draft')
    if release['isPrerelease'] != prerelease:
        problems.append('GitHub prerelease flag is ' + str(release['isPrerelease']))
    latest = gh_json('api', 'repos/' + rc.REPO_SLUG + '/releases/latest')['tag_name']
    if not prerelease and env.get('LINE_MAKE_LATEST') == 'true' and latest != tag:
        problems.append('expected Latest, Latest is ' + latest)
    if prerelease and latest == tag:
        problems.append('a prerelease is marked Latest')

    shipped = rc.platforms(env)
    assets = {asset['name']: asset for asset in release['assets']}
    github_sha512 = {}
    for platform in shipped:
        prefix = 'lod-server-support-' + platform + '-' + args.version
        names = [name for name in assets if name.startswith(prefix)]
        if len(names) != 1:
            problems.append('GitHub asset ' + platform + ': ' + str(names))
            continue
        data = rc.fetch(assets[names[0]]['url'])
        github_sha512[platform] = hashlib.sha512(data).hexdigest()
        local = args.gates / line / 'jars' / names[0]
        if not local.exists():
            problems.append('no gated jar ' + local.name)
            continue
        published, gated = rc.zip_entries(data), rc.zip_entries(local.read_bytes())
        differing = sorted(k for k in published.keys() | gated.keys() if published.get(k) != gated.get(k))
        if differing:
            problems.append(names[0] + ' differs from the gated jar in ' + str(len(differing))
                            + ' entries: ' + str(differing[:5]))
    extra = [name for name in assets
             if not any(name.startswith('lod-server-support-' + p + '-') for p in shipped)]
    if extra:
        problems.append('unexpected GitHub assets ' + str(extra))

    # The project's version list is cached for minutes; a lookup by number is fresh. The list
    # still catches duplicates once it has caught up.
    listed = json.loads(rc.fetch(rc.MODRINTH_API + 'project/' + rc.LSS_PROJECT + '/version'))
    wanted_type = rc.modrinth_version_type(args.version)
    ids = {}
    for platform in shipped:
        number = rc.modrinth_number(args.version, platform, env)
        if sum(1 for v in listed if v['version_number'] == number) > 1:
            problems.append('Modrinth ' + number + ': duplicate versions')
        try:
            version = json.loads(rc.fetch(rc.MODRINTH_API + 'project/' + rc.LSS_PROJECT + '/version/'
                                          + urllib.parse.quote(number, safe='')))
        except urllib.error.HTTPError as error:
            problems.append('Modrinth ' + number + ': HTTP ' + str(error.code))
            continue
        ids[platform] = version['id']
        if rc.published_notes(version['changelog'], tag) != notes:
            problems.append('Modrinth ' + number + ' changelog != notes')
        if version['version_type'] != wanted_type or version['status'] != 'listed':
            problems.append('Modrinth ' + number + ' is ' + version['version_type'] + '/' + version['status'])
        if platform in github_sha512 and version['files'][0]['hashes']['sha512'] != github_sha512[platform]:
            problems.append('Modrinth ' + number + ' file != GitHub asset')

    result = dict(line=line, tag=tag, url=release['url'], latest=latest, modrinth=ids,
                  platforms=shipped, problems=problems)
    (args.state / ('lss-verified-' + line + '.json')).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=1))
    return 0 if not problems else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    rc.add_common_arguments(parser)
    parser.add_argument('line')
    arguments = rc.resolve(parser.parse_args())
    sys.exit(verify(arguments, arguments.line))
