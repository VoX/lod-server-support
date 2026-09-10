"""Recompute WI6 evidence from native owned server/client logs on collection."""
from pathlib import Path
import re,hashlib,uuid
from check_send_admission import check

def participant_bindings(runtime,raw,clients,root):
 errors=[];facts={}
 launches=runtime.get('launches',[])
 if len({x.get('id') for x in launches})!=len(launches):errors.append('duplicate native launch roles')
 roles={x.get('id'):x for x in launches}
 argv=roles.get('server',{}).get('argv',[])
 def prop(name):
  values=[x.split('=',1)[1] for x in argv if x.startswith('-D'+name+'=')]
  if len(values)!=1:errors.append('missing/duplicate server property '+name);return None
  return values[0]
 if prop('lss.wi6.enabled')!='true':errors.append('native admission fixture disabled')
 if prop('lss.rig.serverRoot') not in ('{run}/server',str(Path(root)/'server')):errors.append('native fixture root differs')
 ready=[line for line in raw.splitlines() if '[WI6-FIXTURE] READY ' in line]
 fields=dict(re.findall(r'(\w+)=([^ ]+)',ready[0])) if len(ready)==1 else {}
 for key,role,expected_name in [('observer','client',None),('subject','seated-target-a','SeatedSubjectA'),('unaffected','seated-target-b','SeatedSubjectB')]:
  name=prop('lss.wi6.'+key)
  if not isinstance(name,str) or not re.fullmatch('[A-Za-z0-9_]{1,16}',name):errors.append('invalid participant name '+key);continue
  identity=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3))
  if expected_name and name!=expected_name:errors.append('server subject property differs from owned role')
  if fields.get(key)!=name or fields.get(key+'_uuid')!=identity:errors.append('READY participant identity differs: '+key)
  if name+' joined the game' not in raw:errors.append('native participant join absent: '+key)
  body=clients.get(role,'')
  enabled=bool(re.search(r'Server session config received \(protocol v20, LOD distance: \d+ chunks, enabled: true\)',body))
  consumer=None
  if expected_name:
   args=roles.get(role,{}).get('argv',[])
   positions=[i for i,value in enumerate(args) if value=='--username']
   actual=args[positions[0]+1] if len(positions)==1 and positions[0]+1<len(args) else None
   consumer='LSS_SEATED_TARGET_CONSUMER registered=true' in body
   if actual!=name or args.count('-Dlss.rig.seatedTarget=true')!=1:errors.append('direct participant launch differs: '+key)
   if not consumer:errors.append('direct native consumer absent: '+key)
  if not enabled:errors.append('participant enabled v20 absent: '+key)
  observations=[line for line in body.splitlines() if 'Server session config received' in line or 'LSS_SEATED_TARGET_CONSUMER' in line]
  facts[key]=dict(name=name,uuid=identity,role=role,handshake=enabled,consumer_registered=consumer,observations=observations)
 if len({fact['uuid'] for fact in facts.values()})!=3:errors.append('three distinct participants required')
 return facts,errors

def inspect(run,manifest):
 from rig import read,regular,inside,digest
 root=Path(run);runtime=read(root/'runtime.json');scenario=read(root/'scenario.json')
 if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:raise ValueError('send-admission inputs changed')
 if scenario.get('checker')!='send-admission':raise ValueError('send-admission checker not selected')
 def text(name):
  p=regular(inside(root,name))
  if p.stat().st_size>32*1024*1024:raise ValueError('native log size bound')
  return p.read_text(errors='replace')
 raw=text('server.private.log');rows=[line[line.index('[WI6-FIXTURE]'):] for line in raw.splitlines() if '[WI6-FIXTURE]' in line]
 result=check('\n'.join(rows))
 clients={role:text(name) for role,name in [('client','instances/lss-rig-client/minecraft/logs/latest.log'),('seated-target-a','seated-target-a/logs/latest.log'),('seated-target-b','seated-target-b/logs/latest.log')]}
 participants,participant_errors=participant_bindings(runtime,raw,clients,root)
 result['errors'].extend(participant_errors)
 for value in participants.values():value['observations_sha256']=digest(value['observations'])
 handshake=len(participants)==3 and all(value['handshake'] for value in participants.values())
 passed=not result['errors'];result.update(passed=passed,assertions={k:passed for k in result['assertions']})
 return dict(result,run_id=manifest['run_id'],run_hash=manifest['run_hash'],handshake=handshake,observations=rows,observations_sha256=digest(rows),participants=participants)

def make_proof(root,manifest):
 from rig import read,write,sha
 root=Path(root);report=inspect(root,manifest);path=root/'evidence/send-admission.json';write(path,report)
 prior=read(root/'proof.json') if (root/'proof.json').exists() else {}
 failures=prior.get('failures',[])
 if not isinstance(failures,list):failures=['malformed prior fixture failures']
 proof={key:manifest[key] for key in ('run_id','run_hash','profile_hash','scenario_hash')}
 proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=4 if report['passed'] else 0,assertions=report['assertions'],failures=list(dict.fromkeys([str(x) for x in failures]+report['errors'])),send_admission_report=report,evidence={'send-admission.json':sha(path)},reviews={})
 write(root/'proof.json',proof);return proof

def check_report(proof,manifest,root):
 from rig import read,regular,inside,sha
 try:
  path=regular(inside(Path(root)/'evidence','send-admission.json'))
  if sha(path)!=proof.get('evidence',{}).get('send-admission.json'):return ['send-admission report hash changed']
  actual=inspect(root,manifest)
  if read(path)!=actual or proof.get('send_admission_report')!=actual:return ['send-admission native report mismatch']
  return actual['errors']
 except (ValueError,OSError,KeyError,TypeError) as error:return ['send-admission report invalid: '+str(error)]
