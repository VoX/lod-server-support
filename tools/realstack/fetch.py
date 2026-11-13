"""Downloads for the real-stack matrix.

Every file comes from an official source and is checked against a published or pinned hash before
it is given its final name in the cache, so a file that exists in the cache has been verified. No
account, token or credential is used anywhere; the only header sent is a generic User-Agent.

Cache layout (LSS_REALSTACK_CACHE, default ~/.cache/lss-realstack):

  modrinth/<version id>/<file>          pinned third-party mods and published LSS jars
  lss/<version>-<loader>-<mc>.json      which Modrinth file a published LSS selector resolved to
  mojang/<mc>/version.json, client.jar, server.jar
  libraries/<maven path>                vanilla and Fabric libraries
  assets/indexes, assets/objects        vanilla assets (an object's name is its sha1)
  fabric/<mc>-<loader>-<side>.json      Fabric loader profiles
  neoforge/<version>/                   installer jar and the client and server installs
  paper/<mc>/<file>                     pinned Paper server jars
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

USER_AGENT = 'lss-realstack/1'
MIN_FREE_BYTES = 15 * 1024 ** 3
MODRINTH_API = 'https://api.modrinth.com/v2'
MOJANG_MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'
FABRIC_META = 'https://meta.fabricmc.net/v2'
FABRIC_MAVEN = 'https://maven.fabricmc.net/'
NEOFORGE_MAVEN = 'https://maven.neoforged.net/releases'
PAPER_API = 'https://fill.papermc.io/v3/projects/paper'
PAPER_DOWNLOAD = 'server:default'
ASSET_BASE = 'https://resources.download.minecraft.net'
SAFE_FILE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$')
SAFE_ID = re.compile(r'^[A-Za-z0-9]{6,16}$')
ALLOWED_HOSTS = frozenset({
    'api.modrinth.com', 'cdn.modrinth.com',
    'piston-meta.mojang.com', 'piston-data.mojang.com', 'libraries.minecraft.net',
    'resources.download.minecraft.net',
    'meta.fabricmc.net', 'maven.fabricmc.net',
    'maven.neoforged.net',
    'fill.papermc.io', 'fill-data.papermc.io',
})


class FetchError(RuntimeError):
    """A download was refused, failed, or did not match its hash."""


def cache_root() -> Path:
    """The download cache: LSS_REALSTACK_CACHE, or ~/.cache/lss-realstack."""
    override = os.environ.get('LSS_REALSTACK_CACHE')
    return Path(override) if override else Path.home() / '.cache' / 'lss-realstack'


def seed_roots() -> list[Path]:
    """Optional local folders (LSS_REALSTACK_SEED_ROOTS, ':'-separated) that already hold
    `libraries/` and `assets/objects/` trees. They are only ever read, and a file found there is
    used only when its hash matches, so the tool behaves the same without them."""
    raw = os.environ.get('LSS_REALSTACK_SEED_ROOTS', '')
    return [Path(part) for part in raw.split(':') if part]


def free_bytes(path: Path) -> int:
    """Free space on the filesystem that holds `path` (or its nearest existing parent)."""
    probe = path
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    return shutil.disk_usage(probe).free


def require_free_space(minimum: int = MIN_FREE_BYTES) -> int:
    """Stop before downloading or staging when the home filesystem is short of space."""
    free = free_bytes(Path.home())
    if free < minimum:
        raise FetchError(f'only {free / 1024 ** 3:.1f} GiB free on the home filesystem; '
                         f'{minimum / 1024 ** 3:.0f} GiB are required')
    return free


def check_url_allowed(url: str) -> None:
    """Refuse anything that is not https on one of the official hosts."""
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != 'https' or parts.hostname not in ALLOWED_HOSTS:
        raise FetchError(f'refusing to download from {url}: not an allowed official source')


def http_get(url: str, timeout: int = 180) -> bytes:
    """GET one URL from an allowed host with the generic User-Agent."""
    check_url_allowed(url)
    request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            final = response.geturl()
            check_url_allowed(final)
            return response.read()
    except OSError as error:
        raise FetchError(f'download failed: {url}: {error}') from error


def http_json(url: str):
    """GET and decode a JSON document."""
    return json.loads(http_get(url))


def bytes_digest(data: bytes, algorithm: str) -> str:
    """Hex digest of a byte string."""
    return hashlib.new(algorithm, data).hexdigest()


def file_digest(path: Path, algorithm: str) -> str:
    """Hex digest of a file, read in chunks."""
    digest = hashlib.new(algorithm)
    with open(path, 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def file_matches(path: Path, algorithm: str, expected: str) -> bool:
    """True when the file exists and has the expected digest."""
    return path.is_file() and file_digest(path, algorithm) == expected.lower()


def write_verified(dest: Path, data: bytes, algorithm: str, expected: str, source: str) -> None:
    """Write `data` to `dest` only if it has the expected digest (temporary name, then rename)."""
    actual = bytes_digest(data, algorithm)
    if actual != expected.lower():
        raise FetchError(f'{algorithm} mismatch for {source}: got {actual}, expected {expected}')
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + '.part')
    partial.write_bytes(data)
    os.replace(partial, dest)


def fetch_verified(url: str, dest: Path, algorithm: str, expected: str,
                   seeds: tuple[Path, ...] = (), trust_existing: bool = False) -> str:
    """Make `dest` exist with the expected digest.

    Returns how: 'cached' (already there and verified), 'seeded' (copied from a local seed folder
    after its hash matched) or 'downloaded'. `trust_existing` skips re-hashing a file that is
    already in the cache; it is used for asset objects, whose name is their hash.
    """
    if dest.is_file() and (trust_existing or file_matches(dest, algorithm, expected)):
        return 'cached'
    for seed in seeds:
        if file_matches(seed, algorithm, expected):
            write_verified(dest, seed.read_bytes(), algorithm, expected, str(seed))
            return 'seeded'
    write_verified(dest, http_get(url), algorithm, expected, url)
    return 'downloaded'


def maven_path(name: str) -> str:
    """Repository path of a Maven coordinate: group:artifact:version[:classifier]."""
    parts = name.split(':')
    if len(parts) not in (3, 4):
        raise FetchError(f'not a Maven coordinate: {name}')
    group, artifact, version = parts[:3]
    classifier = '-' + parts[3] if len(parts) == 4 else ''
    return f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}{classifier}.jar'


def library_key(name: str) -> tuple[str, str, str]:
    """What a launcher treats as 'the same library': group, artifact and classifier."""
    parts = name.split(':')
    return parts[0], parts[1], parts[3] if len(parts) > 3 else ''


def rules_allow(rules: list[dict] | None, os_name: str = 'linux', arch: str = 'x86_64') -> bool:
    """Evaluate a launcher rule list for this machine. Feature rules never match (no demo mode,
    no custom launcher features), exactly as a plain launch would behave."""
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if 'features' in rule:
            continue
        wanted = rule.get('os', {})
        if 'name' in wanted and wanted['name'] != os_name:
            continue
        if 'arch' in wanted and wanted['arch'] != arch:
            continue
        allowed = rule.get('action') == 'allow'
    return allowed


def vanilla_libraries(version_json: dict) -> list[dict]:
    """The libraries a Linux client needs, from a Mojang version document."""
    libraries = []
    for entry in version_json.get('libraries', []):
        artifact = entry.get('downloads', {}).get('artifact')
        if not artifact or not rules_allow(entry.get('rules')):
            continue
        libraries.append({'name': entry['name'], 'path': artifact['path'],
                          'url': artifact['url'], 'sha1': artifact['sha1']})
    return libraries


def merge_libraries(first: list[dict], second: list[dict]) -> list[dict]:
    """Concatenate two library lists keeping one entry per group:artifact:classifier. The first
    list wins, as a launcher lets the mod loader's version of a library replace the game's."""
    seen = set()
    merged = []
    for entry in first + second:
        key = library_key(entry['name'])
        if key not in seen:
            seen.add(key)
            merged.append(entry)
    return merged


# --------------------------------------------------------------------------- Modrinth

def modrinth_versions(project: str, minecraft: str, loader: str) -> list[dict]:
    """Published versions of a Modrinth project for one Minecraft version and loader."""
    query = urllib.parse.urlencode({'game_versions': json.dumps([minecraft]),
                                    'loaders': json.dumps([loader])})
    return http_json(f'{MODRINTH_API}/project/{urllib.parse.quote(project)}/version?{query}')


def primary_file(version: dict) -> dict:
    """The file a launcher installs for a Modrinth version."""
    files = version['files']
    return next((entry for entry in files if entry.get('primary')), files[0])


def pin_from_version(slug: str, version: dict) -> dict:
    """The stacks.json entry that pins one Modrinth version's primary file. The id and the file
    name become part of a cache path, so anything but a plain id and a plain jar name is refused."""
    chosen = primary_file(version)
    if not SAFE_ID.match(version['id']) or not SAFE_FILE.match(chosen['filename']):
        raise FetchError(f'unexpected Modrinth version id or file name: {version["id"]!r}, '
                         f'{chosen["filename"]!r}')
    return {'slug': slug, 'project_id': version['project_id'], 'version_id': version['id'],
            'version_number': version['version_number'], 'file': chosen['filename'],
            'sha512': chosen['hashes']['sha512']}


def fetch_pinned_mod(pin: dict, cache: Path) -> tuple[Path, str]:
    """Put one pinned Modrinth file in the cache and return (path, how).

    The pin's sha512 is the authority: a cached file that matches needs no network at all, and a
    download is refused when Modrinth publishes a different hash for that version's file.
    """
    dest = cache / 'modrinth' / pin['version_id'] / pin['file']
    if file_matches(dest, 'sha512', pin['sha512']):
        return dest, 'cached'
    version = http_json(f'{MODRINTH_API}/version/{pin["version_id"]}')
    published = next((entry for entry in version['files'] if entry['filename'] == pin['file']), None)
    if published is None:
        raise FetchError(f'{pin["slug"]} version {pin["version_id"]} has no file {pin["file"]}')
    if published['hashes']['sha512'] != pin['sha512']:
        raise FetchError(f'{pin["slug"]} {pin["file"]}: Modrinth publishes sha512 '
                         f'{published["hashes"]["sha512"][:16]}…, the pin says {pin["sha512"][:16]}…')
    write_verified(dest, http_get(published['url']), 'sha512', pin['sha512'], published['url'])
    return dest, 'downloaded'


def match_lss_version(versions: list[dict], wanted: str) -> dict:
    """Pick the published LSS version `wanted` (for example 0.21.0) out of a Modrinth listing that
    is already filtered to one loader and Minecraft version. Version numbers look like
    v0.21.0+fabric+mc26.3; exactly one must match."""
    matches = [entry for entry in versions
               if entry['version_number'].lstrip('v').split('+')[0] == wanted]
    if len(matches) != 1:
        available = sorted({entry['version_number'] for entry in versions})
        raise FetchError(f'expected exactly one published LSS {wanted}, found {len(matches)}; '
                         f'available: {", ".join(available) or "none"}')
    return matches[0]


def fetch_published_lss(project: str, version: str, minecraft: str, loader: str,
                        cache: Path) -> tuple[Path, dict]:
    """Download the published LSS jar for one platform, verified against Modrinth's sha512.

    The resolution is remembered in the cache, so a later run of the same selector re-verifies
    the cached jar and needs no network. Returns (path, record).
    """
    index = cache / 'lss' / f'{version}-{loader}-{minecraft}.json'
    if index.is_file():
        record = json.loads(index.read_text())
        dest = cache / 'modrinth' / record['version_id'] / record['file']
        if file_matches(dest, 'sha512', record['sha512']):
            return dest, record
    listed = match_lss_version(modrinth_versions(project, minecraft, loader), version)
    record = pin_from_version('lod-server-support', listed)
    record['source'] = f'published:{version}'
    dest = cache / 'modrinth' / record['version_id'] / record['file']
    if not file_matches(dest, 'sha512', record['sha512']):
        url = primary_file(listed)['url']
        write_verified(dest, http_get(url), 'sha512', record['sha512'], url)
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_text(json.dumps(record, indent=1) + '\n')
    return dest, record


# --------------------------------------------------------------------------- Mojang

def mojang_version(minecraft: str, cache: Path) -> dict:
    """Mojang's version document (libraries, client and server jars, asset index, Java version),
    verified against the sha1 in the version manifest."""
    dest = cache / 'mojang' / minecraft / 'version.json'
    if not dest.is_file():
        manifest = http_json(MOJANG_MANIFEST)
        entry = next((item for item in manifest['versions'] if item['id'] == minecraft), None)
        if entry is None:
            raise FetchError(f'Minecraft {minecraft} is not in the Mojang version manifest')
        write_verified(dest, http_get(entry['url']), 'sha1', entry['sha1'], entry['url'])
    return json.loads(dest.read_text())


def fetch_game_jar(version_json: dict, side: str, cache: Path) -> Path:
    """The official client or server jar of a Minecraft version (sha1 from Mojang)."""
    download = version_json['downloads'][side]
    dest = cache / 'mojang' / version_json['id'] / f'{side}.jar'
    fetch_verified(download['url'], dest, 'sha1', download['sha1'])
    return dest


def fetch_libraries(libraries: list[dict], cache: Path) -> tuple[list[Path], dict]:
    """Download a resolved library list into cache/libraries. Returns the paths in order and a
    count of how each file was obtained."""
    seeds = seed_roots()

    def one(entry: dict) -> tuple[Path, str]:
        """Fetch one library; returns its path and how it was obtained."""
        dest = cache / 'libraries' / entry['path']
        local = tuple(seed / 'libraries' / entry['path'] for seed in seeds)
        return dest, fetch_verified(entry['url'], dest, 'sha1', entry['sha1'], seeds=local)

    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(one, libraries))
    counts: dict[str, int] = {}
    for _, how in results:
        counts[how] = counts.get(how, 0) + 1
    return [path for path, _ in results], counts


def fetch_assets(version_json: dict, cache: Path) -> tuple[Path, dict]:
    """Download the asset index and every object it names. Returns (assets dir, counts)."""
    assets = cache / 'assets'
    index_info = version_json['assetIndex']
    index_path = assets / 'indexes' / f'{index_info["id"]}.json'
    fetch_verified(index_info['url'], index_path, 'sha1', index_info['sha1'])
    hashes = sorted({item['hash'] for item in json.loads(index_path.read_text())['objects'].values()})
    seeds = seed_roots()

    def one(sha1: str) -> str:
        """Fetch one asset object; returns how it was obtained."""
        relative = f'{sha1[:2]}/{sha1}'
        local = tuple(seed / 'assets' / 'objects' / relative for seed in seeds)
        return fetch_verified(f'{ASSET_BASE}/{relative}', assets / 'objects' / relative, 'sha1', sha1,
                              seeds=local, trust_existing=True)

    with ThreadPoolExecutor(16) as pool:
        results = list(pool.map(one, hashes))
    counts = {'objects': len(hashes)}
    for how in results:
        counts[how] = counts.get(how, 0) + 1
    return assets, counts


# --------------------------------------------------------------------------- Fabric

def fabric_profile(minecraft: str, loader_version: str, side: str, cache: Path) -> dict:
    """The Fabric loader's launcher profile for the client ('client') or the server ('server')."""
    dest = cache / 'fabric' / f'{minecraft}-{loader_version}-{side}.json'
    if not dest.is_file():
        endpoint = 'profile' if side == 'client' else 'server'
        url = f'{FABRIC_META}/versions/loader/{minecraft}/{loader_version}/{endpoint}/json'
        document = http_json(url)
        if 'libraries' not in document or 'mainClass' not in document:
            raise FetchError(f'unexpected Fabric profile from {url}')
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(document, indent=1) + '\n')
    return json.loads(dest.read_text())


def fabric_libraries(profile: dict) -> list[dict]:
    """Resolve a Fabric profile's libraries to downloads. Most carry a sha1; the loader and
    intermediary entries do not, and for those the Maven `.sha1` file is fetched instead."""
    libraries = []
    for entry in profile['libraries']:
        path = maven_path(entry['name'])
        url = entry.get('url', FABRIC_MAVEN).rstrip('/') + '/' + path
        sha1 = entry.get('sha1')
        libraries.append({'name': entry['name'], 'path': path, 'url': url, 'sha1': sha1})
    return libraries


def resolve_sidecar_hashes(libraries: list[dict], cache: Path) -> list[dict]:
    """Fill in a missing sha1 from the repository's `.sha1` file. A library that is already in
    the cache keeps the hash recorded beside it, so no network is needed."""
    resolved = []
    for entry in libraries:
        if entry['sha1']:
            resolved.append(entry)
            continue
        note = cache / 'libraries' / (entry['path'] + '.sha1')
        if not note.is_file():
            text = http_get(entry['url'] + '.sha1').decode('ascii').split()[0].strip().lower()
            if len(text) != 40 or any(char not in '0123456789abcdef' for char in text):
                raise FetchError(f'bad .sha1 file for {entry["url"]}')
            note.parent.mkdir(parents=True, exist_ok=True)
            note.write_text(text + '\n')
        resolved.append({**entry, 'sha1': note.read_text().strip()})
    return resolved


# --------------------------------------------------------------------------- NeoForge

def neoforge_installer(version: str, sha256: str, cache: Path) -> Path:
    """The official NeoForge installer jar, verified against the sha256 pinned in stacks.json."""
    name = f'neoforge-{version}-installer.jar'
    dest = cache / 'neoforge' / version / name
    fetch_verified(f'{NEOFORGE_MAVEN}/net/neoforged/neoforge/{version}/{name}', dest, 'sha256', sha256)
    return dest


def neoforge_published_sha256(version: str) -> str:
    """The sha256 that maven.neoforged.net publishes for an installer (used by `pin`)."""
    name = f'neoforge-{version}-installer.jar'
    url = f'{NEOFORGE_MAVEN}/net/neoforged/neoforge/{version}/{name}.sha256'
    return http_get(url).decode('ascii').split()[0].strip().lower()


# --------------------------------------------------------------------------- Paper

def paper_builds(minecraft: str) -> list[dict]:
    """The builds PaperMC publishes for one Minecraft version, newest first (the fill v3 API)."""
    listing = http_json(f'{PAPER_API}/versions/{urllib.parse.quote(minecraft)}/builds')
    builds = listing if isinstance(listing, list) else listing.get('builds', [])
    return sorted(builds, key=lambda build: build['id'], reverse=True)


def choose_paper_build(builds: list[dict], wanted: int | None = None) -> dict:
    """The build to pin: the one asked for, otherwise the newest STABLE build, otherwise (a
    version PaperMC has not called stable yet) the newest of any channel."""
    if wanted is not None:
        builds = [build for build in builds if build['id'] == wanted]
    stable = [build for build in builds if build.get('channel') == 'STABLE']
    if not (stable or builds):
        raise FetchError('PaperMC lists no such build')
    return (stable or builds)[0]


def paper_pin_from_build(build: dict) -> dict:
    """The stacks.json `paper` entry that pins one build's server jar. The file name becomes
    part of a cache path, so anything but a plain jar name is refused."""
    download = build['downloads'][PAPER_DOWNLOAD]
    sha256 = download['checksums']['sha256']
    if not SAFE_FILE.match(download['name']) or not isinstance(build['id'], int) \
            or not re.fullmatch(r'[0-9a-f]{64}', sha256):
        raise FetchError(f'unexpected Paper build id, file name or hash: {build["id"]!r}, '
                         f'{download["name"]!r}')
    return {'build': build['id'], 'file': download['name'], 'sha256': sha256}


def fetch_paper_server(minecraft: str, pin: dict, cache: Path) -> tuple[Path, str]:
    """Put the pinned Paper server jar in the cache and return (path, how).

    The pin's sha256 is the authority: a cached file that matches needs no network at all, and
    a download is refused when PaperMC publishes a different file or hash for that build.
    """
    dest = cache / 'paper' / minecraft / pin['file']
    if file_matches(dest, 'sha256', pin['sha256']):
        return dest, 'cached'
    build = http_json(f'{PAPER_API}/versions/{urllib.parse.quote(minecraft)}/builds/{pin["build"]}')
    published = paper_pin_from_build(build)
    if published != {key: pin[key] for key in ('build', 'file', 'sha256')}:
        raise FetchError(f'Paper {minecraft} build {pin["build"]}: PaperMC publishes '
                         f'{published["file"]} sha256 {published["sha256"][:16]}…, the pin says '
                         f'{pin["file"]} {pin["sha256"][:16]}…')
    url = build['downloads'][PAPER_DOWNLOAD]['url']
    write_verified(dest, http_get(url, timeout=600), 'sha256', pin['sha256'], url)
    return dest, 'downloaded'
