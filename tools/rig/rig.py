#!/usr/bin/env python3
"""Disposable Linux rig. No download, credentials, desktop automation or implicit retry."""
from bind_endpoints import bindings, check_available
from source_client_natives import prepare_directory as prepare_source_native_directory
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid

REPO = Path(__file__).resolve().parents[2]

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def sha(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def read(path):
    return json.loads(Path(path).read_text())

def write(path, data):
    path = Path(path)
    tmp = path.with_suffix('.tmp')
    with tmp.open('w') as stream:
        stream.write(json.dumps(data, indent=2, sort_keys=True) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    tmp.replace(path)
    directory=os.open(path.parent,os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)

def regular(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('expected independent regular file: ' + str(path))
    return path

def inside(root, relative):
    path = root / relative
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('path escapes owned root')
    return path

def identity(pid):
    try:
        # comm may contain spaces and parentheses.
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return {'pid': int(pid), 'start': fields[19], 'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}
    except (FileNotFoundError, ProcessLookupError):
        return None

def alive(owner):
    return owner is not None and identity(owner['pid']) == owner

def descendant(pid, ancestor):
    seen = set()
    while pid > 1 and pid not in seen:
        if pid == ancestor:
            return True
        seen.add(pid)
        try:
            pid = int(Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[1])
        except (FileNotFoundError, ProcessLookupError):
            return False
    return False

def endpoint(value):
    match = re.fullmatch(r'\[([^]]+)\]:(\d+)|([^:]+):(\d+)', value)
    if not match:
        raise ValueError('endpoint requires host:port or [IPv6]:port')
    host, port = (match[1], match[2]) if match[1] else (match[3], match[4])
    port = int(port)
    if not 1 <= port <= 65535:
        raise ValueError('invalid port')
    if host not in ('127.0.0.1', '::1', 'localhost'):
        raise ValueError('disposable rig endpoints must be loopback')
    return host, port

def free_endpoint(value):
    host, port = endpoint(value)
    family = socket.AF_INET6 if ':' in host else socket.AF_INET
    with socket.socket(family) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind((host, port))
        probe.listen(1)

def composition(runtime):
    """Resolve each participant's own platform lock; never treat mods as libraries."""
    from catalog import validate_profile
    from materialize import resolution
    refs = []
    if runtime.get('server_profile'):
        refs.append(('server', runtime['server_profile']))
    refs.extend((entry['role'], entry) for entry in runtime.get('client_profiles', []))
    roles = set()
    records = []
    for role, reference in refs:
        if not re.fullmatch('[A-Za-z0-9_-]+', role) or role in roles:
            raise ValueError('invalid/duplicate participant role')
        roles.add(role)
        locked = read(regular(reference['path']))
        validate_profile(locked, allow_unresolved_ranges=bool(runtime.get("range_runtime")))
        if locked['id'] != reference['id'] or digest(locked) != reference['profile_hash']:
            raise ValueError('participant profile identity changed')
        resolved_profile = dict(locked, artifacts=locked['artifacts'] + reference.get('candidate_artifacts', []))
        resolved = resolution(resolved_profile, runtime.get('cache', {}), runtime.get('range_runtime'))
        if not resolved['ready']:
            raise ValueError('participant profile unresolved: ' + role + ': ' + json.dumps(resolved))
        records.append({'role': role, 'id': locked['id'], 'profile_hash': digest(locked), 'profile': locked})
    return records

def plan(profile, runtime, scenario):
    sys.path.insert(0, str(REPO / 'tools/compat'))
    from catalog import validate_profile
    validate_profile(profile, allow_unresolved_ranges=bool(runtime.get("range_runtime")))
    from materialize import resolution
    resolved_profile = dict(profile, artifacts=profile['artifacts'] + runtime.get('candidate_artifacts', []))
    resolved = resolution(resolved_profile, runtime.get('cache', {}), runtime.get('range_runtime'))
    if resolved['errors']:
        raise ValueError('; '.join(resolved['errors']))
    participants = composition(runtime)
    missing = resolved['missing']
    bindings(runtime, endpoint)
    endpoint(runtime['client_endpoint'])
    if runtime['backend'] not in ('linux-headless', 'isolated-linux-prism', 'windows-observer'):
        raise ValueError('unknown backend')
    return {'schema_version': 1, 'profile_id': profile['id'], 'profile_hash': digest(profile),
            'scenario_hash': digest(scenario), 'scenario_id': scenario['id'],
            'missing': missing, 'status': 'blocked' if missing or profile['status'] in ('blocked', 'unsupported') or runtime['backend'] == 'windows-observer' else 'ready',
            'backend': runtime['backend'], 'client_endpoint': runtime['client_endpoint'],
            'bind_endpoints': bindings(runtime, endpoint),
            'participants': [{k: entry[k] for k in ('role', 'id', 'profile_hash')} for entry in participants]}

def doctor(runtime):
    names = ['python3', 'java']
    if runtime['backend'] == 'isolated-linux-prism':
        names += ['Xvfb', 'xauth', 'xprop', 'glxinfo', 'prismlauncher']
    result = {name: bool(shutil.which(runtime.get(name, name))) for name in names}
    result['linux_ownership'] = sys.platform == 'linux' and Path('/proc/self/stat').is_file()
    result['disk_free_bytes'] = shutil.disk_usage(Path.home()).free
    for kind in ('bind_endpoint', 'client_endpoint'):
        endpoint(runtime[kind])
    try:
        check_available(runtime, endpoint, free_endpoint)
        result['bind_available'] = True
    except OSError:
        result['bind_available'] = False
    result['credentials_copied'] = False
    return result

def require_lock():
    import fcntl
    fd = int(os.environ.get("LSS_HARNESS_LOCK_FD", "-1"))
    lock = Path(f"/tmp/lss-harness-{os.getuid()}/port-25565.lock")
    if fd < 0 or not Path(f"/proc/self/fd/{fd}").samefile(lock):
        raise ValueError("use tools/rig/rig; coarse harness ownership required")
    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

def create(profile, runtime, scenario, state):
    require_lock()
    result = plan(profile, runtime, scenario)
    if result['status'] != 'ready':
        raise ValueError('profile blocked; resolve exact plan before create')
    check_available(runtime, endpoint, free_endpoint)
    run_id = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex[:12]
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    if state.is_symlink():
        raise ValueError('state root is a symlink')
    root = state / run_id
    root.mkdir(mode=0o700)
    for name in ('artifacts', 'server', 'client', 'evidence'):
        (root / name).mkdir(mode=0o700)
    for artifact in profile['artifacts']:
        if artifact.get('enabled', True):
            name = artifact['file']
            if Path(name).name != name:
                raise ValueError('artifact filename must be a basename')
            source = regular(runtime['cache'][artifact['sha256']])
            target = root / 'artifacts' / name
            if target.exists():
                raise ValueError('duplicate artifact filename')
            shutil.copyfile(source, target)
            if sha(target) != artifact['sha256']:
                raise ValueError('artifact changed during staging')
    for entry in runtime.get('stage_files', []):
        source = regular(entry['source'])
        if sha(source) != entry['sha256']:
            raise ValueError('staging input identity mismatch')
        target = inside(root, entry['target'])
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise ValueError('staging target collision')
        shutil.copyfile(source, target)
        if sha(target) != entry['sha256']:
            raise ValueError('staging input changed during clone')
    for relative, content in runtime.get('generated_files', {}).items():
        target = inside(root, relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise ValueError('generated file target collision')
        target.write_text(content.replace('{run}', str(root)).replace('{run_id}', run_id).replace('{endpoint}', runtime['client_endpoint']))
    for participant in composition(runtime):
        directory = root / 'participants'
        directory.mkdir(exist_ok=True)
        write(directory / (participant['role'] + '.json'), participant['profile'])
    from runtime_trees import verify as verify_trees
    verify_trees(root,runtime.get('immutable_trees',{}))
    write(root / 'profile.json', profile)
    write(root / 'scenario.json', scenario)
    # Runtime contains local launch context paths, but never account contents.
    write(root / 'runtime.json', runtime)
    from toolchain import snapshot
    run_manifest = {'profile_hash': digest(profile), 'scenario_hash': digest(scenario), 'runtime_hash': digest(runtime),
                    'runtime_tools': snapshot(REPO),
                    'runner_sha256': sha(Path(__file__)), 'checker_sha256': digest({name: sha(Path(__file__).with_name(name)) for name in ('proof.py', 'check_source_seed.py', 'check_regions.py', 'check_workload.py', 'performance.py', 'metrics.py', 'measure.py')}),
                    'staged_inputs': [{k: row[k] for k in ('sha256', 'target')} for row in runtime.get('stage_files', [])],
                    'generated_config_hash': digest(runtime.get('generated_files', {}))}
    result.update(run_id=run_id, status='created', created_at=time.time(), runtime_hash=digest(runtime),
                  run_manifest=run_manifest, run_hash=digest(run_manifest))
    from toolchain import retain
    retain(REPO,root/'tool-sources',run_manifest['runtime_tools'])
    write(root / 'manifest.json', result)
    return root

def private_display(root, env, children):
    """Xvfb allocates its display atomically; cookie is generated, never exported."""
    authority = root / 'Xauthority'
    authority.touch(mode=0o600)
    cookie = os.urandom(16).hex()
    subprocess.run(['xauth', '-f', str(authority), 'add', ':999', '.', cookie], check=True, capture_output=True)
    display = ':' + str(100 + int.from_bytes(os.urandom(2), 'big'))
    if display == os.environ.get('DISPLAY'):
        raise ValueError('refusing ordinary desktop display')
    subprocess.run(['xauth', '-f', str(authority), 'add', display, '.', cookie], check=True, capture_output=True)
    from launch_journal import before_spawn, spawned, spawn_failed
    before_spawn(root,'display')
    try:
        proc = subprocess.Popen(['Xvfb', display, '-screen', '0', '960x540x24',
                             '-nolisten', 'tcp', '-auth', str(authority)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        spawn_failed(root,'display')
        raise
    children.append(proc)
    display_owner=spawned(root,'display',proc)
    probe_env = dict(env, DISPLAY=display, XAUTHORITY=str(authority))
    deadline = time.monotonic() + 15
    while subprocess.run(['xprop', '-root'], env=probe_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        if proc.poll() is not None or time.monotonic() >= deadline:
            raise ValueError('private display startup timeout/collision')
        time.sleep(.1)
    env.update(DISPLAY=display, XAUTHORITY=str(authority), ALSOFT_DRIVERS='null')
    env.pop('WAYLAND_DISPLAY', None)
    write(root / 'display.json', {'display': display, 'host_display': os.environ.get('DISPLAY',''), 'xvfb': display_owner})
    return display

def validate_private_xvfb_args(root, display, executable, argv):
    authority = str((root / 'Xauthority').resolve())
    if Path(executable).name != 'Xvfb' or not argv or Path(argv[0]).name != 'Xvfb':
        raise ValueError('owned display is not native Xvfb')
    if argv.count(display['display']) != 1:
        raise ValueError('Xvfb display identity differs')
    for option, value in (('-nolisten','tcp'),('-auth',authority)):
        positions=[i for i,word in enumerate(argv) if word==option]
        if len(positions)!=1 or positions[0]+1>=len(argv) or argv[positions[0]+1]!=value:
            raise ValueError('Xvfb isolation arguments differ: '+option)

def verify_private_xvfb(root, display):
    expected=display['xvfb'];proc=Path('/proc')/str(expected['pid'])
    if identity(expected['pid'])!=expected:raise ValueError('Xvfb process identity changed')
    argv=[value.decode() for value in (proc/'cmdline').read_bytes().split(b'\0') if value]
    executable=(proc/'exe').resolve(strict=True)
    authority=regular(root/'Xauthority')
    if authority.stat().st_mode & 0o077:raise ValueError('private Xauthority permissions differ')
    validate_private_xvfb_args(root,display,executable,argv)
    if identity(expected['pid'])!=expected or not alive(expected):raise ValueError('Xvfb process identity changed')

def guard_display(root):
    display = read(root / 'display.json')
    owner = read(root / 'owner.json')
    if (not re.fullmatch(r':[1-9][0-9]*', display['display'])
            or not isinstance(display.get('host_display'),str)
            or display['display'] == display['host_display']):
        raise ValueError('default desktop input forbidden')
    if not alive(display['xvfb']) or not alive(owner):
        raise ValueError('stale process creation identity')
    if not descendant(display['xvfb']['pid'], owner['pid']):
        raise ValueError('foreign private display process')
    verify_private_xvfb(root,display)
    env = os.environ.copy()
    env.update(DISPLAY=display['display'], XAUTHORITY=str(root / 'Xauthority'))
    return env

def guard_window(root, window, expected):
    env = guard_display(root)
    owner = read(root / 'owner.json')
    if not alive(expected):
        raise ValueError('stale process creation identity')
    if not descendant(expected['pid'], owner['pid']):
        raise ValueError('foreign game process')
    prop = subprocess.check_output(['xprop', '-id', str(window), '_NET_WM_PID'], env=env, text=True)
    if not re.search(r'=\s*' + str(expected['pid']) + r'\s*$', prop):
        raise ValueError('foreign window PID')
    return env

def input_action(root, window, expected, action, args):
    # XTest emits the real input path used by GLFW. Explicit-window SendEvent
    # delivery is not accepted as equivalent Minecraft key handling.
    from private_input import XInput
    driver = XInput(root, window, expected)
    try:
        if action == 'key':
            for key in args: driver.key(key)
        elif action == 'text': driver.text(' '.join(args))
        elif action == 'capture': driver.capture(args[0])
        elif action == 'hold': driver.key(args[0], float(args[1]))
        elif action == 'click': driver.click(int(args[0]), int(args[1]))
        else: raise ValueError('unsupported private input action')
    finally:
        driver.close()

def terminate_owned(children, display_pid=None):
    # Keep X alive through application shutdown hooks. Closing the display first
    # can trigger native XIO exit before Java's bounded evidence writers flush.
    groups = ([p for p in reversed(children) if p.pid != display_pid],
              [p for p in reversed(children) if p.pid == display_pid])
    for group in groups:
        for proc in group:
            if proc.poll() is None:
                proc.terminate()
        for proc in group:
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill(); proc.wait()

def run(root):
    from proof import check_proof
    require_lock()
    manifest, runtime, scenario, profile = [read(root / (x + '.json')) for x in ('manifest', 'runtime', 'scenario', 'profile')]
    if manifest['status'] != 'created':
        raise ValueError('attempt is immutable; create a new run for retry')
    from toolchain import verify
    verify(REPO, manifest.get('run_manifest', {}).get('runtime_tools'))
    verify(root/'tool-sources',manifest['run_manifest']['runtime_tools'])
    from runtime_trees import verify as verify_trees
    verify_trees(root, runtime.get('immutable_trees', {}))
    if manifest['profile_hash'] != digest(profile) or manifest['scenario_hash'] != digest(scenario) or manifest['runtime_hash'] != digest(runtime):
        raise ValueError('stale run identity')
    for participant in manifest.get('participants', []):
        if digest(read(root / 'participants' / (participant['role'] + '.json'))) != participant['profile_hash']:
            raise ValueError('participant profile bytes changed')
    for artifact in profile['artifacts']:
        if artifact.get('enabled', True) and sha(regular(root / 'artifacts' / artifact['file'])) != artifact['sha256']:
            raise ValueError('staged artifact mismatch')
    for entry in runtime.get('stage_files', []):
        if sha(regular(inside(root, entry['target']))) != entry['sha256']:
            raise ValueError('staged runtime bytes changed')
    if profile['status'] != 'unverified':
        raise ValueError('blocked/unsupported profile cannot run')
    from run_claim import acquire
    acquire(root,runtime,manifest)
    check_available(runtime, endpoint, free_endpoint)
    write(root / 'owner.json', identity(os.getpid()))
    write(root / 'supervisor.json', identity(os.getppid()))
    from launch_journal import initialize, before_spawn, spawned, spawn_failed, terminal
    initialize(root,manifest,runtime,read(root/'owner.json'),read(root/'supervisor.json'))
    manifest['launch_journal_version']=1
    children, logs, launched = [], [], []
    rss_stream = None
    observation_completed = False
    runtime_failures = []
    stopped = []
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, lambda signum, frame: stopped.append(signum))
    env = os.environ.copy()
    env.update(ALSOFT_DRIVERS='null', LSS_RIG_RUN_ID=manifest['run_id'])
    manifest.update(status='running', started_at=time.time())
    write(root / 'manifest.json', manifest)
    try:
        if runtime['backend'] == 'isolated-linux-prism':
            private_display(root, env, children)
            gpu_env = runtime.get('gpu_environment', {})
            if set(gpu_env) - {'GALLIUM_DRIVER', 'MESA_D3D12_DEFAULT_ADAPTER_NAME', 'DRI_PRIME', 'ALSOFT_DRIVERS'}:
                raise ValueError('unsafe GPU environment key')
            env.update(gpu_env)
            gl = subprocess.check_output(['glxinfo', '-B'], env=env, text=True, timeout=20)
            if 'llvmpipe' in gl.lower() or 'softpipe' in gl.lower() or 'Accelerated: yes' not in gl:
                raise ValueError('GPU-required profile selected software or unproven acceleration')
            write(root / 'gpu.json', {'accelerated': True, 'renderer': next((s.strip() for s in gl.splitlines() if 'OpenGL renderer string:' in s), 'unknown')})
        if runtime['backend'] == 'linux-headless' and runtime.get('private_display'):
            private_display(root, env, children)
            gpu_env = runtime.get('gpu_environment', {})
            if set(gpu_env) - {'GALLIUM_DRIVER', 'MESA_D3D12_DEFAULT_ADAPTER_NAME', 'DRI_PRIME', 'ALSOFT_DRIVERS'}:
                raise ValueError('unsafe GPU environment key')
            env.update(gpu_env)
            if runtime.get('require_gpu'):
                gl = subprocess.check_output(['glxinfo', '-B'], env=env, text=True, timeout=20)
                if 'llvmpipe' in gl.lower() or 'softpipe' in gl.lower() or 'Accelerated: yes' not in gl:
                    raise ValueError('GPU-required direct client selected software or unproven acceleration')
                write(root / 'gpu.json', {'accelerated': True, 'renderer': next((s.strip() for s in gl.splitlines() if 'OpenGL renderer string:' in s), 'unknown')})
        if runtime.get('require_gpu') and not (root / 'gpu.json').exists():
            raise ValueError('GPU-required runtime lacks an owned private display')
        for launch in runtime['launches']:
            if not re.fullmatch('[A-Za-z0-9_-]+', launch['id']):
                raise ValueError('invalid launch ID')
            argv = [arg.replace('{run}', str(root)).replace('{run_id}', manifest['run_id']).replace('{endpoint}', runtime['client_endpoint']) for arg in launch['argv']]
            if '--server' in argv and any('prism' in part.lower() for part in argv):
                raise ValueError('unverified Prism --server connection route')
            working = inside(root, launch['cwd'])
            working.mkdir(parents=True, exist_ok=True)
            log = open(root / (launch['id'] + '.private.log'), 'xb')
            logs.append(log)
            prepare_source_native_directory(root,launch)
            before_spawn(root,'launch:'+launch['id'])
            try:
                proc = subprocess.Popen(argv, cwd=working, env=env, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
            except OSError:
                spawn_failed(root,'launch:'+launch['id'])
                raise
            children.append(proc)
            launched.append((launch, proc))
            spawned(root,'launch:'+launch['id'],proc)
            if launch.get('ready_marker'):
                ready_deadline = time.monotonic() + launch.get('ready_timeout_seconds', 120)
                while launch['ready_marker'] not in (root / (launch['id'] + '.private.log')).read_text(errors='replace'):
                    if proc.poll() is not None or time.monotonic() >= ready_deadline or stopped or (root/'stop').exists():
                        raise ValueError('Minecraft readiness timeout/exit: ' + launch['id'])
                    time.sleep(.2)
        targets = {launch['id']: proc for launch, proc in launched}
        if len(targets) != len(launched):
            raise ValueError('duplicate launch identity')
        for condition in runtime.get('ready_conditions', []):
            target = condition['launch_id'];marker = condition['marker']
            if target not in targets or not isinstance(marker, str) or not 0 < len(marker) <= 256:
                raise ValueError('invalid joint readiness condition')
            seconds = condition.get('timeout_seconds', 120)
            if not isinstance(seconds, (int, float)) or not 0 < seconds <= 180:
                raise ValueError('invalid joint readiness deadline')
            ready_deadline = time.monotonic() + seconds
            while marker not in (root / (target + '.private.log')).read_text(errors='replace'):
                if stopped or (root / 'stop').exists() or time.monotonic() >= ready_deadline or any(proc.poll() is not None for proc in children):
                    raise ValueError('joint workload readiness timeout/exit')
                time.sleep(.1)
        from commands import Commands
        commands = Commands(root, launched)
        from measure import Sampler
        sampler = Sampler({launch['id']: identity(proc.pid) if Path(launch['argv'][0]).name == 'java' else None for launch, proc in launched})
        rss_stream = open(root / 'evidence/rss-samples.jsonl', 'x')
        deadline = time.monotonic() + scenario.get('observe_seconds', scenario['timeout_seconds'])
        while time.monotonic() < deadline and not stopped and not (root / 'stop').exists():
            commands.poll()
            for observation in sampler.sample():
                rss_stream.write(json.dumps(observation) + '\n')
            rss_stream.flush()
            if (root / 'proof.json').exists():
                break
            if any(p.poll() is not None for p in children):
                raise ValueError('startup/process exited before semantic proof')
            time.sleep(.1)
        observation_completed = time.monotonic() >= deadline and not stopped and not (root / 'stop').exists()
        from proof import check_proof
        errors = check_proof(read(root / 'proof.json') if (root / 'proof.json').exists() else {}, manifest, scenario, root)
        if (root/'proof.json').is_file():
            runtime_failures.extend(error for error in errors if not error.startswith('visual review pending/rejected: '))
            incoming=read(root/'proof.json')
            if isinstance(incoming,dict) and isinstance(incoming.get('failures'),list):
                runtime_failures.extend(str(error) for error in incoming['failures'])
        manifest.update(status='failed' if errors else 'passed', errors=errors)
    except Exception as error:
        runtime_failures.append(str(error))
        manifest.update(status='failed', errors=[str(error)])
    finally:
        # Graceful protocol commands target only streams opened by this run.
        for launch, proc in reversed(launched):
            if launch.get('stop_stdin') and proc.poll() is None:
                try:
                    proc.stdin.write((launch['stop_stdin'] + '\n').encode()); proc.stdin.flush()
                    proc.wait(timeout=20)
                except (OSError, subprocess.TimeoutExpired):
                    pass
        # Outer owned-process supervisor additionally reaps nested/escaped children.
        display_pid = read(root / 'display.json')['xvfb']['pid'] if (root / 'display.json').exists() else None
        terminate_owned(children, display_pid)
        for log in logs:
            log.close()
        if rss_stream is not None:
            rss_stream.close()
        if scenario.get('checker') == 'export-lifecycle' and (root/'proof.json').is_file():
            try:
                from check_export_lifecycle import make_proof
                export_proof=make_proof(root,manifest)
                errors=check_proof(export_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['export lifecycle checker failed: '+str(error)])
        if scenario.get('checker') == 'receive-lifecycle' and (root/'proof.json').is_file():
            try:
                from check_receive_run import make_proof
                lifecycle_proof=make_proof(root,manifest)
                errors=check_proof(lifecycle_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['lifecycle checker failed: '+str(error)])
        if scenario.get('checker') == 'send-admission' and (observation_completed or (root/'proof.json').is_file()):
            try:
                from check_send_admission_run import make_proof
                admission_proof=make_proof(root,manifest)
                errors=check_proof(admission_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['send admission checker failed: '+str(error)])
        if scenario.get('checker') == 'xaero-map' and (root/'proof.json').is_file():
            try:
                from check_xaero_map_run import make_proof
                map_proof=make_proof(root,manifest,require_closed=True)
                errors=check_proof(map_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['native map checker failed: '+str(error)])
        if scenario.get('checker') == 'seated-draw' and (observation_completed or (root/'proof.json').is_file()):
            try:
                from check_seated_run import make_proof
                seated_proof=make_proof(root,manifest)
                errors=check_proof(seated_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['seated draw checker failed: '+str(error)])
        if scenario.get('checker') == 'source-prefill' and observation_completed:
            try:
                from check_prefill import inspect
                server_codes=[proc.returncode for launch,proc in launched if launch['id']=='server']
                write(root/'evidence/source-prefill-stop.json',{'run_id':manifest['run_id'],'server_returncode':server_codes[0] if len(server_codes)==1 else None})
                outcome=inspect(root,manifest)
                write(root/'evidence/prefill-result.json',outcome)
                prefill_proof={key:manifest[key] for key in ('run_id','profile_hash','scenario_hash','run_hash')}
                prefill_proof.update(ready=outcome['status']=='passed',test_count=outcome['test_count'],prefill_report=outcome,assertions={key:outcome['status']=='passed' for key in scenario['assertions']},failures=outcome['errors'])
                write(root/'proof.json',prefill_proof)
                errors=check_proof(prefill_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                write(root/'evidence/prefill-result.json',{'status':'failed','errors':[str(error)],'run_hash':manifest['run_hash']})
                manifest.update(status='failed',errors=['native prefill checker failed: '+str(error)])
        if scenario.get('checker') == 'source-seed' and observation_completed:
            try:
                from check_source_seed import inspect
                server_codes=[proc.returncode for launch,proc in launched if launch['id']=='server']
                write(root/'evidence/source-seed-stop.json', {'run_id':manifest['run_id'],'server_returncode':server_codes[0] if len(server_codes)==1 else None})
                outcome=inspect(root,manifest)
                seed_proof={key:manifest[key] for key in ('run_id','profile_hash','scenario_hash','run_hash')}
                seed_proof.update(ready=outcome['status']=='passed',test_count=outcome['test_count'],source_seed_report=outcome,
                    assertions={key:outcome['status']=='passed' for key in scenario['assertions']},failures=outcome['errors'])
                write(root/'proof.json',seed_proof)
                errors=check_proof(seed_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                manifest.update(status='failed',errors=['source seed checker failed: '+str(error)])
        if scenario.get('checker') == 'concurrent-sources' and observation_completed:
            try:
                from check_source_run import inspect, make_proof
                outcome=inspect(root,manifest)
                write(root/'evidence/source-correctness.json',outcome)
                source_proof=make_proof(outcome)
                write(root/'proof.json',source_proof)
                errors=check_proof(source_proof,manifest,scenario,root)
                manifest.update(status='failed' if errors else 'passed',errors=errors)
            except Exception as error:
                write(root/'evidence/source-correctness.json',{'status':'failed','run_hash':manifest['run_hash'],'errors':['mixed-source checker failed: '+str(error)]})
                manifest.update(status='failed',errors=['mixed-source checker failed: '+str(error)])
        if scenario.get('checker') == 'folia-regions' and observation_completed:
            try:
                from check_regions import check, load_rows, handshakes
                rows = load_rows(root / 'evidence/region-events.jsonl')
                ticks = load_rows(root / 'evidence/tick-events.jsonl')
                joins = {row['connection_id']: row for row in rows if row.get('event') == 'join'}
                outcome = check(rows, ticks, handshakes(root, joins))
                write(root / 'evidence/regions-result.json', dict(outcome, run_hash=manifest['run_hash']))
                proof = {key: manifest[key] for key in ('run_id', 'profile_hash', 'scenario_hash', 'run_hash')}
                proof.update(ready=True, handshake=not any('acceptance absent' in e for e in outcome['errors']), test_count=2,
                             assertions={key: outcome['status'] == 'passed' for key in scenario['assertions']}, failures=outcome['errors'])
                write(root / 'proof.json', proof)
                errors = check_proof(proof, manifest, scenario, root)
                manifest.update(status='failed' if errors else 'passed', errors=errors)
            except Exception as error:
                manifest.update(status='failed', errors=['postcleanup scenario checker failed: ' + str(error)])
        if runtime.get('authorized_launcher_context'):
            try:
                from launch_prism import verify_launcher_inputs
                verify_launcher_inputs(Path(runtime['authorized_launcher_context']), runtime.get('launcher_inputs'))
                manifest['launcher_closure_verified'] = True
            except Exception:
                # Do not serialize arbitrary launcher exception text or context
                # contents into the evidence manifest.
                manifest['launcher_closure_verified'] = False
                manifest.update(status='failed', errors=manifest.get('errors', []) + ['launcher dependency closure changed or could not be verified after cleanup'])
        manifest.update(finished_at=time.time(), cleanup='supervisor-pending')
        try:
            verify_trees(root, runtime.get('immutable_trees', {}))
        except ValueError as error:
            manifest.update(status='failed', errors=manifest.get('errors', []) + [str(error)])
        if runtime_failures:
            manifest.update(status='failed',errors=list(dict.fromkeys(runtime_failures+manifest.get('errors',[]))))
        # Missing human review is not a runtime failure, but can never be acceptance.
        # Freeze all semantic/visual inputs before releasing the owned processes.
        if manifest.get('status') == 'failed' and (root/'proof.json').is_file():
            from review_state import pending, semantic_digest
            final_proof=read(root/'proof.json')
            if pending(final_proof,scenario,manifest.get('errors',[]),root):
                from proof import check_proof
                if check_proof(final_proof,manifest,scenario,root)==manifest['errors']:
                    manifest.update(status='awaiting-review',review_pending_proof_hash=semantic_digest(final_proof))
        write(root / 'manifest.json', manifest)
        terminal(root,manifest['status'])
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['doctor', 'plan', 'create', 'run', 'status', 'stop', 'collect', 'review'])
    parser.add_argument('target', nargs='?')
    parser.add_argument('scenario', nargs='?')
    parser.add_argument('--runtime')
    parser.add_argument('--state', default=str(Path.home() / '.local/state/lss-rig/runs'))
    args = parser.parse_args()
    if args.command == 'doctor':
        result = doctor(read(args.runtime))
    elif args.command in ('plan', 'create'):
        values = read(args.target), read(args.runtime), read(args.scenario)
        result = plan(*values) if args.command == 'plan' else {'run': str(create(*values, Path(args.state)))}
    else:
        root = Path(args.target).resolve()
        manifest = read(root / 'manifest.json')
        if args.command == 'run':
            result = run(root)
        elif args.command == 'stop':
            # A stop request is consumed by the owning runner, never a port/PID kill.
            (root / 'stop').touch()
            result = {'stop_requested': True}
        elif args.command in ('collect', 'review'):
            from proof import check_proof
            errors = check_proof(read(root / 'proof.json') if (root / 'proof.json').exists() else {}, manifest, read(root / 'scenario.json'), root)
            semantic_errors=list(errors)
            from toolchain import verify
            try:
                verify(REPO, manifest.get('run_manifest', {}).get('runtime_tools'))
                verify(root/'tool-sources',manifest['run_manifest']['runtime_tools'])
            except ValueError as error:
                errors.append(str(error))
            runtime = read(root / 'runtime.json')
            from review_state import ownership_errors
            ownership_failures=ownership_errors(root,runtime)
            errors.extend(ownership_failures)
            processes = read(root/'processes.json') if not ownership_failures else []
            owner = read(root/'supervisor.json') if not ownership_failures else None
            if alive(owner) or any(alive(p) for p in processes):
                errors.append('owned processes remain live')
            pending_review=manifest.get('status')=='awaiting-review'
            if manifest.get('status') != 'passed' and not pending_review:
                errors.append('attempt did not pass')
            already_reviewed=(manifest.get('status')=='passed' and manifest.get('review_proof_hash')==digest(read(root/'proof.json')))
            if args.command=='review' and not pending_review and not already_reviewed:
                errors.append('review finalization requires an awaiting-review attempt')
            from runtime_trees import verify as verify_trees
            try:
                verify_trees(root, runtime.get('immutable_trees', {}))
            except ValueError as error:
                errors.append(str(error))
            for filename, expected in (('runtime.json', 'runtime_hash'), ('profile.json', 'profile_hash'), ('scenario.json', 'scenario_hash')):
                if digest(read(root / filename)) != manifest[expected]:
                    errors.append('run input identity changed before collection: ' + filename)
            if runtime.get('authorized_launcher_context'):
                try:
                    from launch_prism import verify_launcher_inputs
                    verify_launcher_inputs(Path(runtime['authorized_launcher_context']), runtime.get('launcher_inputs'))
                except Exception:
                    errors.append('launcher dependency closure could not be verified at collection')
            result = {key: manifest[key] for key in ('schema_version', 'run_id', 'profile_id', 'profile_hash', 'scenario_id', 'scenario_hash', 'backend', 'run_hash')}
            status='failed' if errors else 'passed'
            if pending_review:
                from review_state import pending
                proof=read(root/'proof.json')
                if pending(proof,read(root/'scenario.json'),errors,root) or (not errors and args.command=='collect'):
                    status='awaiting-review'
                elif not errors and args.command=='review':
                    # This explicit final step requires the real user's bound review.
                    manifest.update(status='passed',errors=[],reviewed_at=time.time(),review_proof_hash=digest(proof))
                    write(root/'manifest.json',manifest)
            if (pending_review or manifest.get('review_proof_hash')) and semantic_errors:
                from review_state import pending
                if not pending(read(root/'proof.json'),read(root/'scenario.json'),semantic_errors,root):
                    manifest.update(status='failed',errors=errors,review_failed_at=time.time())
                    write(root/'manifest.json',manifest)
            result.update(status=status, errors=errors,
                          cleanup='incomplete' if ownership_failures or alive(owner) or any(alive(p) for p in processes) else 'complete')
            write(root / 'evidence/result.json', result)
        else:
            result = manifest
    print(json.dumps(result, indent=2))
    return 1 if isinstance(result, dict) and result.get('status') == 'failed' else 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError) as error:
        print('rig: ' + str(error), file=sys.stderr)
        sys.exit(2)
