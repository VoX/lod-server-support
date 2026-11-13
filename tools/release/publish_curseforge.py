#!/usr/bin/env python3
"""Upload a published LSS release's Fabric and NeoForge jars to the CurseForge mods project.

Run it after verify_lss.py has passed for every line. It mirrors the published Modrinth
versions: for each line and loader that has a Modrinth version, the display name and Minecraft
versions come from that version, the file is the GitHub release asset (sha512-checked against
the Modrinth file), and the changelog is the line's tag notes. Paper and Sponge jars are not
uploaded (a CurseForge mods project cannot host them). Fabric files declare Fabric API as a
required dependency. Uploaded files wait for CurseForge moderation before they are public.

Usage: publish_curseforge.py --version X.Y.Z            (prints the plan)
       publish_curseforge.py --version X.Y.Z --publish  (uploads, oldest line first)
A receipt per file (<state>/cf-<line>-<loader>.json) makes a rerun skip what was uploaded.
"""
import argparse
import hashlib
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import release_common as rc

API = 'https://minecraft.curseforge.com/api/'
LOADERS = {'fabric': 'Fabric', 'neoforge': 'NeoForge'}


def cf_versions(token):
    headers = {'X-Api-Token': token}
    types = {t['id']: t['name'] for t in json.loads(rc.fetch(API + 'game/version-types', headers, 120))}
    out = {}
    for version in json.loads(rc.fetch(API + 'game/versions', headers, 120)):
        type_name = types.get(version['gameVersionTypeID'], '')
        # Minecraft versions live in "Minecraft x.y" types, and in bare "26.2"-style types for the new scheme.
        if (type_name.startswith('Minecraft ') or type_name == version['name'].rsplit('.', 1)[0]
                or type_name == version['name']):
            out.setdefault(('mc', version['name']), version['id'])
        if type_name in ('Modloader', 'Environment'):
            out[(type_name, version['name'])] = version['id']
    return out


def plan(args, token):
    ids = cf_versions(token)
    rows = []
    for line, tag in rc.release_lines(args.version, args.repo, args.only):
        notes = rc.tag_notes(tag, args.repo)
        assert notes.startswith('### '), (tag, notes[:40])
        env = rc.line_env(tag, args.repo)
        for loader, cf_name in LOADERS.items():
            if loader not in rc.platforms(env):
                continue
            number = rc.modrinth_number(args.version, loader, env)
            try:
                modrinth = json.loads(rc.fetch(rc.MODRINTH_API + 'project/' + rc.LSS_PROJECT + '/version/'
                                               + urllib.parse.quote(number, safe='')))
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    continue
                raise
            published = modrinth['files'][0]
            asset = args.state / 'cf-assets' / published['filename']
            if not asset.exists():
                asset.parent.mkdir(exist_ok=True)
                subprocess.run(['gh', 'release', 'download', tag, '-R', rc.REPO_SLUG, '-p', published['filename'],
                                '-D', str(asset.parent), '--clobber'], check=True, capture_output=True)
            data = asset.read_bytes()
            assert hashlib.sha512(data).hexdigest() == published['hashes']['sha512'], ('asset != Modrinth file', asset)
            game_versions = ([ids[('mc', g)] for g in modrinth['game_versions']] + [ids[('Modloader', cf_name)]]
                             + [ids[('Environment', 'Client')], ids[('Environment', 'Server')]])
            meta = dict(changelog=notes, changelogType='markdown', displayName=modrinth['name'],
                        gameVersions=game_versions, releaseType='release')
            if loader == 'fabric':
                meta['relations'] = {'projects': [{'slug': 'fabric-api', 'type': 'requiredDependency'}]}
            rows.append(dict(line=line, loader=loader, file=str(asset), filename=published['filename'],
                             mc=modrinth['game_versions'], meta=meta))
    return rows


def publish(args, rows, token):
    for row in rows:
        receipt = args.state / ('cf-' + row['line'] + '-' + row['loader'] + '.json')
        if receipt.exists():
            print(row['line'], row['loader'], 'already uploaded (receipt)',
                  json.loads(receipt.read_text()).get('id'), flush=True)
            continue
        body, content_type = rc.multipart('metadata', row['meta'], row['filename'], Path(row['file']).read_bytes())
        request = urllib.request.Request(API + 'projects/' + str(args.project_id) + '/upload-file', data=body,
                                         method='POST', headers={'X-Api-Token': token, 'Content-Type': content_type,
                                                                 **rc.USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                out = json.load(response)
        except urllib.error.HTTPError as error:
            raise SystemExit(row['line'] + ' ' + row['loader'] + ': HTTP ' + str(error.code) + ' '
                             + repr(error.read()[:400]))
        receipt.write_text(json.dumps(dict(out, filename=row['filename'], mc=row['mc']), indent=2) + '\n')
        print(row['line'], row['loader'], 'uploaded file id', out.get('id'), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    rc.add_common_arguments(parser)
    parser.add_argument('--project-id', type=int, default=rc.CURSEFORGE_PROJECT)
    parser.add_argument('--publish', action='store_true')
    arguments = rc.resolve(parser.parse_args())
    if rc.is_prerelease(arguments.version):
        raise SystemExit('CurseForge takes stable releases only')
    api_token = rc.secret('CURSEFORGE_API_TOKEN')
    planned = plan(arguments, api_token)
    (arguments.state / 'cf-publication-plan.json').write_text(json.dumps(
        [dict(r, meta=dict(r['meta'], changelog=r['meta']['changelog'][:60] + '…')) for r in planned], indent=2) + '\n')
    if arguments.publish:
        publish(arguments, planned, api_token)
    else:
        for planned_row in planned:
            print(planned_row['line'], planned_row['loader'], planned_row['filename'], planned_row['mc'],
                  planned_row['meta']['displayName'], planned_row['meta']['gameVersions'],
                  'deps' if 'relations' in planned_row['meta'] else '')
