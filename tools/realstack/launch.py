"""How a row is launched: ports, displays, staged settings and command lines. Everything here is
pure, so it can be tested without starting a process.

Clients run in production form with no launcher and no account: the game's own main class (through
the mod loader) on the official jars, an offline player name and `--accessToken 0`, against a
local server in offline mode.
"""
from __future__ import annotations

import uuid
from pathlib import Path

PLAYER = 'RealStack'
SEED = 210021
WINDOW = (1280, 720)
# Ports other local rigs and servers on a maintainer's machine use; a run never takes one of them.
RESERVED_PORTS = frozenset([*range(25565, 25571), 25591, 25592, 25611, 25641, 25642,
                            *range(25701, 25707)])
PORT_CANDIDATES = range(25900, 25960)
DISPLAY_CANDIDATES = range(160, 200)
SERVER_MEMORY = ['-Xms1G', '-Xmx3G']
CLIENT_MEMORY = ['-Xms1G', '-Xmx3G']
# Never the desktop session: X11 only, software rendering, no audio device.
CLIENT_ENVIRONMENT = {
    'XDG_SESSION_TYPE': 'x11', 'SDL_VIDEO_DRIVER': 'x11', 'SDL_VIDEODRIVER': 'x11',
    'QT_QPA_PLATFORM': 'xcb', 'ALSOFT_DRIVERS': 'null', 'SDL_AUDIODRIVER': 'dummy',
    'SDL_AUDIO_DRIVER': 'dummy', 'SDL_VIDEO_FORCE_EGL': '1', 'SDL_VIDEO_X11_FORCE_EGL': '1',
    'MESA_GL_VERSION_OVERRIDE': '4.6', 'MESA_GLSL_VERSION_OVERRIDE': '460',
    'LIBGL_ALWAYS_SOFTWARE': '1',
}
DESKTOP_VARIABLES = ('WAYLAND_DISPLAY', 'XAUTHORITY', 'DISPLAY')
CLIENT_OPTIONS = {
    'onboardAccessibility': 'false', 'guiScale': '2', 'skipMultiplayerWarning': 'true',
    'joinedFirstServer': 'true', 'tutorialStep': 'none', 'narrator': '0',
    'pauseOnLostFocus': 'false', 'fullscreen': 'false', 'maxFps': '30', 'renderDistance': '4',
    'simulationDistance': '5', 'soundCategory_master': '0.0', 'soundCategory_music': '0.0',
    'enableVsync': 'false', 'menuBackgroundBlurriness': '0', 'panoramaScrollSpeed': '0.0',
}


class LaunchError(RuntimeError):
    """A run cannot be staged or started as asked."""


def pick_port(candidates, reserved, in_use) -> int:
    """The first candidate port that is neither reserved nor in use. `in_use` is a predicate."""
    for port in candidates:
        if port not in reserved and not in_use(port):
            return port
    raise LaunchError('no free port among the candidates')


def check_port(port: int, reserved, in_use) -> int:
    """Accept an explicitly requested port only when it is neither reserved nor in use."""
    if port in reserved:
        raise LaunchError(f'port {port} is reserved for other local servers and rigs')
    if in_use(port):
        raise LaunchError(f'port {port} is in use')
    return port


def pick_display(candidates, taken) -> int:
    """The first candidate X display number that does not exist yet. Display 0 is never used."""
    for number in candidates:
        if number != 0 and number not in taken:
            return number
    raise LaunchError('no free private display number among the candidates')


def private_environment(base: dict) -> dict:
    """A copy of the environment with every pointer to the desktop session removed."""
    return {key: value for key, value in base.items() if key not in DESKTOP_VARIABLES}


def client_environment(base: dict, display: int) -> dict:
    """The environment of a client: the private display and software rendering only."""
    if display == 0:
        raise LaunchError('refusing display :0')
    environment = private_environment(base)
    environment.update(CLIENT_ENVIRONMENT)
    environment['DISPLAY'] = f':{display}'
    return environment


def server_properties(port: int, minecraft: str) -> str:
    """server.properties for a disposable offline server bound to the loopback address."""
    values = {
        'online-mode': 'false', 'white-list': 'false', 'enforce-secure-profile': 'false',
        'server-ip': '127.0.0.1', 'server-port': str(port), 'level-seed': str(SEED),
        'level-name': 'world', 'motd': f'lss-realstack {minecraft}', 'view-distance': '5',
        'simulation-distance': '5', 'spawn-protection': '0', 'max-players': '4',
        'enable-rcon': 'false', 'enable-query': 'false', 'difficulty': 'peaceful',
        'gamemode': 'creative', 'allow-flight': 'true', 'sync-chunk-writes': 'false',
        'pause-when-empty-seconds': '-1',
    }
    return ''.join(f'{key}={value}\n' for key, value in values.items())


def client_options() -> str:
    """options.txt for an unattended client: no tutorial, no narrator, small render distance."""
    return ''.join(f'{key}:{value}\n' for key, value in CLIENT_OPTIONS.items())


def offline_uuid(name: str) -> str:
    """A stable UUID for an offline player name."""
    return str(uuid.uuid3(uuid.NAMESPACE_DNS, 'lss-realstack-' + name))


def rig_tag(rig_id: str, role: str) -> str:
    """The JVM property that marks a process as belonging to one run."""
    return f'-Dlssrig.id={rig_id}-{role}'


def xvfb_command(display: int, framebuffer_dir: Path) -> list[str]:
    """A private Xvfb: unix socket only, its framebuffer mirrored into a file for screenshots."""
    if display == 0:
        raise LaunchError('refusing display :0')
    return ['Xvfb', f':{display}', '-screen', '0', f'{WINDOW[0]}x{WINDOW[1]}x24',
            '-nolisten', 'tcp', '-fbdir', str(framebuffer_dir)]


def bounded(command: list[str], seconds: int) -> list[str]:
    """Wrap a command so it cannot outlive its deadline even if the runner dies."""
    return ['timeout', '-k', '15', str(int(seconds)), *command]


def game_arguments(version_json: dict, version_name: str, game_dir: Path, assets_dir: Path,
                   address: str) -> list[str]:
    """Game arguments for an offline client that joins `address` as soon as it has started."""
    arguments = ['--username', PLAYER, '--version', version_name, '--gameDir', str(game_dir),
                 '--assetsDir', str(assets_dir), '--assetIndex', version_json['assetIndex']['id'],
                 '--uuid', offline_uuid(PLAYER), '--accessToken', '0', '--versionType', 'release',
                 '--width', str(WINDOW[0]), '--height', str(WINDOW[1]),
                 '--quickPlayMultiplayer', address]
    declared = [item for item in version_json.get('arguments', {}).get('game', [])
                if isinstance(item, str)]
    if '--userType' in declared:
        arguments += ['--userType', 'legacy']
    return arguments


def fabric_client_command(java: Path, tag: str, classpath: list[Path], client_jar: Path,
                          jvm_args: list[str], game_args: list[str]) -> list[str]:
    """A production Fabric client: Knot on the official client jar, mods from <gameDir>/mods."""
    return [str(java), tag, *CLIENT_MEMORY, *jvm_args, f'-Dfabric.gameJarPath={client_jar}',
            '-cp', ':'.join(str(path) for path in classpath),
            'net.fabricmc.loader.impl.launch.knot.KnotClient', *game_args]


def fabric_server_command(java: Path, tag: str, classpath: list[Path], server_jar: Path,
                          jvm_args: list[str]) -> list[str]:
    """A Fabric dedicated server: Knot on Mojang's server jar, mods from ./mods."""
    return [str(java), tag, *SERVER_MEMORY, *jvm_args, f'-Dfabric.gameJarPath={server_jar}',
            '-cp', ':'.join(str(path) for path in classpath),
            'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui']


def neoforge_server_command(java: Path, tag: str, neoforge_version: str,
                            jvm_args: list[str]) -> list[str]:
    """A NeoForge dedicated server as the installer's run.sh starts it. The working directory
    must contain `libraries` (the installed server's library folder)."""
    return [str(java), tag, *SERVER_MEMORY, *jvm_args,
            f'@libraries/net/neoforged/neoforge/{neoforge_version}/unix_args.txt', 'nogui']


def neoforge_client_classpath(profile: dict, library_dir: Path,
                              vanilla: list[tuple[str, Path]]) -> list[Path]:
    """Classpath of a NeoForge client: the installer profile's libraries, then the vanilla
    libraries the profile does not replace. `vanilla` is (Maven name, path) pairs."""
    paths = []
    replaced = set()
    for entry in profile['libraries']:
        paths.append(library_dir / entry['downloads']['artifact']['path'])
        replaced.add(tuple(entry['name'].split(':')[:2]))
    for name, path in vanilla:
        if tuple(name.split(':')[:2]) not in replaced:
            paths.append(path)
    return paths


def neoforge_client_command(java: Path, tag: str, profile: dict, library_dir: Path,
                            classpath: list[Path], jvm_args: list[str],
                            game_args: list[str]) -> list[str]:
    """A production NeoForge client from the official installer's client profile."""
    substitutions = {'${library_directory}': str(library_dir), '${classpath_separator}': ':',
                     '${version_name}': profile['id']}

    def substitute(argument: str) -> str:
        """Fill the launcher placeholders of one argument."""
        for placeholder, value in substitutions.items():
            argument = argument.replace(placeholder, value)
        return argument

    profile_jvm = [substitute(item) for item in profile['arguments'].get('jvm', [])
                   if isinstance(item, str)]
    profile_game = [item for item in profile['arguments'].get('game', []) if isinstance(item, str)]
    return [str(java), tag, *CLIENT_MEMORY, *jvm_args, *profile_jvm,
            '-cp', ':'.join(str(path) for path in classpath), profile['mainClass'],
            *game_args, *profile_game]
