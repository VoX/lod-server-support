"""Derive comparable experiment inputs from actual runtime bytes and host facts."""
import os
import platform
from pathlib import Path
from rig import digest,sha,regular


def hardware(gpu):
    cpu={}
    for line in Path('/proc/cpuinfo').read_text().splitlines():
        if ':' in line:
            key,value=(part.strip() for part in line.split(':',1))
            if key in ('vendor_id','model name','cpu family','model','stepping'):cpu.setdefault(key,value)
    memory=next(int(line.split()[1])*1024 for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))
    return {'cpu':cpu,'logical_cpus':os.cpu_count(),'memory_bytes':memory,'kernel':platform.release(),
            'gpu_renderer':gpu.get('renderer'),'accelerated':gpu.get('accelerated')}


def fingerprints(runtime,artifact_paths,fixture_paths,gpu):
    staged={row['target']:row['sha256'] for row in runtime['stage_files']}
    if len(staged)!=len(runtime['stage_files']):raise ValueError('duplicate staged target')
    if not set(artifact_paths.values())<=set(staged) or not set(fixture_paths)<=set(staged):raise ValueError('measurement artifact not staged')
    if set(artifact_paths.values())&set(fixture_paths):raise ValueError('production artifact mislabeled as fixture')
    jvms={}
    for launch in runtime['launches']:
        argv=launch['argv'];executable=regular(argv[0])
        if executable.name!='java':raise ValueError('measured launch must expose its actual Java process')
        home=executable.resolve().parent.parent
        inputs={str(path.relative_to(home)):sha(regular(path)) for path in [home/'bin/java',home/'release',home/'lib/server/libjvm.so']}
        jvms[launch['id']]={'jdk_inputs':inputs,'argv':argv,'environment':runtime.get('gpu_environment',{})}
    # Production jar hashes differ by arm; every other staged dependency,
    # generated config, launch/input schedule and fixture remains identical.
    workload={'dependencies':{path:value for path,value in staged.items() if path not in artifact_paths.values()},
              'generated_files':runtime.get('generated_files',{}),'launches':runtime['launches'],
              'private_display':runtime.get('private_display',False),'backend':runtime['backend']}
    return {'hardware_hash':digest(hardware(gpu)),'jvm_hash':digest(jvms),'workload_hash':digest(workload),
            'fixture_hash':digest({path:staged[path] for path in fixture_paths})}
