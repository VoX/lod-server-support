#!/usr/bin/env python3
"""Create reviewable private Prism/server runtime bindings; never copy accounts/worlds."""
import argparse,json,sys,zipfile
from pathlib import Path
from rig import sha,write,regular,require_lock
REPO=Path(__file__).resolve().parents[2]

def launcher_inputs(context,profile):
    context=Path(context).resolve();files={}
    for directory in ('libraries','assets'):
        for path in sorted((context/directory).rglob('*')):
            if path.is_symlink():raise ValueError('symlink in launcher dependency inputs')
            if path.is_file():files[str(path.relative_to(context))]=sha(path)
    if not files:raise ValueError('launcher dependency cache empty; explicit acquisition required')
    for component in profile['components']:
        if component['uid']=='java':continue
        uid,version=component['uid'],component['version']
        if Path(uid).name!=uid or Path(version).name!=version:raise ValueError('invalid component path')
        path=regular(context/'meta'/uid/(version+'.json'))
        files[str(path.relative_to(context))]=sha(path)
    return {'files':files,'policy':'exact cached assets/libraries and selected component metadata; no accounts'}

def verify_bootstrap(path,profile):
    components={item['uid']:item['version'] for item in profile['components']}
    with zipfile.ZipFile(regular(path)) as archive:
        if profile['platform']=='fabric':
            if archive.getinfo('install.properties').file_size>65536:raise ValueError('oversized bootstrap identity')
            raw=archive.read('install.properties')
            props=dict(line.split('=',1) for line in raw.decode().splitlines() if '=' in line and not line.startswith('#'))
            if props.get('game-version')!=components.get('net.minecraft') or props.get('fabric-loader-version')!=components.get('net.fabricmc.fabric-loader'):
                raise ValueError('Fabric bootstrap differs from locked Minecraft/loader identity')
        else:
            if archive.getinfo('version.json').file_size>65536:raise ValueError('oversized bootstrap identity')
            raw=archive.read('version.json')
            version=json.loads(raw)
            if version.get('id')!=components.get('net.minecraft') or str(version.get('java_version'))!=components.get('java'):
                raise ValueError('Paper bootstrap differs from locked Minecraft/Java identity')

def build(profile,cache,range_runtime,server_source,candidate,context,java,prism,endpoint,fixtures=(),server_candidate=None,server_profile=None,server_profile_path=None,server_fixtures=()):
    server_source=Path(server_source);candidate=regular(candidate)
    server_candidate=regular(server_candidate or candidate)
    if profile['platform']!='fabric' and server_candidate==candidate:raise ValueError('native client needs an explicit server candidate')
    sys.path.insert(0,str(REPO/'tools/compat'))
    from catalog import inspect_jar
    def describe_artifact(path):
        row=inspect_jar(path)
        from materialize import nested_metadata
        mods=[(m['id'],m['version']) for kind,m in nested_metadata(path) if kind=='fabric']
        mods += [(entry['modId'],entry['version']) for kind,m in nested_metadata(path) if kind=='maven' for entry in m.get('mods',[])]
        plugin=row['metadata'].get('paper')
        if not mods and not plugin:raise ValueError('candidate has no loader identity')
        mid,version=mods[0] if mods else (plugin['name'],str(plugin['version']))
        row.update(id=mid,version=version,source='cache:sha256:'+row['sha256'],enabled=True,kind='mod' if mods else 'plugin')
        return row
    client_artifact=describe_artifact(candidate)
    server_artifact=describe_artifact(server_candidate)
    server_profile=server_profile or dict(profile,artifacts=[a for a in profile['artifacts'] if a['id']=='fabric-api'])
    server_platform=server_profile['platform']
    if server_platform not in ('fabric','paper'):raise ValueError('server bootstrap requires Fabric or Paper profile')
    if server_platform not in server_artifact['metadata']:raise ValueError('server candidate does not match bootstrap platform')
    bootstrap='fabric-server-launch.jar' if server_platform=='fabric' else 'paper.jar'
    extension_dir='mods' if server_platform=='fabric' else 'plugins'
    if server_profile_path is None:raise ValueError('explicit server dependency profile path required')
    cache=dict(cache);cache[client_artifact['sha256']]=str(candidate);cache[server_artifact['sha256']]=str(server_candidate)
    instance='lss-rig-client';minecraft='instances/'+instance+'/minecraft'
    runtime={'backend':'isolated-linux-prism','bind_endpoint':endpoint,'client_endpoint':endpoint,
             'cache':cache,'candidate_artifacts':[client_artifact],'range_runtime':range_runtime,'stage_files':[],'generated_files':{},'launches':[],
             'gpu_environment':{'GALLIUM_DRIVER':'d3d12','MESA_D3D12_DEFAULT_ADAPTER_NAME':'NVIDIA','ALSOFT_DRIVERS':'null'},
             'java':java,'prismlauncher':prism,'authorized_launcher_context':str(context),'launcher_inputs':launcher_inputs(context,profile)}
    from catalog import digest
    runtime['server_profile']={'path':str(Path(server_profile_path).resolve()),'id':server_profile['id'],'profile_hash':digest(server_profile),'candidate_artifacts':[server_artifact]}
    def stage(path,target):
        path=regular(path);runtime['stage_files'].append({'source':str(path.resolve()),'sha256':sha(path),'target':target})
    # Fresh world and configuration. Only immutable server bootstrap dependencies.
    verify_bootstrap(server_source/bootstrap,server_profile)
    stage(server_source/bootstrap,'server/'+bootstrap)
    for directory in ('libraries','versions','cache'):
        frozen={}
        for path in sorted((server_source/directory).rglob('*')):
            if path.is_symlink():raise ValueError('symlink server input')
            if path.is_file():
                stage(path,'server/'+str(path.relative_to(server_source)))
                frozen[str(path.relative_to(server_source/directory))]=sha(path)
        runtime.setdefault('immutable_trees',{})['server/'+directory]=frozen
    for artifact in profile['artifacts']:
        if not artifact['enabled'] or artifact.get('kind','mod')!='mod':continue
        stage(cache[artifact['sha256']],minecraft+'/mods/'+artifact['file'])
    for artifact in server_profile['artifacts']:
        if artifact['enabled'] and artifact.get('kind','mod') in ('mod','plugin'):stage(cache[artifact['sha256']],'server/'+extension_dir+'/'+artifact['file'])
    stage(candidate,minecraft+'/mods/lod-server-support-'+profile['platform']+'.jar');stage(server_candidate,'server/'+extension_dir+'/lod-server-support-'+server_platform+'.jar')
    for fixture in fixtures:
        row=describe_artifact(regular(fixture));runtime['cache'][row['sha256']]=str(Path(fixture).resolve());runtime['candidate_artifacts'].append(row)
        stage(fixture,minecraft+'/mods/'+Path(fixture).name)
    for fixture in server_fixtures:
        row=describe_artifact(regular(fixture));runtime['cache'][row['sha256']]=str(Path(fixture).resolve());runtime['server_profile']['candidate_artifacts'].append(row)
        stage(fixture,'server/'+extension_dir+'/'+Path(fixture).name)
    config={'InstanceType':'OneSix','name':'LSS isolated '+profile['line'],'OverrideJavaLocation':'true','JavaPath':java,
            'OverrideJavaArgs':'true','JvmArgs':'-Dlss.rig.runId={run_id}','OverrideMemory':'true','MinMemAlloc':'512','MaxMemAlloc':'3072',
            'OverrideWindow':'true','MinecraftWinWidth':'960','MinecraftWinHeight':'540','LaunchMaximized':'false','OverrideCommands':'true',
            'PreLaunchCommand':'','PostExitCommand':'','WrapperCommand':''}
    runtime['generated_files']['instances/'+instance+'/instance.cfg']='[General]\n'+''.join(k+'='+v+'\n' for k,v in config.items())
    components=[c for c in profile['components'] if c['uid']!='java']
    runtime['generated_files']['instances/'+instance+'/mmc-pack.json']=json.dumps({'formatVersion':1,'components':components})+'\n'
    runtime['generated_files'][minecraft+'/options.txt']='maxFps:30\nrenderDistance:6\nsimulationDistance:5\npauseOnLostFocus:false\nenableVsync:false\nguiScale:2\nonboardAccessibility:false\n'
    runtime['generated_files'][minecraft+'/config/lss-client-config.json']=json.dumps({'receiveServerLods':True,'enableXaeroMapBridge':True,'enableXaeroMapBackpressure':True,'lodDistanceChunks':32})+'\n'
    from rig import endpoint as parse_endpoint
    host,port=parse_endpoint(endpoint)
    runtime['generated_files']['server/eula.txt']='eula=true\n'
    runtime['generated_files']['server/server.properties']=f'server-ip={host}\nserver-port={port}\nonline-mode=false\nlevel-type=minecraft:flat\nlevel-seed=project-improvements-functional\nview-distance=6\nsimulation-distance=5\nspawn-protection=0\nallow-flight=true\nenforce-secure-profile=false\n'
    server_config='server/config/lss-server-config.json' if server_platform=='fabric' else 'server/plugins/LodServerSupport/lss-server-config.json'
    runtime['generated_files'][server_config]=json.dumps({'lodDistanceChunks':32,'lodStore':'on'})+'\n'
    runtime['launches']=[{'id':'server','cwd':'server','argv':[java,'-Xms512M','-Xmx2G','-Dlss.rig.runId={run_id}','-jar',bootstrap,'nogui'],'ready_marker':'Done (','ready_timeout_seconds':180,'stop_stdin':'stop'},
        {'id':'client','cwd':'client','argv':[sys.executable,str(REPO/'tools/rig/launch_prism.py'),'--context',str(context),'--run','{run}','--java',java,'--prism',prism,'--instance',instance]}]
    return runtime

def main():
    require_lock()
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('profile','cache','range-runtime','server-source','candidate','context','java','prism','output'):p.add_argument('--'+name,required=True)
    p.add_argument('--server-candidate');p.add_argument('--server-profile',required=True);p.add_argument('--server-cache');p.add_argument('--endpoint',default='[::1]:25572');p.add_argument('--fixture',action='append',default=[]);p.add_argument('--server-fixture',action='append',default=[]);p.add_argument('--adapter',required=True,help='Discovered Mesa D3D12 adapter selector')
    a=p.parse_args();profile=json.loads(Path(a.profile).read_text());cache=json.loads(Path(a.cache).read_text());ranges=json.loads(Path(a.range_runtime).read_text())
    if a.server_cache:cache.update(json.loads(Path(a.server_cache).read_text()))
    server_profile=json.loads(Path(a.server_profile).read_text())
    runtime=build(profile,cache,ranges,a.server_source,a.candidate,a.context,a.java,a.prism,a.endpoint,a.fixture,a.server_candidate,server_profile,a.server_profile,a.server_fixture)
    runtime['gpu_environment']['MESA_D3D12_DEFAULT_ADAPTER_NAME']=a.adapter
    write(a.output,runtime)
if __name__=='__main__':main()
