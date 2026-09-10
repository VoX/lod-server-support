"""Bind server-smoke acceptance to the native server participant and both fresh clients."""
import json,re
from pathlib import Path

def export_binding(run,runtime,manifest,scenario,candidate_target,artifact):
 from rig import read,regular,digest
 run=Path(run);reference=runtime.get('server_profile',{});profile=read(regular(run/'participants/server.json'))
 if digest(profile)!=reference.get('profile_hash') or profile['id']!=reference.get('id'):raise ValueError('native server profile binding changed')
 matches=[r for r in manifest.get('participants',[]) if r.get('role')=='server']
 if len(matches)!=1 or matches[0].get('profile_hash')!=digest(profile):raise ValueError('native server participant absent from collected manifest')
 platform=profile.get('platform');expected='server/'+('plugins' if platform=='paper' else 'mods')+'/lod-server-support-'+str(platform)+'.jar'
 if platform not in ('fabric','paper','neoforge') or candidate_target!=expected:raise ValueError('server smoke export must select the actual server candidate')
 checksum=artifact(candidate_target)
 if not any(r.get('sha256')==checksum and platform in r.get('metadata',{}) for r in reference.get('candidate_artifacts',[])):raise ValueError('server candidate metadata/hash not bound to participant')
 if scenario.get('id')!='server-smoke' or scenario.get('version')!=2:raise ValueError('canonical server-smoke v2 required')
 candidates={};fixtures={}
 for phase in ('first','second'):
  path='smoke-'+phase+'/mods/lod-server-support-fabric.jar';candidates[path]=artifact(path)
  path='smoke-'+phase+'/mods/lss-server-smoke-client.jar';fixtures[path]=artifact(path)
 if len(set(candidates.values()))!=1 or len(set(fixtures.values()))!=1:raise ValueError('fresh clients used different candidate/fixture bytes')
 command=json.loads(runtime['generated_files']['server-smoke-command.json'])
 memory={name:[arg for arg in value['argv'] if arg.startswith(('-Xms','-Xmx','-XX:'))] for name,value in {'server':command['server'],**command['clients']}.items()}
 return dict(execution_route='native-server-smoke',scenario_version=2,server_profile_id=profile['id'],server_profile_hash=digest(profile),server_platform=platform,server_candidate_target=candidate_target,client_candidate_artifacts=candidates,client_fixture_artifacts=fixtures,native_launch_memory_flags=memory)

def validate_binding(record,profiles,require):
 from catalog import digest
 inputs=record['run_manifest'];server=profiles.get(inputs.get('server_profile_id'),{})
 require(inputs.get('execution_route')=='native-server-smoke' and inputs.get('scenario_version')==2,'server-smoke requires canonical native v2 evidence')
 require(server.get('platform') in ('fabric','paper','neoforge') and inputs.get('server_platform')==server.get('platform'),'server-smoke native platform/profile missing')
 require(inputs.get('server_profile_hash')==digest(server),'stale native server profile evidence')
 platform=server.get('platform');target='server/'+('plugins' if platform=='paper' else 'mods')+'/lod-server-support-'+str(platform)+'.jar'
 require(inputs.get('candidate_target')==target and inputs.get('server_candidate_target')==target,'server-smoke record selected a client or foreign server candidate')
 for key,filename in [('client_candidate_artifacts','lod-server-support-fabric.jar'),('client_fixture_artifacts','lss-server-smoke-client.jar')]:
  values=inputs.get(key,{})
  require(isinstance(values,dict) and set(values)=={'smoke-'+phase+'/mods/'+filename for phase in ('first','second')},'missing both fresh native client bindings: '+key)
  require(all(isinstance(v,str) and re.fullmatch('[0-9a-f]{64}',v) for v in values.values()) and len(set(values.values()))==1,'native client artifact identity differs: '+key)
