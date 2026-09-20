#!/usr/bin/env python3
"""Compose exact Folia/client dependencies with a checked preseeded source snapshot."""
import argparse
import copy
from pathlib import Path
import sys
from rig import require_lock,read,write,sha,digest
from world_snapshot import stages
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from catalog import inspect_jar


def main():
    require_lock()
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('folia-runtime','client-profile','source-fixture','region-fixture','world-snapshot','output'):
        p.add_argument('--'+name,required=True)
    p.add_argument('--measured',action='store_true')
    a=p.parse_args();runtime=copy.deepcopy(read(a.folia_runtime));profile=read(a.client_profile)
    server=read(runtime['server_profile']['path'])
    if server['platform']!='folia' or server['line']!='26.2' or profile['platform']!='fabric' or profile['line']!='26.2':
        raise ValueError('explicit native26.2 Fabric/Folia composition required')
    if {r['id'] for r in runtime['launches']}!={'server','client-A','client-B','client-C','client-D'}:
        raise ValueError('exact four-client owning-region recipe required')
    from source_client_natives import apply as isolate_source_client_natives
    isolate_source_client_natives(runtime)
    world_digest,snapshot=stages(Path(a.world_snapshot))
    if runtime.get('world_digest'):raise ValueError('recipe must not already stage another snapshot')
    runtime['world_digest']=world_digest;runtime['stage_files'].extend(snapshot)
    candidates=runtime['server_profile']['candidate_artifacts']
    for argument,plugin_id,target in ((a.region_fixture,'LssRigRegions','server/plugins/lss-rig-regions.jar'),
                                       (a.source_fixture,'LssRigFoliaSources','server/plugins/lss-rig-folia-sources.jar')):
        path=Path(argument).resolve();metadata=inspect_jar(path)['metadata']
        if metadata.get('paper',{}).get('name')!=plugin_id:raise ValueError('fixture plugin identity mismatch')
        candidates[:]=[item for item in candidates if item.get('metadata',{}).get('paper',{}).get('name')!=plugin_id]
        checksum=sha(path)
        candidates.append({'id':plugin_id,'kind':'plugin','version':'1.0.0-test-only','metadata':metadata,'file':path.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'enabled':True})
        runtime['cache'][checksum]=str(path)
        runtime['stage_files']=[item for item in runtime['stage_files'] if item['target']!=target]
        runtime['stage_files'].append({'source':str(path),'target':target,'sha256':checksum})
    launch=next(item for item in runtime['launches'] if item['id']=='server')
    remove=('-Dlss.rig.workload=','-Dlss.rig.offerSeconds=','-Dlss.rig.churnAfterSeconds=','-Dlss.rig.slowAfterSeconds=','-Dlss.rig.admissionAfterSeconds=')
    launch['argv']=[item for item in launch['argv'] if not item.startswith(remove)]
    launch['argv'][1:1]=['-Dlss.rig.regionOnly=true','-Dlss.rig.seedSnapshotDigest='+world_digest]
    if a.measured:launch['argv'].insert(1,'-Dlss.rig.measuredWorkload=true')
    launch.update(ready_marker='LSS_RIG_SOURCES_READY',ready_timeout_seconds=180)
    runtime['ready_conditions']=[{'launch_id':'server','marker':'LSS_RIG_SOURCE_WORKLOAD_ACTIVE','timeout_seconds':180}]
    import json
    runtime['generated_files']['server/plugins/LodServerSupport/lss-server-config.json']=json.dumps({
        'lodDistanceChunks':32,'enableChunkGeneration':True,'generationConcurrencyLimitGlobal':4,
        'generationConcurrencyLimitPerPlayer':1,'lodStore':'on','lodStoreBackfill':False})+'\n'
    out=Path(a.output);out.mkdir(parents=True,exist_ok=False,mode=0o700)
    write(out/'profile.json',profile);write(out/'server-profile.json',server)
    runtime['server_profile'].update(path=str((out/'server-profile.json').resolve()),profile_hash=digest(server))
    for reference in runtime['client_profiles']:reference['path']=str((out/'profile.json').resolve())
    bounds={'held_sync':200,'held_gen':1,'send_queue':1024,'backlog':1024,'disk.pending':800,'generation.active':4,'store.queue':1024}
    runtime['generated_files']['debt-bounds.json']=json.dumps(bounds)+'\n'
    write(out/'runtime.json',runtime)
    write(out/'scenario.json',{'schema_version':1,'id':'folia-four-client-source-correctness','version':2,'target_schema':2,'measured_workload':a.measured,'checker':'concurrent-sources','debt_bounds_hash':digest(bounds),'timeout_seconds':1100 if a.measured else 900,
                              'observe_seconds':840 if a.measured else 550,'required_test_count':4,'assertions':['independent_target_delivery','actual_payload_source','bounded_debt_drain','owning_region_overlap'],'human_reviews':[]})
    write(out/'debt-bounds.json',{'held_sync':200,'held_gen':1,'send_queue':1024,'backlog':1024,'disk.pending':800,'generation.active':4,'store.queue':1024})
    print(json.dumps({'profile':str(out/'profile.json'),'runtime':str(out/'runtime.json'),'scenario':str(out/'scenario.json')}))


if __name__=='__main__':main()
