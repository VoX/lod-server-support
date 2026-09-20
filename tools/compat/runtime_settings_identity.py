"""Initial effective run settings, independent of outcomes and cache locations."""
import hashlib,json,re
from pathlib import PurePosixPath

def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def identity(runtime,staged_inputs=None,artifact_checker=None):
    rows=runtime.get('stage_files',[])
    staged={row['target']:row['sha256'] for row in rows}
    if len(staged)!=len(rows):raise ValueError('duplicate settings input target')
    if staged_inputs is not None and staged!={row['target']:row['sha256'] for row in staged_inputs}:
        raise ValueError('retained settings stage bindings changed')
    # Declared staged source paths are placement, not effective configuration.
    # References to them in launcher/config argv bind to their staged identity.
    replacements=sorted(((row['source'],'{staged:'+row['target']+'}') for row in rows),key=lambda pair:(-len(pair[0]),pair[1]))
    def normalize(value):
        if isinstance(value,str):
            for source,target in replacements:
                if source:value=value.replace(source,target)
            return value
        if isinstance(value,list):return [normalize(item) for item in value]
        if isinstance(value,dict):return {key:normalize(item) for key,item in value.items()}
        return value
    generated=runtime.get('generated_files',{})
    config=digest(normalize(generated))
    initial_config_inputs={target:value for target,value in staged.items() if PurePosixPath(target).suffix.lower() in ('.json','.cfg','.properties','.toml','.yaml','.yml','.txt')}
    initial_world_inputs={target:value for target,value in staged.items() if PurePosixPath(target).suffix.lower() in ('.mca','.dat','.sqlite','.db')}
    if artifact_checker is not None:
        for target,expected in {**initial_config_inputs,**initial_world_inputs}.items():
            if artifact_checker(target)!=expected:raise ValueError('intended settings source bytes changed: '+target)
    launches=runtime.get('launches',[])
    if len({row['id'] for row in launches})!=len(launches):raise ValueError('duplicate launch settings identity')
    excluded_environment={'DISPLAY','XAUTHORITY','LSS_HARNESS_LOCK_FD','LSS_RIG_RUN_ID','LSS_RIG_OWNER_PID','LSS_RIG_SUPERVISOR_PID','PRISM_ACCOUNT_ID','MINECRAFT_ACCESS_TOKEN','ACCESS_TOKEN'}
    def environment(value):
        return {key:item for key,item in (value or {}).items() if key not in excluded_environment}
    def jvm_flags(argv):
        return [arg for arg in argv[1:] if arg.startswith(('-D','-X','-XX:','-javaagent:','-agentlib:','-agentpath:')) and arg.split('=',1)[0] not in ('-Dlss.rig.runId','-Dlss.rig.ownerPid','-Dlss.rig.supervisorPid','-Dlss.rig.lockFd')]
    launch_settings=[{'id':row['id'],'jvm_flags':normalize(jvm_flags(row['argv'])),'environment':normalize(environment(row.get('environment',row.get('env',{}))))} for row in launches]
    settings={'schema_version':1,'initial_generated_config_sha256':config,'initial_staged_config_inputs':initial_config_inputs,'initial_world_inputs':initial_world_inputs,
              'world_sha256':runtime.get('world_digest') or digest({'kind':'fresh-generated-world','initial_configuration_hash':config}),
              'execution':{**{key:runtime.get(key) for key in ('backend','private_display','require_gpu')},'gpu_environment':normalize(environment(runtime.get('gpu_environment'))),'environment':normalize(environment(runtime.get('environment',runtime.get('env',{}))))},
              'launches':launch_settings,'bind_endpoint':runtime.get('bind_endpoint'),'bind_endpoints':runtime.get('bind_endpoints'),
              'client_endpoint':runtime.get('client_endpoint')}
    # Only a digest is exported: no local launcher/account path or raw argv.
    return {'runtime_settings_version':1,'runtime_settings_sha256':digest(settings)}
