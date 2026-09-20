"""Recompute Elytra proof from native logs, command receipts, exact artifacts and phases."""
import json,re,uuid,hashlib,struct,zlib
from check_client_ui import verify_png
from pathlib import Path
from elytra_contract import PHASES,SUBJECT,OBSERVER,predicates,setup_commands,falling_commands,landing_command
from check_elytra_strict import check

def inspect(root,manifest):
 from rig import read,regular,inside,digest,sha
 root=Path(root);runtime=read(root/'runtime.json');scenario=read(root/'scenario.json');errors=[]
 if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:raise ValueError('Elytra input identity changed')
 if scenario.get('checker')!='elytra' or scenario.get('version')!=2:raise ValueError('strict Elytra v2 checker required')
 if runtime.get('elytra_ready')is not True:raise ValueError('draft Elytra recipe is not runnable')
 contract=runtime['elytra_contract'];staged={r['target']:r['sha256'] for r in manifest['run_manifest']['staged_inputs']}
 for name,expected in contract['artifacts'].items():
  if staged.get(name)!=expected or sha(regular(inside(root,name)))!=expected:errors.append('wrong candidate/fixture artifact: '+name)
 target_refs=[x for x in runtime.get('client_profiles',[]) if x.get('role')=='elytra-target']
 participant_refs=[x for x in manifest.get('participants',[]) if x.get('role')=='elytra-target']
 profile=read(regular(root/'participants/elytra-target.json'))
 if len(target_refs)!=1 or len(participant_refs)!=1 or digest(profile)!=contract.get('target_profile_hash') or target_refs[0].get('profile_hash')!=digest(profile) or participant_refs[0].get('profile_hash')!=digest(profile):errors.append('independent native target dependency profile differs')
 if contract.get('observer')!=OBSERVER or contract.get('subject')!=SUBJECT:errors.append('subject composition differs')
 expected_uuid=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+SUBJECT).encode()).digest(),version=3))
 launches={x['id']:x for x in runtime['launches']};argv=launches.get('elytra-target',{}).get('argv',[])
 if '--username' not in argv or argv[argv.index('--username')+1]!=SUBJECT or '-Dlss.rig.elytraTarget=true' not in argv:errors.append('actual target launch binding differs')
 def text(name):
  p=regular(inside(root,name))
  if p.stat().st_size>32*1024*1024:raise ValueError('native evidence log too large')
  return p.read_text(errors='replace')
 observer=text('instances/lss-rig-client/minecraft/logs/latest.log');target=text('elytra-target/logs/latest.log');server=text('server.private.log')
 def rows(body,marker):return [json.loads(line.split(marker+' ',1)[1]) for line in body.splitlines() if marker+' 'in line]
 for role,body,name in [('observer',observer,OBSERVER),('target',target,SUBJECT)]:
  sessions=rows(body,'LSS_ELYTRA_SESSION')
  if len(sessions)!=1 or sessions[0].get('run_id')!=manifest['run_id'] or sessions[0].get('role')!=role or sessions[0].get('connection_id')!=manifest['run_id']+'-'+role or sessions[0].get('player')!=name or sessions[0].get('protocol')!=20:errors.append('missing/foreign/replaced actual session: '+role)
  if 'Server session config received (protocol v20,' not in body or not re.search(r'Server session config received \(protocol v20,[^\n]*enabled: true\)',body):errors.append('enabled actual v20 absent: '+role)
  if len(re.findall(re.escape('handshake received from '+name+' (protocol v20,'),server))!=1:errors.append('server handshake absent/replaced: '+role)
 if 'LSS_ELYTRA_TARGET_CONSUMER registered=true' not in target:errors.append('real target consumer absent')
 journal=read(regular(root/'evidence/elytra-phases.json'))
 if journal.get('run_id')!=manifest['run_id'] or journal.get('run_hash')!=manifest['run_hash']:errors.append('phase journal identity mismatch')
 phases=journal['phases'];result=check(rows(observer,'LSS_ELYTRA_SUBMIT'),rows(target,'LSS_ELYTRA_NATIVE'),phases,manifest['run_id'],expected_uuid);errors+=result['errors']
 server_bytes=(root/'server.private.log').read_bytes();native_commands=journal['native_commands']
 for phase in phases:
  for kind,(command,marker) in predicates(phase['id'],manifest['run_id']).items():
   found=[r for r in native_commands if r.get('phase')==phase['id'] and r.get('kind')==kind]
   if len(found)!=1:errors.append('native phase readback missing: '+phase['id']+'/'+kind);continue
   row=found[0];receipt=read(regular(inside(root,'commands/results/'+row['request'])))
   if row.get('command')!=command or row.get('result')!=receipt or receipt.get('status')!='response_observed' or receipt.get('launch_id')!='server':errors.append('native command receipt differs');continue
   offset=receipt.get('log_offset')
   if type(offset)is not int or offset<0 or offset>=len(server_bytes) or not re.search(r'\[Server\] '+re.escape(marker)+r'\s*$',server_bytes[offset:offset+256*1024].decode(errors='replace'),re.M):errors.append('native predicate did not emit actual server response')
   if not phase['start_ns']<=row.get('observed_ns',-1)<=phase['end_ns']:errors.append('native predicate outside phase')
 setup=journal.get('setup',[])
 if [r.get('command') for r in setup]!=setup_commands()+falling_commands()+[landing_command(),landing_command()]:errors.append('unexpected setup/action; direct flight setters or post-flight target teleport forbidden')
 by_phase={p['id']:p for p in phases}
 for row in setup:
  receipt=read(regular(inside(root,'commands/results/'+row['request'])))
  if row.get('result')!=receipt or receipt.get('status')!='submitted':errors.append('setup native submission receipt differs')
 looks=[x for x in journal.get('inputs',[]) if x.get('action')=='relative-look']
 if len(looks)!=1 or looks[0].get('dx')!=0 or looks[0].get('dy')!=400 or not by_phase['gliding']['end_ns']<looks[0].get('start_ns',-1)<=looks[0].get('end_ns',-1)<by_phase['landed']['start_ns']:errors.append('native landing look input missing/outside causal interval')
 captures=[x for x in journal.get('inputs',[]) if x.get('action')=='capture-mouse']
 if len(captures)!=1 or captures[0].get('button')!=1 or captures[0].get('x')!=480 or captures[0].get('y')!=270 or not looks or not by_phase['gliding']['end_ns']<captures[0].get('start_ns',-1)<=captures[0].get('end_ns',-1)<looks[0].get('start_ns',-1):errors.append('native mouse capture input missing/outside interval')
 if not looks or not any(looks[0]['end_ns']<=r.get('nano_time',-1)<by_phase['landed']['start_ns'] and r.get('uuid')==expected_uuid and r.get('mouse_grabbed')is True and r.get('pitch',0)>40 for r in rows(target,'LSS_ELYTRA_NATIVE')):errors.append('actual native downward pitch after input missing')
 owners=read(regular(root/'processes.json'))
 actions=journal.get('inputs',[])
 if not any(r.get('key')=='Shift_L' and r.get('action')=='hold' for r in actions) or not any(r.get('key')=='space' and r.get('action')=='press' for r in actions):errors.append('real private crouch/flight input absent')
 holds=[r for r in actions if r.get('key')=='Shift_L' and r.get('action')=='hold']
 presses=[r for r in actions if r.get('key')=='space' and r.get('action')=='press']
 if len(holds)!=1 or len(presses)!=1:errors.append('exact single native crouch and glide inputs required')
 elif not (holds[0]['start_ns']<=by_phase['crouched']['start_ns']<by_phase['crouched']['end_ns']<=holds[0]['end_ns']<by_phase['standing_recovered']['start_ns'] and by_phase['falling']['end_ns']<presses[0]['start_ns']<presses[0]['end_ns']<by_phase['gliding']['start_ns']):errors.append('input/observed phase causality differs')
 for row in actions:
  if row.get('process') not in owners:errors.append('input was not on the owned direct target process')
  if row.get('game_root')!='elytra-target' or not isinstance(row.get('process'),dict) or not row.get('window'):errors.append('private input native identity absent')
 capture=journal.get('capture',{});glide=next((p for p in phases if p['id']=='gliding'),{})
 if not glide.get('start_ns',1)<=capture.get('time_ns',-1)<=glide.get('end_ns',0):errors.append('capture outside actual gliding phase')
 from elytra_camera import framed
 if not framed(rows(observer,'LSS_ELYTRA_CAMERA'),rows(observer,'LSS_ELYTRA_SUBMIT'),capture.get('time_ns',0)-500_000_000,manifest['run_id'],expected_uuid,capture.get('time_ns',0)):errors.append('capture lacks time-aligned native camera framing')
 try:
  image=regular(inside(root/'evidence',capture['artifact']))
  if sha(image)!=capture['sha256']:errors.append('capture bytes changed')
  if image.stat().st_size>64*1024*1024:raise ValueError('observer capture exceeds byte limit')
  data=image.read_bytes();verify_png(data)
  width,height=struct.unpack('>II',data[16:24])
  if width<640 or height<360:errors.append('actual observer PNG missing or too small')
 except (KeyError,ValueError,OSError,struct.error,zlib.error):errors.append('actual observer capture absent')
 return dict(passed=not errors,errors=errors,run_id=manifest['run_id'],run_hash=manifest['run_hash'],phases=result['phases'],handshake=not any('session' in e or 'handshake' in e or 'v20' in e for e in errors),capture=capture,artifacts=contract['artifacts'])

def make_proof(root,manifest):
 from rig import write,read,sha
 root=Path(root);report=inspect(root,manifest);path=root/'evidence/elytra-report.json';write(path,report);prior=read(root/'proof.json') if (root/'proof.json').exists() else {};failures=list(prior.get('failures',[]))+report['errors'];scenario=read(root/'scenario.json');proof={k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')}
 proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=6 if report['passed'] else 0,assertions={k:report['passed'] for k in scenario['assertions']},failures=failures,elytra_report={'artifact':'elytra-report.json','artifact_sha256':sha(path)},evidence={'elytra-report.json':sha(path)})
 if report['capture']:
  proof['review_artifacts']={'visual_render':{'artifact':report['capture']['artifact'],'artifact_sha256':report['capture']['sha256']}}
 write(root/'proof.json',proof);return proof

def check_report(proof,manifest,scenario,root):
 from rig import read,regular,inside,sha
 if root is None:return ['Elytra requires retained native evidence']
 try:
  report=proof['elytra_report'];path=regular(inside(Path(root)/'evidence',report['artifact']));actual=inspect(root,manifest)
  if sha(path)!=report['artifact_sha256'] or read(path)!=actual:return ['Elytra report changed or differs from raw recomputation']
  return actual['errors']
 except (ValueError,OSError,KeyError,TypeError) as e:return ['invalid Elytra evidence: '+str(e)]
