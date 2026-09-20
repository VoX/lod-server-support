#!/usr/bin/env python3
"""Benchmark provenance. Required JSON gates completion; JFR is optional evidence."""
import datetime as dt
import json
import math
import shutil
import subprocess
import sys
import uuid
from pathlib import Path


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def reject_constant(value):
    raise ValueError(f'non-finite JSON value: {value}')


def read(path):
    return json.loads(path.read_text(), parse_constant=reject_constant)


def invalidate(root):
    for pattern in ('server*.json', 'client*.json', '*.jfr', 'cpu*.jsonl', 'server*.log',
                    'client*.log', 'warm-join-meta.json', 'current.json'):
        for path in root.glob(pattern):
            path.unlink()


def valid_metrics(path, kind, duration):
    data = read(path)
    if not isinstance(data, dict) or not isinstance(data.get('timestamp'), str):
        raise ValueError(f'{kind}: missing timestamp/object')
    if kind == 'server':
        if isinstance(data.get('duration_seconds'), bool) or data.get('duration_seconds') != duration:
            raise ValueError('server: incorrect duration_seconds')
        group = data.get('throughput', {})
        keys = ('total_sections_sent', 'total_bytes_sent')
    else:
        group = data
        keys = ('columns_received', 'bytes_received')
    for key in keys:
        value = group.get(key) if isinstance(group, dict) else None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError(f'{kind}: invalid {key}')
    return data


def main():
    command, *args = sys.argv[1:]
    if command == 'prepare':
        target = Path(args[0])
        if target.exists():
            previous = target.parent / '.previous' / (target.name + '-' + uuid.uuid4().hex)
            previous.parent.mkdir(parents=True, exist_ok=True)
            target.rename(previous)
            # Store/compression wrappers put their orchestrator log alongside
            # the arm directory. Preserve that log with the archived attempt.
            log = target.with_name(target.name + '.orchestrator.log')
            if log.exists():
                log.rename(previous / 'orchestrator.log')
        target.mkdir(parents=True)
        return 0
    if command == 'begin':
        root, scenario, duration = Path(args[0]), args[1], int(args[2])
        run = root / 'runs' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S-') + uuid.uuid4().hex)
        run.mkdir(parents=True)
        commit = subprocess.run(['git', '-C', str(root.parent), 'rev-parse', 'HEAD'],
                                text=True, capture_output=True).stdout.strip() or 'unknown'
        write(run / 'manifest.json', dict(run_id=run.name, commit=commit, scenario=scenario,
              duration_seconds=duration, started=now(), status='incomplete', cycles={}))
        invalidate(root)
        print(run)
        return 0
    if command == 'record':
        root, out = Path(args[0]), Path(args[1])
        out.mkdir(parents=True, exist_ok=True)
        if args[2:] == ['legacy'] and not (root / 'scripts/lib/benchmark-results.py').is_file():
            write(out / 'benchmark-manifest.json', dict(status='legacy-unverified', verified=False,
                  reason='Historical harness does not certify process exits or current-run exports'))
            print('[harness] Historical benchmark evidence is LEGACY/UNVERIFIED; excluded from acceptance gates', file=sys.stderr)
            return 0
        current = read(root / 'benchmark-results/current.json')
        run = root / 'benchmark-results/runs' / current['run_id']
        if current.get('status') != 'complete' or read(run / 'manifest.json') != current:
            raise ValueError('benchmark current identity is not a completed run')
        for cycle in current['cycles']:
            for kind in ('server', 'client'):
                valid_metrics(run / cycle / f'{kind}.json', kind, current['duration_seconds'])
        write(out / 'benchmark-manifest.json', current)
        return 0
    run = Path(args[0])
    manifest = read(run / 'manifest.json')
    if command == 'cycle-start':
        cycle = args[1]
        (run / cycle).mkdir()
        manifest['cycles'][cycle] = dict(started=now(), status='incomplete')
    elif command == 'collect':
        cycle, server, client, server_status, client_status = args[1:]
        out = run / cycle
        errors = []
        info = manifest['cycles'][cycle]
        info.update(server_exit=int(server_status), client_exit=int(client_status), finished=now())
        if int(server_status) or int(client_status):
            errors.append(f'process exit: server={server_status}, client={client_status}')
        for kind, source in (('server', Path(server)), ('client', Path(client))):
            exported = source / 'benchmark-results' / f'{kind}.json'
            try:
                # Retain malformed exports for diagnosis too; these source paths were
                # cleared immediately before this cycle's owned producers launched.
                shutil.copy2(exported, out / exported.name)
                valid_metrics(out / exported.name, kind, manifest['duration_seconds'])
            except (OSError, ValueError, TypeError) as exc:
                errors.append(f'{kind} metrics: {exc}')
            recording = source / f'{kind}-benchmark.jfr'
            info[f'{kind}_jfr'] = 'present' if recording.is_file() else 'absent (optional)'
            if recording.is_file():
                shutil.copy2(recording, out / recording.name)
        info['errors'] = errors
        info['status'] = 'incomplete' if errors else 'complete'
        write(run / 'manifest.json', manifest)
        for error in errors:
            print(f'[benchmark] ERROR: {error}', file=sys.stderr)
        return int(bool(errors))
    elif command == 'finish':
        required = ['populate', 'measure'] if manifest['scenario'] == 'warm-join' else ['measure']
        if any(manifest['cycles'].get(c, {}).get('status') != 'complete' for c in required):
            raise ValueError('cannot publish an incomplete cycle')
        root = run.parent.parent
        for cycle in required:
            suffix = '-populate' if cycle == 'populate' else ''
            for source in (run / cycle).iterdir():
                name = source.stem + suffix + source.suffix
                shutil.copy2(source, root / name)
        meta = run / 'warm-join-meta.json'
        if meta.exists():
            shutil.copy2(meta, root / meta.name)
        manifest.update(status='complete', finished=now())
        write(run / 'manifest.json', manifest)
        # current.json is the final publication point, after every alias is valid.
        write(root / 'current.json', manifest)
    elif command == 'fail':
        manifest.update(status='incomplete', exit_status=int(args[1]), finished=now())
        invalidate(run.parent.parent)
    elif command == 'current':
        if manifest['status'] != 'complete':
            raise ValueError('current run is incomplete')
        print(json.dumps(read(run / 'measure' / 'server.json'), indent=2))
        return 0
    else:
        raise ValueError(f'unknown command: {command}')
    write(run / 'manifest.json', manifest)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(f'[benchmark] ERROR: {exc}', file=sys.stderr)
        sys.exit(1)
