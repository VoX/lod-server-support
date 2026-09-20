#!/usr/bin/env python3
"""Materialize a two-client feasibility lock from cached Loom/Folia inputs.
Only offline fixture identities; immutable regular-file snapshots. Does not launch.
"""
import argparse
import json
from pathlib import Path
import shutil
import zipfile
from rig import sha, write, require_lock

def main():
    require_lock()
    p=argparse.ArgumentParser();p.add_argument('--source-worktree',required=True);p.add_argument('--java-home',required=True);p.add_argument('--fixture',required=True);p.add_argument('--fabric-candidate',required=True);p.add_argument('--paperclip',required=True);p.add_argument('--timing-agent',required=True);p.add_argument('--observer',required=True);p.add_argument('--target-class-sha256',required=True);p.add_argument('--range-runtime');p.add_argument('--output',required=True)
    p.add_argument('--paper-candidate',required=True)
    p.add_argument('--concurrent-client')
    p.add_argument('--observe-seconds',type=int,default=60)
    p.add_argument('--churn-after-seconds',type=int,default=360)
    p.add_argument('--slow-after-seconds',type=int,default=160)
    p.add_argument('--admission-after-seconds',type=int,default=200)
    p.add_argument('--world-snapshot')
    a=p.parse_args();source=Path(a.source_worktree);out=Path(a.output)
    out.mkdir(parents=True,exist_ok=False,mode=0o700);cache=out/'cache';cache.mkdir()
    profile={'schema_version':1,'id':'mc262-folia-two-client-feasibility','line':'26.2','platform':'fabric','route':'native','components':[{'uid':'net.minecraft','version':'26.2'},{'uid':'net.fabricmc.fabric-loader','version':'0.19.3'},{'uid':'java','version':'25'}],'artifacts':[],'capabilities':['folia-owning-region-feasibility'],'status':'unverified','limitations':['Feasibility or diagnostic only; candidate hashes are selected explicitly, not final matrix acceptance.','Exact tick observer is required; full workload acceptance remains separately checked.']}
    runtime={'backend':'linux-headless','private_display':True,'require_gpu':True,'bind_endpoint':'127.0.0.1:25574','client_endpoint':'127.0.0.1:25574','cache':{},'candidate_artifacts':[],'stage_files':[],'generated_files':{},'launches':[],'gpu_environment':{'GALLIUM_DRIVER':'d3d12','MESA_D3D12_DEFAULT_ADAPTER_NAME':'NVIDIA','ALSOFT_DRIVERS':'null'}}
    if a.world_snapshot:
        from world_snapshot import stages
        runtime['world_digest'],snapshot_files=stages(Path(a.world_snapshot))
        runtime['stage_files'].extend(snapshot_files)
    if a.range_runtime: runtime['range_runtime']=json.loads(Path(a.range_runtime).read_text())
    from assets import stages as asset_stages
    loom_cache=Path.home()/'.gradle/caches/fabric-loom'
    asset_index,asset_files,asset_hashes=asset_stages(loom_cache/'assets',loom_cache/'26.2/mojang_minecraft_info.json',loom_cache/'26.2/minecraft-client.jar','26.2')
    runtime['stage_files'].extend(asset_files)
    runtime['immutable_trees']={'assets':asset_hashes}
    def stage(path,target):
        runtime['stage_files'].append({'source':str(path.resolve()),'sha256':sha(path),'target':target})
    def snapshot(path,label,candidate=False):
        dest=cache/(label+'.jar')
        if path.is_dir():
            with zipfile.ZipFile(dest,'w') as z:
                for item in sorted(path.rglob('*')):
                    if item.is_symlink(): raise ValueError('symlink classpath input')
                    if item.is_file(): z.write(item,item.relative_to(path))
        else: shutil.copyfile(path,dest)
        checksum=sha(dest)
        import sys
        sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
        from catalog import inspect_jar, Invalid
        try: metadata=inspect_jar(dest)['metadata']
        except Invalid as error:
            if 'no recognized mod metadata' not in str(error): raise
            metadata={}
        artifact={'id':label,'version':'locked','file':dest.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':metadata,'enabled':True,'kind':'mod' if metadata else 'library'}
        if candidate:
            runtime['candidate_artifacts'].append(artifact)
            stage(dest,'artifacts/'+dest.name)
        else:
            profile['artifacts'].append(artifact)
        runtime['cache'][checksum]=str(dest)
        return dest
    args=(source/'fabric/build/loom-cache/argFiles/runSoakClient').read_text().splitlines()
    classpath=args[args.index('-classpath')+1].split(':')
    classpath=[item for item in classpath if not any(Path(item).is_relative_to(source/sub) for sub in ('fabric/build/classes','fabric/build/resources','common/build/libs'))]
    classpath.insert(0,a.fabric_candidate)
    libs=[]
    # Combine class/resources dirs into independent jars; keep source ordering.
    for index,item in enumerate(classpath):
        if not Path(item).exists(): raise ValueError('missing cached client input: '+item)
        dest=snapshot(Path(item),f'client-{index:03d}',candidate=index==0)
        libs.append('{run}/artifacts/'+dest.name)
    server=source/'paper/build/run/folia-soak-server'
    for base in ('libraries','versions','cache'):
        frozen={}
        for item in sorted((server/base).rglob('*')):
            if item.is_symlink(): raise ValueError('symlink server cache')
            if item.is_file():
                stage(item,'server/'+str(item.relative_to(server)))
                frozen[str(item.relative_to(server/base))]=sha(item)
        runtime.setdefault('immutable_trees',{})['server/'+base]=frozen
    stage(Path(a.paperclip),'server/folia.jar')
    stage(Path(a.fixture),'server/plugins/lss-rig-regions.jar')
    stage(Path(a.timing_agent),'server/lss-rig-folia-timing.jar')
    stage(Path(a.observer),'server/lss-rig-observer.jar')
    plugin=Path(a.paper_candidate)
    stage(plugin,'server/plugins/lod-server-support-paper.jar')
    from catalog import inspect_jar
    server_candidates=[]
    for candidate in (plugin,Path(a.fixture)):
        checksum=sha(candidate);metadata=inspect_jar(candidate)['metadata']
        server_candidates.append({'id':metadata['paper']['name'],'version':str(metadata['paper']['version']),'file':candidate.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':metadata,'enabled':True,'kind':'plugin'})
        runtime['cache'][checksum]=str(candidate.resolve())
    runtime['generated_files'].update({'server/eula.txt':'eula=true\n','server/server.properties':'server-ip=127.0.0.1\nserver-port=25574\nonline-mode=false\nlevel-type=minecraft:flat\nlevel-seed=rig-feasibility-1\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\nmax-players=8\nallow-flight=true\nenforce-secure-profile=false\n','server/config/paper-global.yml':'_version: 31\nthreaded-regions:\n  scheduler: EDF\n  threads: 4\n'})
    flat={'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains','features':False,'lakes':False,'structure_overrides':[]}
    runtime['generated_files']['server/server.properties']+='generator-settings='+json.dumps(flat,separators=(',',':'))+'\n'
    java=str(Path(a.java_home)/'bin/java')
    runtime['launches'].append({'id':'server','cwd':'server','argv':[java,'-javaagent:{run}/server/lss-rig-folia-timing.jar','-Dlss.rig.observerJar={run}/server/lss-rig-observer.jar','-Dlss.rig.foliaClassSha256='+a.target_class_sha256,'-Xms512M','-Xmx2G','-Dlss.rig.runId={run_id}','-Dlss.rig.evidence={run}/evidence','-jar','folia.jar','--nogui'],'ready_marker':'Done (','ready_timeout_seconds':180,'stop_stdin':'stop'})
    letters='ABCD' if a.concurrent_client else 'AB'
    if a.concurrent_client:
        stage(Path(a.concurrent_client),'artifacts/lss-rig-concurrent-client.jar')
        client_fixture=Path(a.concurrent_client);checksum=sha(client_fixture);metadata=inspect_jar(client_fixture)['metadata']
        runtime['candidate_artifacts'].append({'id':metadata['fabric']['id'],'version':metadata['fabric']['version'],'file':'lss-rig-concurrent-client.jar','sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':metadata,'enabled':True,'kind':'mod'})
        runtime['cache'][checksum]=str(client_fixture.resolve())
        libs.append('{run}/artifacts/lss-rig-concurrent-client.jar')
        runtime['launches'][0]['argv'][1:1]=['-Dlss.rig.workload=true','-Dlss.rig.offerSeconds='+str(max(1,a.observe_seconds-120)),'-Dlss.rig.churnAfterSeconds='+str(a.churn_after_seconds),'-Dlss.rig.slowAfterSeconds='+str(a.slow_after_seconds),'-Dlss.rig.admissionAfterSeconds='+str(a.admission_after_seconds)]
        runtime['generated_files']['server/plugins/LodServerSupport/lss-server-config.json']=json.dumps({'lodDistanceChunks':32,'enableChunkGeneration':False,'generationConcurrencyLimitGlobal':4,'generationConcurrencyLimitPerPlayer':1})
        runtime['ready_conditions']=[{'launch_id':'server','marker':'LSS_RIG_WORKLOAD_READY','timeout_seconds':120}]
    for letter in letters:
        game='client-'+letter
        runtime['generated_files'][game+'/options.txt']='maxFps:30\nrenderDistance:3\nsimulationDistance:5\npauseOnLostFocus:false\nenableVsync:false\nguiScale:2\nonboardAccessibility:false\n'
        runtime['launches'].append({'id':game,'cwd':game,'argv':[java,'-Xms256M','-Xmx1500M','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=official','-Dfabric.defaultMixinRemapType=static','-Dlss.soak=true','-cp',':'.join(libs),'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','RigSubject'+letter,'--version','26.2','--accessToken','0','--gameDir','{run}/'+game,'--assetsDir','{run}/assets','--assetIndex',asset_index,'--quickPlayMultiplayer','{endpoint}','--width','960','--height','540']})
        if a.concurrent_client:
            runtime['launches'][-1]['argv'][1:1]=['-Dlss.rig.runId={run_id}','-Dlss.rig.subject=RigSubject'+letter,'-Dlss.rig.evidence={run}/evidence','-Dlss.rig.endpoint={endpoint}']
    scenario={'schema_version':1,'id':'folia-two-client-feasibility','version':1,'timeout_seconds':180,'observe_seconds':60,'checker':'folia-regions','required_test_count':2,'assertions':['two_simultaneous_lss_sessions','owning_region_overlap'],'human_reviews':[]}
    if a.concurrent_client:
        scenario.update(id='folia-four-client-workload-diagnostic',observe_seconds=a.observe_seconds,timeout_seconds=a.observe_seconds+180,required_test_count=4,assertions=['four_simultaneous_lss_sessions','independent_target_delivery'])
        scenario.pop('checker')  # Diagnostic data is not a full performance acceptance proof.
    from rig import digest
    server_profile={'schema_version':1,'id':'mc262-folia-server-feasibility','line':'26.2','platform':'folia','route':'native','components':[{'uid':'net.minecraft','version':'26.2'},{'uid':'java','version':'25'}],'artifacts':[],'capabilities':['owning-region-timing'],'status':'unverified','limitations':['Offline cached Folia26.2-7 closure; upstream fetch URLs are not resolved by this preparer.']}
    for index,entry in enumerate(runtime['stage_files']):
        if not entry['target'].startswith(('server/libraries/','server/versions/','server/cache/')) and entry['target']!='server/folia.jar':continue
        path=Path(entry['source']);checksum=entry['sha256']
        server_profile['artifacts'].append({'id':f'server-{index:03d}','version':'locked','file':path.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':{},'enabled':True,'kind':'server' if entry['target']=='server/folia.jar' else 'library'})
        runtime['cache'][checksum]=str(path)
    write(out/'server-profile.json',server_profile)
    runtime['server_profile']={'path':str(out/'server-profile.json'),'id':server_profile['id'],'profile_hash':digest(server_profile),'candidate_artifacts':server_candidates}
    runtime['client_profiles']=[{'role':'client-'+letter,'path':str(out/'profile.json'),'id':profile['id'],'profile_hash':digest(profile),'candidate_artifacts':runtime['candidate_artifacts']} for letter in letters]
    write(out/'profile.json',profile);write(out/'runtime.json',runtime);write(out/'scenario.json',scenario)
    print(json.dumps({'profile':str(out/'profile.json'),'runtime':str(out/'runtime.json'),'scenario':str(out/'scenario.json')}))
if __name__=='__main__':main()
