#!/usr/bin/env python3
"""Real-stack test matrix: boot the stacks players actually run and check that LSS works in them.

  realstack.py list
  realstack.py doctor
  realstack.py provision <row>
  realstack.py run <row> --lss published:<version> | dir:<path>
  realstack.py replay <run directory>
  realstack.py pin <modrinth project> --minecraft <version> --loader <loader>
  realstack.py pin --paper <minecraft version>

A row (stacks.json) names a Minecraft version, a server platform, a client loader and the pinned
third-party mods of both sides. `run` stages a fresh offline server and a fresh production client
(no launcher, no account) on a private Xvfb display, lets the client join, runs the row's checks
and writes verdict.json. It exits 0 only when every check passed. See README.md.
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import signal
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import checks  # noqa: E402
import fetch  # noqa: E402
import launch  # noqa: E402
import procs  # noqa: E402
import settings  # noqa: E402
import stackfile  # noqa: E402
import xwdpng  # noqa: E402

RUN_SECONDS = 900            # hard bound of one run, lock wait excluded
TEARDOWN_RESERVE = 120       # part of the bound kept for stopping everything
LOCK_WAIT_SECONDS = 1800
SERVER_START_SECONDS = 300
CLIENT_JOIN_SECONDS = 300
OBSERVE_SECONDS = 60         # the client stays in the world at least this long
COLUMNS_SECONDS = 150        # how long after the join the columns counter may take to move
DIAG_INTERVAL = 10
CONSOLE_REPLY_SECONDS = 8
RELOAD_SECONDS = 30          # how long the server may take to answer a settings reload
SETTINGS_FILE_SECONDS = 30   # how long after "Done" the first start may take to write its settings
INSTALLER_SECONDS = 900
FAULTS = ('client-without-lss',)
# A scenario replaces the row's checks: it stages a situation a player meets and reads what the
# client and the server say about it.
#   notice           the chat line the client must speak (checks.NOTICE_TEXTS)
#   server_lss       False stages the server without the mod (and nothing asks it for a diag)
#   server_settings  (path, value) pairs set in the server's settings file once the first start
#                    has created it, then applied with a reload, all before the client joins
#   server_settings_empty  map settings that must have no entries in the generated file,
#                    because an entry there would take priority over what the scenario sets
#   client_settings  a settings file the client is staged with
#   diag_witness     (line, counter) in the server's diag that ends the wait when it shows
SCENARIOS = {
    'server-without-lss': {
        'checks': ['server_ready', 'client_joined', 'client_notice', 'client_clean', 'screenshot'],
        'notice': 'no_support',
        'server_lss': False,
    },
    'off-in-world': {
        'checks': ['server_started', 'server_settings', 'client_joined', 'handshake_off',
                   'no_columns', 'client_notice', 'client_clean', 'screenshot'],
        'notice': 'server_off_world',
        'server_settings': [(('lod', 'distance', 'by_dimension', 'minecraft:overworld'), '0')],
        'server_settings_empty': [('lod', 'distance', 'by_world')],
    },
    'client-receives-nothing': {
        'checks': ['server_started', 'client_joined', 'no_handshake', 'opted_out', 'client_clean',
                   'screenshot'],
        'client_settings': {'config_version': 1, 'lod': {'receive': False},
                            'far_players': {'sharing': {'enabled': False}}},
        'diag_witness': ('FarPlayers', 'opted_out'),
    },
}
NOTICE_SECONDS = 150         # how long after the join a scenario's notice or diag witness may take
SETTLE_SECONDS = 20          # how long the client stays after it, to catch a second notice
BRAND_PREFIXES = {'lss': 'lod-server-support-', 'vss': 'voxy-server-side-'}
SERVER_COMMANDS = {'lss': 'lsslod', 'vss': 'vsslod'}   # each build's own server command root
KEEP_AFTER_RUN = ('logs', 'crash-reports', 'config', 'console.log', 'console.in', 'stdout.log',
                  'server.properties', 'options.txt', 'eula.txt')


class RunAborted(RuntimeError):
    """The run could not continue; the checks are still evaluated on what was collected."""


def runs_root() -> Path:
    """Where run directories go: LSS_REALSTACK_RUNS, or ~/.local/state/lss-realstack/runs."""
    override = os.environ.get('LSS_REALSTACK_RUNS')
    return Path(override) if override else Path.home() / '.local' / 'state' / 'lss-realstack' / 'runs'


def say(message: str) -> None:
    """Progress line with a UTC time stamp."""
    print(f'[{datetime.now(timezone.utc):%H:%M:%S}] {message}', flush=True)


# --------------------------------------------------------------------------- Java

def java_major(home: Path) -> int | None:
    """The major version of the JDK at `home`, from its `release` file."""
    try:
        for line in (home / 'release').read_text().splitlines():
            if line.startswith('JAVA_VERSION='):
                return int(line.split('=', 1)[1].strip('"').split('.')[0])
    except (OSError, ValueError):
        return None
    return None


def java_candidates() -> list[Path]:
    """Places a JDK is commonly installed, most specific first."""
    found = []
    if os.environ.get('JAVA_HOME'):
        found.append(Path(os.environ['JAVA_HOME']))
    for parent in (Path.home() / '.local' / 'jdk', Path('/usr/lib/jvm'), Path.home() / '.sdkman'
                   / 'candidates' / 'java'):
        if parent.is_dir():
            found += sorted(entry for entry in parent.iterdir() if entry.is_dir())
    on_path = shutil.which('java')
    if on_path:
        found.append(Path(on_path).resolve().parent.parent)
    return found


def find_java(major: int) -> Path | None:
    """The `java` binary of a JDK with this major version: LSS_JAVA_HOME_<major> when set (and
    then only that), otherwise the first matching JDK among the usual places."""
    override = os.environ.get(f'LSS_JAVA_HOME_{major}')
    homes = [Path(override)] if override else java_candidates()
    for home in homes:
        binary = home / 'bin' / 'java'
        if binary.is_file() and java_major(home) == major:
            return binary
    return None


def require_java(major: int) -> Path:
    """Like find_java, but a missing JDK is an error that says how to fix it."""
    binary = find_java(major)
    if binary is None:
        raise fetch.FetchError(f'no Java {major} found; set LSS_JAVA_HOME_{major} to a JDK {major} home')
    return binary


# --------------------------------------------------------------------------- provisioning

def provision_fabric_side(row: dict, side: str, version_json: dict, cache: Path) -> dict:
    """Fabric loader libraries for one side, plus the game jar that side runs on."""
    block = row[side]
    profile = fetch.fabric_profile(row['minecraft'], block['loader_version'], side, cache)
    libraries = fetch.resolve_sidecar_hashes(fetch.fabric_libraries(profile), cache)
    if side == 'client':
        libraries = fetch.merge_libraries(libraries, fetch.vanilla_libraries(version_json))
    paths, counts = fetch.fetch_libraries(libraries, cache)
    say(f'  {side}: Fabric loader {block["loader_version"]}, {len(paths)} libraries {counts}')
    return {'classpath': [str(path) for path in paths],
            'game_jar': str(fetch.fetch_game_jar(version_json, side, cache))}


def neoforge_install(block: dict, side: str, java: Path, cache: Path) -> Path:
    """Run the official NeoForge installer once per version and side, into the cache. The
    installer downloads its own libraries from maven.neoforged.net and Mojang and checks them."""
    version = block['neoforge']['version']
    installer = fetch.neoforge_installer(version, block['neoforge']['installer_sha256'], cache)
    root = cache / 'neoforge' / version / side
    marker = root / '.realstack-installed'
    wanted = block['neoforge']['installer_sha256']
    if marker.is_file() and marker.read_text().strip() == wanted:
        return root
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    if side == 'client':
        (root / 'launcher_profiles.json').write_text('{"profiles": {}}\n')
    log = cache / 'neoforge' / version / f'install-{side}.log'
    tag = launch.rig_tag('provision-' + secrets.token_hex(4), 'installer')
    command = launch.bounded([str(java), tag, '-jar', str(installer), f'--install-{side}', str(root)],
                             INSTALLER_SECONDS)
    say(f'  {side}: running the NeoForge {version} installer (log: {log})')
    with open(log, 'wb') as output:
        result = subprocess.run(command, cwd=installer.parent, stdout=output,
                                stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                env=launch.private_environment(dict(os.environ)), check=False)
    produced = neoforge_profile_path(root, version) if side == 'client' \
        else root / 'libraries' / 'net' / 'neoforged' / 'neoforge' / version / 'unix_args.txt'
    if result.returncode != 0 or not produced.is_file():
        raise fetch.FetchError(f'the NeoForge installer failed (exit {result.returncode}); see {log}')
    marker.write_text(wanted + '\n')
    return root


def neoforge_profile_path(root: Path, version: str) -> Path:
    """The client profile the installer writes."""
    return root / 'versions' / f'neoforge-{version}' / f'neoforge-{version}.json'


def provision(row: dict, cache: Path) -> dict:
    """Download and verify everything a row needs. Returns the paths `run` launches from."""
    free = fetch.require_free_space()
    say(f'provisioning {row["id"]} into {cache} ({free / 1024 ** 3:.0f} GiB free)')
    version_json = fetch.mojang_version(row['minecraft'], cache)
    java = require_java(version_json['javaVersion']['majorVersion'])
    result = {'java': str(java), 'java_major': version_json['javaVersion']['majorVersion'],
              'asset_index': version_json['assetIndex']['id'], 'server': {}, 'client': {}}

    server = row['server']
    if server['platform'] == 'fabric':
        result['server'] = provision_fabric_side(row, 'server', version_json, cache)
    elif server['platform'] == 'neoforge':
        root = neoforge_install(server, 'server', java, cache)
        result['server'] = {'install_root': str(root)}
        say(f'  server: NeoForge {server["neoforge"]["version"]} installed at {root}')
    elif server['platform'] == 'paper':
        path, how = fetch.fetch_paper_server(row['minecraft'], server['paper'], cache)
        result['server'] = {'paper_jar': str(path),
                            'game_jar': str(fetch.fetch_game_jar(version_json, 'server', cache))}
        say(f'  server: Paper {row["minecraft"]} build {server["paper"]["build"]}: {how}')
    else:
        raise fetch.FetchError(f'server platform {server["platform"]} is not implemented yet')

    client = row['client']
    if client['loader'] == 'fabric':
        result['client'] = provision_fabric_side(row, 'client', version_json, cache)
    else:
        root = neoforge_install(client, 'client', java, cache)
        vanilla = fetch.vanilla_libraries(version_json)
        paths, counts = fetch.fetch_libraries(vanilla, cache)
        say(f'  client: NeoForge {client["neoforge"]["version"]} installed, '
            f'{len(paths)} vanilla libraries {counts}')
        result['client'] = {'install_root': str(root),
                            'vanilla': [[entry['name'], str(path)]
                                        for entry, path in zip(vanilla, paths)]}
    assets, counts = fetch.fetch_assets(version_json, cache)
    result['client']['assets'] = str(assets)
    say(f'  client: asset index {result["asset_index"]} {counts}')

    for side in ('server', 'client'):
        mods = []
        for pin in row[side]['mods']:
            path, how = fetch.fetch_pinned_mod(pin, cache)
            mods.append({'slug': pin['slug'], 'version_number': pin['version_number'],
                         'file': pin['file'], 'sha512': pin['sha512'], 'path': str(path)})
            say(f'  {side} mod {pin["slug"]} {pin["version_number"]}: {how}')
        result[side]['mods'] = mods
    return result


def branded_prefix(jar_prefix: str, brand: str) -> str:
    """A row's jar prefix under a brand: the Voxy Server Side build of the same jar is named
    voxy-server-side-<loader>."""
    lss = BRAND_PREFIXES['lss']
    if not jar_prefix.startswith(lss):
        raise stackfile.StackError(f'jar prefix {jar_prefix} does not start with {lss}')
    return BRAND_PREFIXES[brand] + jar_prefix[len(lss):]


def resolve_lss(document: dict, row: dict, selector: str, cache: Path, brand: str = 'lss') -> dict:
    """Resolve the `--lss` selector to one jar per side, hash-verified or hashed. The VSS brand
    exists only as locally built jars (`dir:`); its published files live in another project."""
    kind, value = stackfile.parse_lss_selector(selector)
    if brand != 'lss' and kind == 'published':
        raise stackfile.StackError('--brand vss needs --lss dir:<gate jars>')
    resolved = {}
    for side in ('server', 'client'):
        wanted = row[side]['lss']
        if kind == 'published':
            path, record = fetch.fetch_published_lss(document['lss']['modrinth_project'], value,
                                                     row['minecraft'], wanted['loader'], cache)
            resolved[side] = {'path': str(path), 'file': record['file'], 'sha512': record['sha512'],
                              'source': selector, 'version_id': record['version_id']}
        else:
            directory = Path(value).expanduser()
            if not directory.is_dir():
                raise stackfile.StackError(f'--lss dir: {directory} is not a directory')
            names = [entry.name for entry in directory.iterdir() if entry.is_file()]
            name = stackfile.choose_built_jar(names, branded_prefix(wanted['jar_prefix'], brand),
                                              row['minecraft'])
            path = directory / name
            resolved[side] = {'path': str(path), 'file': name, 'source': selector,
                              'sha512': fetch.file_digest(path, 'sha512')}
        say(f'  LSS for the {side}: {resolved[side]["file"]} ({resolved[side]["source"]})')
    return resolved


# --------------------------------------------------------------------------- one run

class Run:
    """One bounded attempt at a row: stages, starts, observes, stops and judges."""

    def __init__(self, document: dict, row: dict, selector: str, options: argparse.Namespace) -> None:
        self.document = document
        self.row = row
        self.selector = selector
        self.options = options
        self.cache = fetch.cache_root()
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        self.rig_id = f'rs{stamp}{secrets.token_hex(3)}'
        self.directory = runs_root() / f'{stamp}-{row["id"]}'
        self.server_dir = self.directory / 'server'
        self.client_dir = self.directory / 'client'
        self.evidence = self.directory / 'evidence'
        self.framebuffer = self.directory / 'xvfb'
        self.console_log = self.server_dir / 'console.log'
        self.client_log = self.client_dir / 'stdout.log'
        self.environment = launch.private_environment(dict(os.environ))
        self.server: subprocess.Popen | None = None
        self.client: subprocess.Popen | None = None
        self.deadline = 0.0
        self.scenario = SCENARIOS.get(options.scenario or '')
        self.facts: dict = {'rig_id': self.rig_id, 'player': launch.PLAYER, 'fault': options.fault,
                            'scenario': options.scenario, 'brand': options.brand.upper(),
                            'notice': self.scenario.get('notice') if self.scenario else None,
                            'own_paths': [str(self.cache), str(self.directory)], 'diag_polls': []}
        self.final_diag = ''

    # -- helpers

    def markers(self, *roles: str) -> list[str]:
        """Command-line markers of this run's processes (all roles when none are named)."""
        tags = {'server': f'lssrig.id={self.rig_id}-server',
                'client': f'lssrig.id={self.rig_id}-client',
                'xvfb': f'-fbdir {self.framebuffer}'}
        return [tags[role] for role in (roles or tags)]

    def remaining(self, wanted: float) -> float:
        """A monotonic deadline `wanted` seconds from now, capped by the run's own bound."""
        return min(time.monotonic() + wanted, self.deadline - TEARDOWN_RESERVE)

    def log_text(self, path: Path) -> str:
        """A log file's text so far."""
        try:
            return path.read_text(errors='replace')
        except OSError:
            return ''

    def wait_for_line(self, path: Path, pattern, process: subprocess.Popen, seconds: float,
                      what: str) -> None:
        """Wait (bounded) until a log record's message matches; abort if the process ends."""
        def seen() -> bool:
            """True once a record matches."""
            return any(pattern(entry.message) for entry in checks.parse_entries(self.log_text(path)))

        def done() -> bool:
            """True when there is nothing more to wait for."""
            return seen() or process.poll() is not None
        if not procs.wait_until(done, self.remaining(seconds), 1.0) or not seen():
            state = 'its process ended' if process.poll() is not None else f'{seconds:.0f} s passed'
            raise RunAborted(f'{what}: not seen ({state})')

    def console(self, command: str, settle: float = 1.5, expect: str | tuple[str, ...] = '',
                seconds: float = CONSOLE_REPLY_SECONDS) -> str:
        """Send one command to the server console and return the output it produced. With
        `expect`, wait (at most `seconds`) until that text, or one of those texts, has
        appeared, then let the rest of the reply arrive; otherwise wait `settle` seconds."""
        if self.server is None or self.server.poll() is not None or self.server.stdin is None:
            return ''
        before = self.console_log.stat().st_size if self.console_log.exists() else 0
        with open(self.server_dir / 'console.in', 'a') as record:
            record.write(f'{datetime.now(timezone.utc):%H:%M:%S} {command}\n')
        try:
            self.server.stdin.write((command + '\n').encode())
            self.server.stdin.flush()
        except OSError:
            return ''

        def reply() -> str:
            """What the console has printed since the command was sent."""
            with open(self.console_log, 'rb') as handle:
                handle.seek(before)
                return handle.read().decode('utf-8', 'replace')

        if expect:
            wanted = (expect,) if isinstance(expect, str) else expect
            procs.wait_until(lambda: any(text in reply() for text in wanted),
                             time.monotonic() + seconds, 0.2)
            settle = 0.5
        time.sleep(settle)
        return reply()

    # -- stages

    def stage(self, provisioned: dict, lss: dict) -> None:
        """Create the run directory with a fresh server directory and client game directory."""
        platform = self.row['server']['platform']
        folders = {'server': self.server_dir / launch.mods_folder(platform),
                   'client': self.client_dir / 'mods'}
        for directory in folders.values():
            directory.mkdir(parents=True)
        scenario = self.scenario or {}
        staged = []
        for side in ('server', 'client'):
            sources = [(Path(mod['path']), mod['sha512'], mod['slug'])
                       for mod in provisioned[side]['mods']]
            if side == 'client' and self.options.fault == 'client-without-lss':
                staged.append({'side': side, 'mod': 'lss', 'left_out_by_fault': self.options.fault})
            elif side == 'server' and not scenario.get('server_lss', True):
                staged.append({'side': side, 'mod': 'lss', 'left_out_by_scenario': self.options.scenario})
            else:
                sources.append((Path(lss[side]['path']), lss[side]['sha512'], 'lss'))
            for source, sha512, slug in sources:
                target = folders[side] / source.name
                shutil.copyfile(source, target)
                if fetch.file_digest(target, 'sha512') != sha512:
                    raise RunAborted(f'staged {target} does not match its hash')
                staged.append({'side': side, 'mod': slug, 'file': source.name, 'sha512': sha512})
        (self.evidence / 'staged.json').write_text(json.dumps(staged, indent=1) + '\n')
        self.facts['staged'] = staged
        (self.server_dir / 'eula.txt').write_text('eula=true\n')
        (self.client_dir / 'options.txt').write_text(launch.client_options())
        if platform == 'neoforge':
            libraries = Path(provisioned['server']['install_root']) / 'libraries'
            (self.server_dir / 'libraries').symlink_to(libraries, target_is_directory=True)
        if platform == 'paper':
            self.seed_paper_cache(provisioned)
        if scenario.get('client_settings'):
            relative = launch.client_settings_path(self.options.brand)
            text = settings.render(scenario['client_settings'])
            (self.client_dir / relative).parent.mkdir(parents=True, exist_ok=True)
            (self.client_dir / relative).write_text(text)
            (self.evidence / 'client-settings.yaml').write_text(text)
            self.facts['client_settings'] = {'file': str(relative), 'text': text}

    def seed_paper_cache(self, provisioned: dict) -> None:
        """Give the Paper jar the game jar it would otherwise download on its first start:
        this tool's own verified copy, when its hash is the one the Paper jar asks for.
        Without it the Paper jar downloads the file from Mojang and checks the hash itself."""
        with zipfile.ZipFile(provisioned['server']['paper_jar']) as archive:
            names = archive.namelist()
            context = (archive.read('META-INF/download-context').decode()
                       if 'META-INF/download-context' in names else '')
        game_jar = Path(provisioned['server']['game_jar'])
        seeded = []
        for wanted in launch.paperclip_downloads(context):
            if fetch.file_matches(game_jar, 'sha256', wanted['sha256']):
                (self.server_dir / 'cache').mkdir(exist_ok=True)
                shutil.copyfile(game_jar, self.server_dir / 'cache' / wanted['name'])
                seeded.append(wanted['name'])
        self.facts['paper_cache_seeded'] = seeded

    def start_display(self) -> int:
        """Start the private Xvfb display for this run."""
        display = self.options.display or launch.pick_display(launch.DISPLAY_CANDIDATES,
                                                              procs.displays_in_use())
        if display == 0 or display in procs.displays_in_use():
            raise RunAborted(f'display :{display} is not a free private display')
        self.framebuffer.mkdir(parents=True, exist_ok=True)
        command = launch.bounded(launch.xvfb_command(display, self.framebuffer), RUN_SECONDS)
        procs.spawn(command, self.directory, self.environment, self.directory / 'xvfb.log')
        if not procs.wait_until(lambda: procs.display_ready(display, self.environment),
                                self.remaining(20), 0.3):
            raise RunAborted(f'Xvfb :{display} did not come up')
        self.facts['display'] = display
        say(f'private display :{display} is up')
        return display

    def start_server(self, provisioned: dict) -> int:
        """Start the offline server and wait until it accepts players."""
        port = (launch.check_port(self.options.port, launch.RESERVED_PORTS, procs.port_in_use)
                if self.options.port else
                launch.pick_port(launch.PORT_CANDIDATES, launch.RESERVED_PORTS, procs.port_in_use))
        (self.server_dir / 'server.properties').write_text(
            launch.server_properties(port, self.row['minecraft']))
        java = Path(provisioned['java'])
        tag = launch.rig_tag(self.rig_id, 'server')
        block = self.row['server']
        if block['platform'] == 'fabric':
            command = launch.fabric_server_command(
                java, tag, [Path(item) for item in provisioned['server']['classpath']],
                Path(provisioned['server']['game_jar']), block.get('jvm_args', []))
        elif block['platform'] == 'paper':
            command = launch.paper_server_command(java, tag, Path(provisioned['server']['paper_jar']),
                                                  block.get('jvm_args', []))
        else:
            command = launch.neoforge_server_command(java, tag, block['neoforge']['version'],
                                                     block.get('jvm_args', []))
        self.facts['port'] = port
        self.facts['server_command'] = command
        self.server = procs.spawn(launch.bounded(command, RUN_SECONDS), self.server_dir,
                                  self.environment, self.console_log, with_console=True)
        say(f'server starting on 127.0.0.1:{port}')
        started = time.monotonic()
        self.wait_for_line(self.console_log, checks.SERVER_READY.match, self.server,
                           SERVER_START_SECONDS, 'server "Done" line')
        self.facts['server_start_seconds'] = round(time.monotonic() - started, 1)
        say(f'server ready after {self.facts["server_start_seconds"]} s')
        return port

    def apply_server_settings(self) -> None:
        """A scenario's server settings. The settings file exists only once the first start has
        created it, so this runs after the server is up and before the client joins: change
        the lines in the file, tell the server to read it (`lsslod reload`), and go on only
        when the server has answered that it applied a setting. Anything else ends the run."""
        changes = (self.scenario or {}).get('server_settings')
        if not changes:
            return
        self.wait_for_line(self.console_log, checks.SERVICE_START.match, self.server,
                           SETTINGS_FILE_SECONDS, 'server settings: the mod\'s service start')
        relative = launch.server_settings_path(self.row['server']['platform'], self.options.brand)
        path = self.server_dir / relative
        if not procs.wait_until(path.is_file, self.remaining(SETTINGS_FILE_SECONDS), 0.5):
            raise RunAborted(f'server settings: the first start did not create {relative}')
        generated = path.read_text()
        (self.evidence / 'server-settings-generated.yaml').write_text(generated)
        record = []
        try:
            for key in self.scenario.get('server_settings_empty', []):
                if not settings.is_empty_or_absent(generated, key):
                    raise settings.SettingsError(
                        f'{settings.dotted(key)} has entries in the generated file, and they '
                        'would take priority over what this scenario sets')
            edited = generated
            for key, value in changes:
                before = settings.line_of(edited, key)
                edited = settings.set_value(edited, key, value)
                record.append({'path': list(key), 'value': value, 'line_before': before,
                               'line_after': settings.line_of(edited, key)})
        except settings.SettingsError as error:
            raise RunAborted(f'server settings: {error}') from error
        path.write_text(edited)
        (self.evidence / 'server-settings.yaml').write_text(edited)
        self.facts['server_settings'] = {'file': str(relative), 'changes': record}
        reply = self.console(f'{SERVER_COMMANDS[self.options.brand]} reload', seconds=RELOAD_SECONDS,
                             expect=('active setting(s) applied', 'no active changes',
                                     'Reload failed', 'adoption pending'))
        answered = [entry.message for entry in checks.parse_entries(reply)]
        self.facts['server_settings']['reload_reply'] = answered
        result = checks.check_server_settings(reply, edited, record)
        if not result.passed:
            raise RunAborted(f'server settings: {result.message}; the server answered '
                             f'{answered[-4:] or "nothing"}')
        say('server settings: ' + ', '.join(f'{settings.dotted(tuple(change["path"]))} = '
                                            f'{change["value"]}' for change in record)
            + ' set and reloaded')

    def start_client(self, provisioned: dict, display: int, port: int) -> None:
        """Start the production client on the private display and wait until it has joined."""
        java = Path(provisioned['java'])
        tag = launch.rig_tag(self.rig_id, 'client')
        block = self.row['client']
        version_json = fetch.mojang_version(self.row['minecraft'], self.cache)
        assets = Path(provisioned['client']['assets'])
        address = f'127.0.0.1:{port}'
        if block['loader'] == 'fabric':
            game = launch.game_arguments(version_json, self.row['minecraft'], self.client_dir,
                                         assets, address)
            command = launch.fabric_client_command(
                java, tag, [Path(item) for item in provisioned['client']['classpath']],
                Path(provisioned['client']['game_jar']), block.get('jvm_args', []), game)
        else:
            root = Path(provisioned['client']['install_root'])
            version = block['neoforge']['version']
            profile = json.loads(neoforge_profile_path(root, version).read_text())
            vanilla = [(name, Path(path)) for name, path in provisioned['client']['vanilla']]
            classpath = launch.neoforge_client_classpath(profile, root / 'libraries', vanilla)
            game = launch.game_arguments(version_json, profile['id'], self.client_dir, assets,
                                         address)
            command = launch.neoforge_client_command(java, tag, profile, root / 'libraries',
                                                     classpath, block.get('jvm_args', []), game)
        self.facts['client_command'] = command
        self.client = procs.spawn(launch.bounded(command, RUN_SECONDS), self.client_dir,
                                  launch.client_environment(dict(os.environ), display),
                                  self.client_log)
        say('client starting')
        started = time.monotonic()
        joined = f'{launch.PLAYER} joined the game'
        self.wait_for_line(self.console_log, lambda message: message == joined, self.client,
                           CLIENT_JOIN_SECONDS, f'"{joined}" in the server log')
        self.facts['client_join_seconds'] = round(time.monotonic() - started, 1)
        say(f'client joined after {self.facts["client_join_seconds"]} s')

    def poll_diag(self, joined: float) -> dict:
        """Ask the server for its diagnostics (`lsslod diag`, or `vsslod diag` on the Voxy
        Server Side build) and record what came back. The last answer is the final diag."""
        reply = self.console(f'{SERVER_COMMANDS[self.options.brand]} diag', expect='Bandwidth:')
        diag = checks.parse_diag(reply)
        self.facts['diag_polls'].append({
            'seconds_after_join': round(time.monotonic() - joined), 'present': diag['present'],
            'sent': checks.counter(diag['players'].get(launch.PLAYER), 'sent') or 0,
            'total': checks.counter(diag['fields'].get('Throughput'), 'sent')})
        if diag['present']:
            self.final_diag = reply
        return diag

    def end_observation(self, display: int) -> None:
        """Record how the observation window ended and take the screenshot."""
        (self.evidence / 'server-diag-final.txt').write_text(self.final_diag)
        self.facts['client_alive_at_end'] = self.client is not None and self.client.poll() is None
        self.facts['observation_end_offset'] = self.console_log.stat().st_size
        self.take_screenshot(display)

    def observe_scenario(self, display: int) -> None:
        """A scenario's window: keep the client in the world until the scenario's witness has
        shown and has had SETTLE_SECONDS to repeat itself, or the deadline passes. The witness
        is the client's chat notice, or a counter in the server's diag. A server that runs
        the mod is asked for its diag on every round, the client stays the usual minimum, and
        the last diag is taken at the very end."""
        scenario = self.scenario
        with_diag = scenario.get('server_lss', True)
        witness = scenario.get('diag_witness')
        joined = time.monotonic()
        stay_until = self.remaining(self.options.observe) if with_diag else joined
        give_up = self.remaining(NOTICE_SECONDS)
        seen_at = None
        while True:
            elapsed = round(time.monotonic() - joined)
            diag = self.poll_diag(joined) if with_diag else None
            spoken = len(checks.all_matches(checks.parse_entries(self.log_text(self.client_log)),
                                            checks.NOTICE))
            if witness:
                count = checks.counter(diag['fields'].get(witness[0]), witness[1])
                shown = bool(count)
                say(f'  {elapsed:>3} s after join: diag {witness[0]} {witness[1]}={count}, '
                    f'{spoken} notice(s) in the client log')
            else:
                shown = spoken > 0
                say(f'  {elapsed:>3} s after join: {spoken} notice(s) in the client log'
                    + (f', diag {"ok" if diag["present"] else "MISSING"}' if diag else ''))
            now = time.monotonic()
            if shown and seen_at is None:
                seen_at = now
                self.facts['witness_seconds_after_join' if witness
                           else 'notice_seconds_after_join'] = elapsed
            client_gone = self.client is not None and self.client.poll() is not None
            settled = seen_at is not None and now - seen_at >= SETTLE_SECONDS and now >= stay_until
            if client_gone or now >= give_up or settled:
                break
            time.sleep(DIAG_INTERVAL)
        self.end_observation(display)

    def observe(self, display: int) -> None:
        """Keep the client in the world for the observation window, polling the server's
        diagnostics command (`lsslod diag`, or `vsslod diag` on the Voxy Server Side build)."""
        if self.scenario:
            self.observe_scenario(display)
            return
        joined = time.monotonic()
        stay_until = self.remaining(self.options.observe)
        give_up = self.remaining(max(self.options.observe, self.options.columns_deadline))
        while True:
            diag = self.poll_diag(joined)
            poll = self.facts['diag_polls'][-1]
            say(f'  {poll["seconds_after_join"]:>3} s after join: diag '
                f'{"ok" if diag["present"] else "MISSING"}, sent to {launch.PLAYER} = {poll["sent"]}')
            now = time.monotonic()
            client_gone = self.client is not None and self.client.poll() is not None
            if client_gone or now >= give_up or (now >= stay_until and poll['sent'] > 0):
                break
            time.sleep(DIAG_INTERVAL)
        self.end_observation(display)

    def take_screenshot(self, display: int) -> None:
        """Save the private display's framebuffer as evidence."""
        try:
            shot = xwdpng.capture(self.framebuffer / 'Xvfb_screen0', self.evidence / 'client.png')
        except (OSError, xwdpng.XwdError) as error:
            self.facts['screenshot'] = {'error': str(error)}
            return
        shot['windows'] = procs.window_titles(display, self.environment)
        shot['path'] = str(Path(shot['path']).relative_to(self.directory))
        self.facts['screenshot'] = shot
        say(f'screenshot saved ({shot["width"]}x{shot["height"]}, windows: {shot["windows"]})')

    def stop_everything(self) -> None:
        """Stop the client, then the server (through its console first), then the display.
        Only this run's own processes are ever signalled."""
        report: dict = {}
        if self.server is not None and self.server.poll() is None:
            self.console(f'kick {launch.PLAYER}', settle=3.0)
        report['client'] = procs.stop_owned(self.markers('client'), grace=20)
        if self.server is not None and self.server.poll() is None:
            self.console('stop', settle=0.5)
            procs.wait_until(lambda: self.server.poll() is not None, time.monotonic() + 60, 1.0)
        report['server'] = procs.stop_owned(self.markers('server'), grace=15)
        report['display'] = procs.stop_owned(self.markers('xvfb'), grace=5)
        left = procs.owned(procs.list_processes(), self.markers())
        report['left_running'] = [f'{process.pid} {process.name}' for process in left]
        self.facts['stop_report'] = report
        (self.evidence / 'processes-after.txt').write_text(
            '\n'.join(report['left_running']) + ('\n' if left else 'nothing left running\n'))

    def collect(self) -> dict:
        """Copy the logs into the evidence folder and return the material the checks read."""
        copies = {'server-console.log': self.console_log,
                  'server-latest.log': self.server_dir / 'logs' / 'latest.log',
                  'client-stdout.log': self.client_log,
                  'client-latest.log': self.client_dir / 'logs' / 'latest.log'}
        if not (self.evidence / 'server-settings.yaml').exists():
            copies['server-settings.yaml'] = self.server_dir / launch.server_settings_path(
                self.row['server']['platform'], self.options.brand)
        for name, source in copies.items():
            if source.is_file():
                shutil.copyfile(source, self.evidence / name)
        crash_files = sorted(str(path.relative_to(self.directory))
                             for pattern in ('crash-reports/*', 'hs_err_pid*.log')
                             for path in self.client_dir.glob(pattern))
        for path in self.client_dir.glob('crash-reports/*'):
            shutil.copyfile(path, self.evidence / ('client-crash-' + path.name))
        self.facts['crash_files'] = crash_files
        (self.directory / 'facts.json').write_text(json.dumps(self.facts, indent=1) + '\n')
        return load_material(self.directory, self.document)

    def prune(self) -> None:
        """Drop the bulky parts of the staged directories (world, copied mods, caches); the
        logs, settings and crash reports stay, and the evidence folder is never touched."""
        if self.options.keep:
            return
        for directory in (self.server_dir, self.client_dir):
            for entry in directory.iterdir() if directory.is_dir() else []:
                if entry.name in KEEP_AFTER_RUN:
                    continue
                if entry.is_symlink() or entry.is_file():
                    entry.unlink()
                else:
                    shutil.rmtree(entry, ignore_errors=True)
        shutil.rmtree(self.framebuffer, ignore_errors=True)

    def execute(self) -> dict:
        """The whole run. Always stops what it started and always writes verdict.json."""
        say(f'row {self.row["id"]}, {self.options.brand.upper()} {self.selector}'
            + (f', FAULT {self.options.fault}' if self.options.fault else '')
            + (f', SCENARIO {self.options.scenario}' if self.options.scenario else ''))
        provisioned = provision(self.row, self.cache)
        lss = resolve_lss(self.document, self.row, self.selector, self.cache, self.options.brand)
        fetch.require_free_space()
        if self.directory.exists():
            raise launch.LaunchError(f'{self.directory} exists; every attempt keeps its own directory')
        self.evidence.mkdir(parents=True)
        say(f'run directory {self.directory}')
        self.facts.update({'row': self.row['id'], 'lss_selector': self.selector, 'lss': lss,
                           'java': provisioned['java'],
                           'started': datetime.now(timezone.utc).isoformat(timespec='seconds')})
        aborted = None
        lock = procs.HarnessLock()
        try:
            say('waiting for the shared harness lock')
            self.facts['lock_wait_seconds'] = round(lock.acquire(self.options.lock_wait), 1)
            say(f'lock held after {self.facts["lock_wait_seconds"]} s')
            began = time.monotonic()
            self.deadline = began + min(self.options.max_seconds, RUN_SECONDS)
            try:
                self.stage(provisioned, lss)
                display = self.start_display()
                port = self.start_server(provisioned)
                self.apply_server_settings()
                self.start_client(provisioned, display, port)
                self.observe(display)
            except (Exception, KeyboardInterrupt) as error:   # judge what was collected anyway
                aborted = f'{type(error).__name__}: {error}'
                say(f'ABORTED: {aborted}')
                self.facts.setdefault('observation_end_offset',
                                      self.console_log.stat().st_size
                                      if self.console_log.exists() else 0)
            finally:
                self.stop_everything()
                self.facts['run_seconds'] = round(time.monotonic() - began, 1)
        except TimeoutError as error:
            aborted = str(error)
            say(f'ABORTED: {aborted}')
        finally:
            lock.release()
        material = self.collect()
        judged = judged_row(self.row, self.facts.get('scenario'))
        results = checks.run_checks(judged['checks'], material)
        context = verdict_context(self.row, self.facts)
        context['aborted'] = aborted
        verdict = checks.assemble_verdict(judged, results, context)
        (self.directory / 'verdict.json').write_text(json.dumps(verdict, indent=1) + '\n')
        self.prune()
        return verdict


def judged_row(row: dict, scenario: str | None) -> dict:
    """The row as it is judged: a scenario brings its own checks."""
    if not scenario:
        return row
    return {**row, 'checks': SCENARIOS[scenario]['checks']}


def verdict_context(row: dict, facts: dict) -> dict:
    """The identity, timing and pins recorded beside the checks in verdict.json."""
    pins = {side: [f'{mod["slug"]} {mod["version_number"]}' for mod in row[side]['mods']]
            for side in ('server', 'client')}
    keys = ('rig_id', 'started', 'run_seconds', 'lock_wait_seconds', 'server_start_seconds',
            'client_join_seconds', 'port', 'display', 'java', 'fault', 'scenario', 'brand',
            'notice_seconds_after_join', 'witness_seconds_after_join', 'server_settings',
            'client_settings', 'lss_selector', 'lss', 'diag_polls', 'stop_report')
    context = {key: facts.get(key) for key in keys}
    context.update({'minecraft': row['minecraft'], 'pins': pins,
                    'server_platform': loader_label(row['server'], 'platform'),
                    'client_loader': loader_label(row['client'], 'loader')})
    return context


def loader_label(block: dict, kind_key: str) -> str:
    """A side's platform with its pinned loader build, for reports."""
    kind = block[kind_key]
    if kind == 'fabric':
        return f'fabric (loader {block["loader_version"]})'
    if kind == 'neoforge':
        return f'neoforge {block["neoforge"]["version"]}'
    if kind == 'paper':
        return f'paper (build {block["paper"]["build"]})'
    return kind


def load_material(directory: Path, document: dict) -> dict:
    """Read what the checks need from a run directory (used by `run` and by `replay`)."""
    evidence = directory / 'evidence'
    facts = json.loads((directory / 'facts.json').read_text())

    def text(name: str) -> str:
        """An evidence file's text, or nothing when it was never written."""
        path = evidence / name
        return path.read_text(errors='replace') if path.is_file() else ''

    server_log = text('server-console.log')
    console = evidence / 'server-console.log'
    offset = facts.get('observation_end_offset') or 0
    observed = console.read_bytes()[:offset].decode('utf-8', 'replace') if console.is_file() else ''
    screenshot = facts.get('screenshot')
    if screenshot and 'path' in screenshot and not (directory / screenshot['path']).is_file():
        screenshot = None
    return {'server_log': server_log, 'observed_server_log': observed,
            'client_log': text('client-stdout.log'), 'diag': text('server-diag-final.txt'),
            'player': facts.get('player', launch.PLAYER), 'protocol': document['lss']['protocol'],
            'crash_files': facts.get('crash_files', []),
            'client_alive_at_end': facts.get('client_alive_at_end', False),
            'screenshot': screenshot, 'own_paths': facts.get('own_paths', []),
            'notice': facts.get('notice') or 'no_support', 'brand': facts.get('brand') or 'LSS',
            'diag_polls': facts.get('diag_polls', []),
            'server_settings_text': text('server-settings.yaml'),
            'server_settings_changes': (facts.get('server_settings') or {}).get('changes', [])}


def print_verdict(verdict: dict, where: Path) -> None:
    """A short human summary of a verdict."""
    for check in verdict['checks']:
        print(f'  {"PASS" if check["passed"] else "FAIL"}  {check["name"]}: {check["message"]}')
    for name in verdict['checks_not_run']:
        print(f'  FAIL  {name}: not run')
    if verdict.get('aborted'):
        print(f'  aborted: {verdict["aborted"]}')
    print(f'{"PASSED" if verdict["passed"] else "FAILED"}: {verdict["row"]}  ({where})')


# --------------------------------------------------------------------------- subcommands

def command_list(options: argparse.Namespace) -> int:
    """Print the matrix."""
    document = stackfile.load(options.stacks)
    for row in document['rows']:
        server = row['server']
        client = row['client']
        print(f'{row["id"]}  [{row["status"]}]  Minecraft {row["minecraft"]}')
        print(f'    server {server["platform"]}: '
              + (', '.join(mod['slug'] for mod in server['mods']) or 'no third-party mods'))
        print(f'    client {client["loader"]}: '
              + (', '.join(mod['slug'] for mod in client['mods']) or 'no third-party mods'))
        print(f'    checks: {", ".join(row["checks"])}')
        if row['status'] == 'blocked':
            print(f'    blocked: {row["blocked_reason"]}')
    return 0


def command_doctor(options: argparse.Namespace) -> int:
    """Report what this machine has and lacks for running rows."""
    problems = 0

    def report(ok: bool, label: str, detail: str) -> None:
        """Print one line and count a missing requirement."""
        nonlocal problems
        problems += 0 if ok else 1
        print(f'  {"ok     " if ok else "MISSING"} {label}: {detail}')

    for major in (21, 25):
        binary = find_java(major)
        report(binary is not None, f'JDK {major}',
               str(binary) if binary else f'set LSS_JAVA_HOME_{major}')
    for tool in ('Xvfb', 'xdotool', 'timeout'):
        path = shutil.which(tool)
        report(path is not None, tool, path or 'not on PATH')
    free = fetch.free_bytes(Path.home())
    report(free >= fetch.MIN_FREE_BYTES, 'free disk (home)', f'{free / 1024 ** 3:.0f} GiB '
           f'(at least {fetch.MIN_FREE_BYTES / 1024 ** 3:.0f} GiB needed)')
    free_lock = procs.lock_is_free()
    print(f'  {"ok     " if free_lock else "busy   "} harness lock {procs.lock_path()}: '
          + ('free' if free_lock else 'held by another harness; a run waits for it'))
    print(f'  info    cache: {fetch.cache_root()}')
    print(f'  info    runs:  {runs_root()}')
    try:
        document = stackfile.load(options.stacks)
        print(f'  ok      matrix {options.stacks}: {len(document["rows"])} rows')
    except stackfile.StackError as error:
        problems += 1
        print(f'  MISSING matrix: {error}')
    return 1 if problems else 0


def command_provision(options: argparse.Namespace) -> int:
    """Download and verify everything a row needs."""
    document = stackfile.load(options.stacks)
    row = stackfile.find_row(document, options.row)
    provision(row, fetch.cache_root())
    say('provisioned')
    return 0


def command_run(options: argparse.Namespace) -> int:
    """Run one row and judge it."""
    document = stackfile.load(options.stacks)
    row = stackfile.find_row(document, options.row)
    if row['status'] == 'blocked' and not options.even_if_blocked:
        print(f'row {row["id"]} is blocked: {row["blocked_reason"]}', file=sys.stderr)
        return 2
    stackfile.parse_lss_selector(options.lss)
    if options.fault and options.scenario:
        print('--fault and --scenario cannot be combined', file=sys.stderr)
        return 2
    run = Run(document, row, options.lss, options)
    verdict = run.execute()
    print_verdict(verdict, run.directory)
    return 0 if verdict['passed'] else 1


def command_replay(options: argparse.Namespace) -> int:
    """Judge a saved run again from its evidence, without starting anything."""
    document = stackfile.load(options.stacks)
    directory = Path(options.directory).expanduser()
    facts = json.loads((directory / 'facts.json').read_text())
    row = stackfile.find_row(document, facts['row'])
    material = load_material(directory, document)
    judged = judged_row(row, facts.get('scenario'))
    results = checks.run_checks(judged['checks'], material)
    context = verdict_context(row, facts)
    context['aborted'] = None
    context['replayed_from'] = str(directory)
    verdict = checks.assemble_verdict(judged, results, context)
    (directory / 'verdict.replay.json').write_text(json.dumps(verdict, indent=1) + '\n')
    print_verdict(verdict, directory)
    return 0 if verdict['passed'] else 1


def command_pin(options: argparse.Namespace) -> int:
    """Print the stacks.json entry for a Modrinth version (the newest one unless named), the
    sha256 of a NeoForge installer, or a Paper build (the newest stable one unless named).
    Reads only public indexes."""
    if options.paper:
        chosen = fetch.choose_paper_build(fetch.paper_builds(options.paper), options.build)
        print(json.dumps(fetch.paper_pin_from_build(chosen), indent=1))
        print(f'# Paper {options.paper} build {chosen["id"]}: channel {chosen.get("channel")}, '
              f'published {chosen.get("time")}', file=sys.stderr)
        return 0
    if options.neoforge_installer:
        print(json.dumps({'version': options.neoforge_installer, 'installer_sha256':
                          fetch.neoforge_published_sha256(options.neoforge_installer)}, indent=1))
        return 0
    if not (options.project and options.minecraft and options.loader):
        print('pin needs a project with --minecraft and --loader, --neoforge-installer or --paper',
              file=sys.stderr)
        return 2
    versions = fetch.modrinth_versions(options.project, options.minecraft, options.loader)
    if options.version:
        versions = [entry for entry in versions
                    if options.version in (entry['version_number'], entry['id'])]
    if not versions:
        print('no matching published version', file=sys.stderr)
        return 1
    chosen = versions[0]
    print(json.dumps(fetch.pin_from_version(options.project, chosen), indent=1))
    for dependency in chosen.get('dependencies', []):
        print(f'# {dependency["dependency_type"]} dependency: project '
              f'{dependency.get("project_id")} version {dependency.get("version_id")}',
              file=sys.stderr)
    return 0


def build_parser() -> argparse.ArgumentParser:
    """The command line."""
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--stacks', type=Path, default=stackfile.DEFAULT_PATH,
                        help='the matrix file (default: stacks.json beside this tool)')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('list', help='print the matrix').set_defaults(handler=command_list)
    commands.add_parser('doctor', help='report what this machine is missing').set_defaults(
        handler=command_doctor)
    provision_parser = commands.add_parser('provision', help='download and verify a row\'s files')
    provision_parser.add_argument('row')
    provision_parser.set_defaults(handler=command_provision)
    run_parser = commands.add_parser('run', help='run one row and write verdict.json')
    run_parser.add_argument('row')
    run_parser.add_argument('--lss', required=True, help='published:<version> or dir:<path>')
    run_parser.add_argument('--fault', choices=FAULTS,
                            help='inject a known fault to prove the checks can fail')
    run_parser.add_argument('--scenario', choices=sorted(SCENARIOS),
                            help='stage a situation a player meets and judge what the client says')
    run_parser.add_argument('--brand', choices=sorted(BRAND_PREFIXES), default='lss',
                            help='which build of the jars under test (vss needs --lss dir:)')
    run_parser.add_argument('--port', type=int, help='server port (default: first free candidate)')
    run_parser.add_argument('--display', type=int, help='private X display number (never 0)')
    run_parser.add_argument('--observe', type=int, default=OBSERVE_SECONDS,
                            help='seconds the client stays in the world at least')
    run_parser.add_argument('--columns-deadline', type=int, default=COLUMNS_SECONDS,
                            help='seconds after the join the columns counter may take to move')
    run_parser.add_argument('--lock-wait', type=int, default=LOCK_WAIT_SECONDS,
                            help='seconds to wait for the shared harness lock')
    run_parser.add_argument('--max-seconds', type=int, default=RUN_SECONDS,
                            help=f'bound of the run (never more than {RUN_SECONDS})')
    run_parser.add_argument('--keep', action='store_true',
                            help='keep the world and the staged mods in the run directory')
    run_parser.add_argument('--even-if-blocked', action='store_true',
                            help='try a row that is marked blocked')
    run_parser.set_defaults(handler=command_run)
    replay_parser = commands.add_parser('replay', help='judge a saved run again from its evidence')
    replay_parser.add_argument('directory')
    replay_parser.set_defaults(handler=command_replay)
    pin_parser = commands.add_parser('pin', help='print a stacks.json pin for a published file')
    pin_parser.add_argument('project', nargs='?')
    pin_parser.add_argument('--minecraft')
    pin_parser.add_argument('--loader')
    pin_parser.add_argument('--version', help='version number or id (default: newest)')
    pin_parser.add_argument('--neoforge-installer', metavar='VERSION')
    pin_parser.add_argument('--paper', metavar='MINECRAFT', help='pin a Paper server build')
    pin_parser.add_argument('--build', type=int, help='Paper build number (default: newest stable)')
    pin_parser.set_defaults(handler=command_pin)
    return parser


def interrupt(signal_number, _frame) -> None:
    """Turn a termination signal into an exception so that cleanup runs."""
    raise KeyboardInterrupt(f'signal {signal_number}')


def main(arguments: list[str] | None = None) -> int:
    """Entry point."""
    options = build_parser().parse_args(arguments)
    for number in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(number, interrupt)
    try:
        return options.handler(options)
    except (stackfile.StackError, fetch.FetchError, launch.LaunchError) as error:
        print(f'error: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
