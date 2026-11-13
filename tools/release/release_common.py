"""Shared facts and helpers for the release tools.

Nothing here is specific to one release. A release is named by its version; everything else is
read from the tags (each line's `.github/line.env` as tagged) and from two local directories:

  state  ~/.local/state/lss-publish-v<version>/        receipts and logs of what was published
  gates  ~/.local/state/lss-release-prep-v<version>/gates/   the gated jars and manifest.json

Both can be moved with --state / --gates. Secrets are read from the file named by
LSS_SECRETS_FILE (default ~/.bot.env); no tool prints them.
"""
from __future__ import annotations

import io
import json
import os
import re
import subprocess
import urllib.request
import zipfile
import hashlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REPO_SLUG = os.environ.get('LSS_REPO_SLUG', 'VoX/lod-server-support')
MODRINTH_API = 'https://api.modrinth.com/v2/'
LSS_PROJECT = 'lKiXKLvv'
VSS_PROJECT = '84zcagOb'
CURSEFORGE_PROJECT = 1763550
USER_AGENT = {'User-Agent': 'lod-server-support-release-tools/1 (github.com/' + REPO_SLUG + ')'}
# Used when a tag predates config/lines/lines.json.
LEGACY_LINES = ('1.21.1', '1.21.10', '1.21.11', '26.1', '26.2', '26.3')
LEGACY_REFERENCE = '26.3'


def line_key(line):
    """Sort key that orders Minecraft lines oldest first (1.21.1 < 1.21.10 < 26.1)."""
    return tuple(int(part) for part in line.split('.'))


VERSION_FORM = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?')


def check_version(version):
    """The one rule for version strings, shared with release.yml: X.Y.Z, or X.Y.Z-alpha.N,
    -beta.N or -rc.N. Anything else is refused before a tool acts on it."""
    if not VERSION_FORM.fullmatch(version):
        raise SystemExit('version ' + repr(version) + ' is not X.Y.Z or X.Y.Z-(alpha|beta|rc).N')
    return version


def is_prerelease(version):
    """0.22.0-beta.1 and 0.22.0-rc.1 are prereleases; 0.22.0 is not."""
    return '-' in version


def modrinth_version_type(version):
    """What release.yml publishes: alpha for -alpha.N, beta for -beta.N and -rc.N."""
    if not is_prerelease(version):
        return 'release'
    return 'alpha' if '-alpha.' in version else 'beta'


def git(*args, repo=REPO_ROOT, check=True):
    result = subprocess.run(['git', '-C', str(repo), *args], capture_output=True, text=True)
    if check and result.returncode:
        raise SystemExit('git ' + ' '.join(args) + ': ' + result.stderr.strip())
    return result.stdout


def parse_env(text):
    out = {}
    for row in text.replace('\r', '').splitlines():
        if '=' in row and not row.lstrip().startswith('#'):
            key, value = row.split('=', 1)
            out[key.strip()] = value.strip()
    return out


def known_lines(repo=REPO_ROOT, ref='HEAD'):
    """(lines oldest first, reference line) from config/lines/lines.json at ref."""
    shown = subprocess.run(['git', '-C', str(repo), 'show', ref + ':config/lines/lines.json'],
                           capture_output=True, text=True)
    if shown.returncode:
        return sorted(LEGACY_LINES, key=line_key), LEGACY_REFERENCE
    data = json.loads(shown.stdout)
    return sorted(data['lines'], key=line_key), data['reference']


def tag_for(version, line, reference):
    return 'v' + version if line == reference else 'v' + version + '+mc' + line


def release_lines(version, repo=REPO_ROOT, only=None):
    """[(line, tag)] for every line that has a local tag for this version, oldest first and the
    reference line (the one GitHub marks Latest) last. `only` restricts the set."""
    lines, reference = known_lines(repo)
    rows = []
    for line in lines:
        if only and line not in only:
            continue
        tag = tag_for(version, line, reference)
        if subprocess.run(['git', '-C', str(repo), 'rev-parse', '-q', '--verify', 'refs/tags/' + tag],
                          capture_output=True).returncode == 0:
            rows.append((line, tag))
    rows.sort(key=lambda row: (row[0] == reference, line_key(row[0])))
    return rows


def line_env(tag, repo=REPO_ROOT):
    """The line's facts exactly as tagged."""
    return parse_env(git('show', tag + ':.github/line.env', repo=repo))


def tag_notes(tag, repo=REPO_ROOT):
    return git('for-each-ref', '--format=%(contents)', 'refs/tags/' + tag, repo=repo).strip()


def platforms(env):
    """The LSS platforms a line ships, in release.yml's upload order."""
    out = ['fabric', 'paper']
    if env.get('LINE_SHIP_NEOFORGE') == 'true':
        out.append('neoforge')
    if env.get('LINE_SHIP_SPONGE') == 'true':
        out.append('sponge')
    return out


def modrinth_number(version, platform, env):
    return 'v' + version + '+' + platform + '+mc' + env['LINE_MC_' + platform.upper()]


def published_notes(text, tag):
    """release.yml appends GitHub's compare (or commits) link; everything above it is the notes."""
    pattern = (r'\n\n\*\*Full Changelog\*\*: https://github\.com/' + re.escape(REPO_SLUG)
               + r'/(?:compare/\S+\.\.\.|commits/)' + re.escape(tag) + r'\s*$')
    match = re.search(pattern, text)
    return text[:match.start()].strip() if match else text.strip()


def secret(name):
    path = Path(os.environ.get('LSS_SECRETS_FILE', str(Path.home() / '.bot.env')))
    for row in path.read_text().replace('\r', '').splitlines():
        if row.startswith(name + '='):
            return row.split('=', 1)[1].strip().strip('"\'')
    raise SystemExit(name + ' is missing from ' + str(path))


def fetch(url, headers=None, timeout=300):
    request = urllib.request.Request(url, headers={**USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def zip_entries(data):
    """Entry name -> sha256 of its content. CI rebuilds jars, so two builds of one commit may
    differ in entry timestamps while every entry's content is equal."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {info.filename: hashlib.sha256(archive.read(info)).hexdigest()
                for info in archive.infolist() if not info.is_dir()}


def multipart(metadata_name, metadata, filename, data):
    """(body, content type) for the one-JSON-part-plus-one-file uploads both stores take."""
    import uuid
    boundary = 'LSSRelease' + uuid.uuid4().hex
    body = (('--' + boundary + '\r\nContent-Disposition: form-data; name="' + metadata_name
             + '"\r\nContent-Type: application/json\r\n\r\n').encode()
            + json.dumps(metadata).encode()
            + ('\r\n--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="'
               + filename + '"\r\nContent-Type: application/java-archive\r\n\r\n').encode()
            + data + ('\r\n--' + boundary + '--\r\n').encode())
    return body, 'multipart/form-data; boundary=' + boundary


def default_state(version):
    return Path.home() / '.local/state' / ('lss-publish-v' + version)


def default_gates(version):
    return Path.home() / '.local/state' / ('lss-release-prep-v' + version) / 'gates'


def add_common_arguments(parser):
    parser.add_argument('--version', required=True, help='for example 0.22.0 or 0.22.0-beta.1')
    parser.add_argument('--state', type=Path, help='receipts and logs (default ~/.local/state/lss-publish-v<version>)')
    parser.add_argument('--gates', type=Path, help='gated jars (default ~/.local/state/lss-release-prep-v<version>/gates)')
    parser.add_argument('--repo', type=Path, default=REPO_ROOT, help='a checkout that has the release tags')
    parser.add_argument('--lines', help='comma-separated subset of lines (default: every line with a tag)')


def resolve(args):
    """Fill the defaults that depend on the version, and create the state directory."""
    check_version(args.version)
    args.state = args.state or default_state(args.version)
    args.gates = args.gates or default_gates(args.version)
    args.state.mkdir(parents=True, exist_ok=True)
    args.only = set(args.lines.split(',')) if args.lines else None
    return args


def unsynced(version, repo=REPO_ROOT, only=None):
    """{line: reason} for every support line of the release that is not in step with the release
    commit on main. In step means: the line's recorded sync point and main's tag differ only in
    line-local paths (docs, per-line config)."""
    import fnmatch
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'lines'))
    from lines import EXEMPT
    rows = release_lines(version, repo, only)
    _, reference = known_lines(repo)
    main_tags = [tag for line, tag in rows if line == reference]
    if not main_tags:
        return {line: 'the reference line has no tag for ' + version for line, _ in rows}
    out = {}
    for line, tag in rows:
        if line == reference:
            continue
        shown = subprocess.run(['git', '-C', str(repo), 'show', tag + ':config/lines/sync.json'],
                               capture_output=True, text=True)
        if shown.returncode:
            out[line] = tag + ' has no config/lines/sync.json'
            continue
        synced = json.loads(shown.stdout)['reference_commit']
        diff = subprocess.run(['git', '-C', str(repo), 'diff', '--name-only', synced, main_tags[0] + '^{commit}'],
                              capture_output=True, text=True)
        if diff.returncode:
            out[line] = 'cannot compare ' + synced[:9] + ' with ' + main_tags[0]
            continue
        behind = [path for path in diff.stdout.splitlines()
                  if not any(fnmatch.fnmatchcase(path, pattern) for pattern in EXEMPT)]
        if behind:
            out[line] = ('synced to ' + synced[:9] + ', which differs from ' + main_tags[0] + ' in '
                         + str(len(behind)) + ' shared path(s), for example ' + behind[0])
    return out


def main():
    """`release_common.py lines --version V [--lines a,b]` prints "<line> <tag>" rows in push order.
    `release_common.py synced --version V` exits 1 and names each support line that is not in
    step with the release commit on main."""
    import argparse
    parser = argparse.ArgumentParser(description=main.__doc__)
    parser.add_argument('command', choices=['lines', 'synced'])
    parser.add_argument('--version', required=True)
    parser.add_argument('--repo', type=Path, default=REPO_ROOT)
    parser.add_argument('--lines')
    args = parser.parse_args()
    only = set(args.lines.split(',')) if args.lines else None
    if args.command == 'synced':
        problems = unsynced(args.version, args.repo, only)
        for line, reason in sorted(problems.items()):
            print(line + ': ' + reason)
        raise SystemExit(1 if problems else 0)
    for line, tag in release_lines(args.version, args.repo, only):
        print(line, tag)


if __name__ == '__main__':
    main()
