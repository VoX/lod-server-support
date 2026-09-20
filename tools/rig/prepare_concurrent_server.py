#!/usr/bin/env python3
"""Offline 26.2 correctness-lane materializer; four reviewed client launches are reused.
Server dependencies and candidates are explicit and separately locked. No launch,
network fetch, Folia substitution, or performance-acceptance claim occurs here.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil
import zipfile
from rig import require_lock, sha, write, digest
from world_snapshot import stages
from source_client_natives import apply as isolate_source_client_natives


def main(platform):
    require_lock()
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('client-runtime','client-profile','java-home','server-candidate','server-fixture','world-snapshot','output'):
        p.add_argument('--'+name,required=True)
    if platform=='paper':
        p.add_argument('--server-cache',required=True)
        p.add_argument('--server-launcher',required=True)
    else:
        p.add_argument('--server-classpath-file',required=True)
    p.add_argument('--measured',action='store_true')
    a=p.parse_args()
    source=json.loads(Path(a.client_runtime).read_text())
    profile=json.loads(Path(a.client_profile).read_text())
    clients=[entry for entry in source['launches'] if entry['id'].startswith('client-')]
    if {entry['id'] for entry in clients}!={'client-A','client-B','client-C','client-D'}:
        p.error('exactly four explicit independent client launches are required')
    if profile.get('line')!='26.2' or profile.get('platform')!='fabric':
        p.error('this fixture targets the native 26.2 Fabric client closure only')
    for client in clients:
        subject='RigSubject'+client['id'][-1]
        if '-Dlss.rig.subject='+subject not in client['argv'] or '-Dlss.rig.endpoint={endpoint}' not in client['argv']:
            p.error('client fixture subject and reconnect endpoint must be explicit')
    out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=False,mode=0o700)
    cache=out/'cache';cache.mkdir()
    runtime=copy.deepcopy(source)
    runtime['launches']=copy.deepcopy(clients)
    isolate_source_client_natives(runtime)
    runtime['stage_files']=[entry for entry in runtime['stage_files'] if not entry['target'].startswith('server/')]
    runtime['generated_files']={name:value for name,value in runtime['generated_files'].items() if not name.startswith('server/')}
    runtime.pop('world_digest',None)
    runtime.pop('server_profile',None)
    runtime['immutable_trees']={key:value for key,value in runtime.get('immutable_trees',{}).items() if not key.startswith('server/')}
    server_profile={'schema_version':1,'id':'mc262-'+platform+'-source-correctness','line':'26.2','platform':platform,'route':'native','components':[{'uid':'net.minecraft','version':'26.2'},{'uid':'java','version':'25'}],'artifacts':[],'status':'unverified','capabilities':['four-client-source-correctness'],'limitations':['Fixture preparation and final target/debt checkers must pass before acceptance.','Radius/concurrency settings are workload pins, not measured production recommendations.']}
    candidates=[]
    def snapshot(path,target,kind='library',candidate=False):
        path=Path(path).resolve()
        if not path.exists():raise ValueError('missing explicit server input: '+str(path))
        dest=cache/('server-'+str(len(server_profile['artifacts'])+len(candidates))+'.jar')
        if path.is_dir():
            with zipfile.ZipFile(dest,'w') as archive:
                for item in sorted(path.rglob('*')):
                    if item.is_symlink():raise ValueError('symlink server input')
                    if item.is_file():archive.write(item,item.relative_to(path))
        else:shutil.copyfile(path,dest)
        checksum=sha(dest)
        metadata={}
        import sys
        sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
        from catalog import inspect_jar, Invalid
        try:
            if zipfile.is_zipfile(dest):metadata=inspect_jar(dest)['metadata']
            elif candidate:raise ValueError('candidate is not a jar')
        except Invalid as error:
            if candidate or 'no recognized mod metadata' not in str(error):raise
        if metadata:kind='plugin' if 'paper' in metadata else 'mod'
        entry={'id':'server-'+str(len(server_profile['artifacts'])+len(candidates)),'version':'locked','file':dest.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':metadata,'enabled':True,'kind':kind}
        (candidates if candidate else server_profile['artifacts']).append(entry)
        runtime['cache'][checksum]=str(dest)
        runtime['stage_files'].append({'source':str(dest),'sha256':checksum,'target':target})
        return '{run}/'+target
    java=str(Path(a.java_home).resolve()/'bin/java')
    prefix=[java,'-Xms512M','-Xmx2G','-Dlss.rig.runId={run_id}','-Dlss.rig.evidence={run}/evidence']
    if platform=='paper':
        closure=Path(a.server_cache)
        for base in ('libraries','versions','cache'):
            frozen={}
            if not (closure/base).is_dir():raise ValueError('missing explicit Paper cache directory: '+base)
            for item in sorted((closure/base).rglob('*')):
                if item.is_symlink():raise ValueError('symlink server cache')
                if item.is_file():
                    snapshot(item,'server/'+str(item.relative_to(closure)))
                    frozen[str(item.relative_to(closure/base))]=sha(item)
            runtime['immutable_trees']['server/'+base]=frozen
        snapshot(a.server_launcher,'server/paper.jar',kind='server')
        snapshot(a.server_candidate,'server/plugins/lod-server-support-paper.jar',kind='plugin',candidate=True)
        snapshot(a.server_fixture,'server/plugins/lss-rig-paper-concurrent.jar',kind='plugin',candidate=True)
        argv=prefix+['-jar','paper.jar','--nogui']
        config_path='server/plugins/LodServerSupport/lss-server-config.json'
    else:
        server_profile['components']=copy.deepcopy(profile['components'])
        lines=Path(a.server_classpath_file).read_text().splitlines()
        cp=lines[lines.index('-classpath')+1] if '-classpath' in lines else lines[0]
        # Require the caller's reviewed dependency-only closure; no output directories
        # containing shipping sources or common jars may replace the chosen candidate.
        paths=[Path(item) for item in cp.split(':')]
        if any('/build/classes/' in str(path) or '/build/resources/' in str(path) or '/common/build/libs/' in str(path) for path in paths):
            raise ValueError('server classpath must exclude project build outputs; select the candidate explicitly')
        entries=[snapshot(path,'server/libraries/input-'+str(i)+'.jar') for i,path in enumerate(paths)]
        entries.insert(0,snapshot(a.server_candidate,'server/libraries/lod-server-support-fabric.jar',kind='mod',candidate=True))
        entries.append(snapshot(a.server_fixture,'server/libraries/lss-rig-fabric-concurrent.jar',kind='mod',candidate=True))
        argv=prefix+['-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=official','-Dfabric.defaultMixinRemapType=static','-cp',':'.join(entries),'net.fabricmc.loader.impl.launch.knot.KnotServer','--nogui']
        config_path='server/config/lss-server-config.json'
    world_digest,world_files=stages(a.world_snapshot)
    runtime['world_digest']=world_digest;runtime['stage_files'].extend(world_files)
    argv.insert(1,'-Dlss.rig.seedSnapshotDigest='+world_digest)
    runtime['launches'].insert(0,{'id':'server','cwd':'server','argv':argv,'ready_marker':'LSS_RIG_SOURCES_READY','ready_timeout_seconds':240,'stop_stdin':'stop'})
    runtime['ready_conditions']=[{'launch_id':'server','marker':'LSS_RIG_SOURCE_WORKLOAD_ACTIVE','timeout_seconds':120}]
    endpoint=runtime['bind_endpoint']
    if endpoint!='127.0.0.1:25574':raise ValueError('expected the owned shared loopback endpoint 127.0.0.1:25574')
    runtime['generated_files']['server/eula.txt']='eula=true\n'
    runtime['generated_files']['server/server.properties']='server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\nlevel-type=minecraft:flat\nlevel-seed=rig-source-correctness-1\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\nmax-players=8\nallow-flight=true\nenforce-secure-profile=false\n'
    flat={'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains','features':False,'lakes':False,'structure_overrides':[]}
    runtime['generated_files']['server/server.properties']+='generator-settings='+json.dumps(flat,separators=(',',':'))+'\n'
    pins={'lodDistanceChunks':32,'enableChunkGeneration':True,'generationConcurrencyLimitGlobal':4,'generationConcurrencyLimitPerPlayer':1,'lodStore':'on','lodStoreBackfill':False}
    runtime['generated_files'][config_path]=json.dumps(pins)+'\n'
    scenario={'schema_version':1,'id':platform+'-four-client-source-correctness','version':2,'target_schema':2,'measured_workload':a.measured,'server_platform':platform,'checker':'concurrent-sources','timeout_seconds':900,'observe_seconds':550,'required_test_count':3,'assertions':['independent_target_delivery','actual_payload_source','bounded_debt_drain'],'human_reviews':[]}
    if a.measured:
        next(item for item in runtime['launches'] if item['id']=='server')['argv'].insert(1,'-Dlss.rig.measuredWorkload=true')
        scenario.update(observe_seconds=840,timeout_seconds=1100)
    write(out/'server-profile.json',server_profile)
    write(out/'profile.json',profile)
    runtime['server_profile']={'path':str(out/'server-profile.json'),'id':server_profile['id'],'profile_hash':digest(server_profile),'candidate_artifacts':candidates}
    runtime['client_profiles']=[{'role':client['id'],'path':str(out/'profile.json'),'id':profile['id'],'profile_hash':digest(profile),'candidate_artifacts':runtime['candidate_artifacts']} for client in clients]
    bounds={'held_sync':200,'held_gen':1,'send_queue':1024,'backlog':1024,'disk.pending':800,'generation.active':4,'store.queue':1024}
    scenario['debt_bounds_hash']=digest(bounds)
    runtime['generated_files']['debt-bounds.json']=json.dumps(bounds)+'\n'
    write(out/'runtime.json',runtime);write(out/'scenario.json',scenario)
    write(out/'debt-bounds.json',{'held_sync':200,'held_gen':1,'send_queue':1024,'backlog':1024,'disk.pending':800,'generation.active':4,'store.queue':1024})
    write(out/'workload-pins.json',{'platform':platform,'config':pins,'offers_close_seconds':720 if a.measured else 420,'drain_deadline_seconds':840 if a.measured else 540,'measurement_claim':False})
    print(json.dumps({'profile':str(out/'profile.json'),'runtime':str(out/'runtime.json'),'scenario':str(out/'scenario.json')}))
