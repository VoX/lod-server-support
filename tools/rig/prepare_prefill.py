"""Derive a fresh native Paper-only construction recipe with no LSS extension."""
import argparse,copy,json,sys
from pathlib import Path
from rig import read,write,sha,digest,require_lock
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from catalog import inspect_jar


def prepare(source,fixture,destination):
    source=Path(source);destination=Path(destination);fixture=Path(fixture).resolve()
    runtime=copy.deepcopy(read(source/'runtime.json'));profile=read(runtime['server_profile']['path'])
    if profile.get('platform')!='paper' or profile.get('line')!='26.2' or profile.get('route')!='native':raise ValueError('explicit native Paper26.2 required')
    metadata=inspect_jar(fixture)['metadata']
    if metadata.get('paper',{}).get('name')!='LssRigNativePrefill':raise ValueError('native prefill fixture identity required')
    checksum=sha(fixture);candidate={'id':'LssRigNativePrefill','version':'1.0.0-test-only','kind':'plugin','enabled':True,'file':fixture.name,'sha256':checksum,'source':'local-cache:sha256:'+checksum,'metadata':metadata}
    runtime['launches']=[l for l in runtime['launches'] if l['id']=='server']
    if len(runtime['launches'])!=1:raise ValueError('one existing owned Paper launcher required')
    launch=runtime['launches'][0]
    launch['argv']=[a for a in launch['argv'] if not a.startswith(('-javaagent:','-Dlss.rig.measuredWorkload='))]
    launch.update(ready_marker='LSS_RIG_PREFILL_READY',ready_timeout_seconds=360)
    runtime.update(client_profiles=[],candidate_artifacts=[candidate],ready_conditions=[],private_display=False,backend='linux-headless')
    runtime.pop('require_gpu',None);runtime.pop('world_digest',None)
    runtime['stage_files']=[s for s in runtime['stage_files'] if s['target'].startswith('server/') and not s['target'].startswith('server/plugins/')]
    if any(s['target'].startswith('server/world/') for s in runtime['stage_files']):raise ValueError('prefill requires fresh disposable world')
    runtime['stage_files'].append({'source':str(fixture),'target':'server/plugins/lss-rig-native-prefill.jar','sha256':checksum})
    runtime['generated_files']={k:v for k,v in runtime['generated_files'].items() if k.startswith('server/') and not k.startswith('server/plugins/')}
    runtime['immutable_trees']={k:v for k,v in runtime.get('immutable_trees',{}).items() if k.startswith('server/')}
    runtime['cache'][checksum]=str(fixture)
    destination.mkdir(parents=True,exist_ok=False,mode=0o700)
    write(destination/'profile.json',profile)
    runtime['server_profile']={'id':profile['id'],'path':str((destination/'profile.json').resolve()),'profile_hash':digest(profile),'candidate_artifacts':[candidate]}
    scenario={'schema_version':1,'id':'paper-native-prefill','version':1,'execution_route':'source-prefill','checker':'source-prefill','requires_handshake':False,'timeout_seconds':420,'observe_seconds':3,'required_test_count':19044,'assertions':['native_saved_full_domain'],'human_reviews':[]}
    write(destination/'runtime.json',runtime);write(destination/'scenario.json',scenario)
    return destination

if __name__=='__main__':
    require_lock();p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('fixture');p.add_argument('destination');a=p.parse_args();print(prepare(a.source,a.fixture,a.destination))
