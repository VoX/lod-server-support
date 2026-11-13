#!/usr/bin/env python3
"""Run the pinned SpongeVanilla server in a caller-staged run dir (sponge-support-plan.md §5.4).

Used by :sponge:runSoakServer (scripts/soak.sh SOAK_PLATFORM=sponge). Unlike the boot
smoke, the run dir is NOT wiped: the caller stages the world, config, server.properties
and eula first. This script only (1) fetches the sha1-verified server jar into the shared
cache, (2) replaces the plugin jars in mods/ with the given ones and links the cached
library closure, and (3) runs the server in the foreground. Extra JVM arguments follow
`--`. Stdlib only.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sponge_boot_smoke import fetch_server  # noqa: E402  (same directory)


def main(argv):
    if '--' in argv:
        split = argv.index('--')
        argv, jvm_args = argv[:split], argv[split + 1:]
    else:
        jvm_args = []
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--spongevanilla', required=True)
    ap.add_argument('--sha1', required=True)
    ap.add_argument('--java', required=True)
    ap.add_argument('--run-dir', required=True, type=Path)
    ap.add_argument('--plugin', action='append', required=True, type=Path)
    ap.add_argument('--cache', type=Path,
                    default=Path(os.environ.get('LSS_SPONGE_CACHE', Path.home() / '.cache' / 'lss-sponge')))
    args = ap.parse_args(argv)
    cache = args.cache / args.spongevanilla
    server_jar = fetch_server(args.spongevanilla, args.sha1, cache)
    run = args.run_dir
    mods = run / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for old in mods.glob('*.jar'):
        old.unlink()
    for plugin in args.plugin:
        shutil.copy2(plugin, mods / plugin.name)
    libraries = cache / 'libraries'
    libraries.mkdir(parents=True, exist_ok=True)
    link = run / 'libraries'
    if not link.is_symlink():
        if link.exists():
            shutil.rmtree(link)
        link.symlink_to(libraries, target_is_directory=True)
    cmd = [args.java, *jvm_args, '-jar', str(server_jar), 'nogui']
    return subprocess.call(cmd, cwd=run, stdin=subprocess.DEVNULL)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
