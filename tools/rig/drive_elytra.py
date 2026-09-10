#!/usr/bin/env python3
"""Bounded real private input, native readback, and actual proxy-render phases."""
import argparse,json,time,contextlib
from pathlib import Path
from elytra_contract import PHASES,SUBJECT,OBSERVER,predicates,setup_commands,falling_commands,landing_command

def run(root):
 from rig import read,write,inside,regular,alive,sha
 from native_window import find
 from private_input import XInput
 from check_elytra_run import make_proof
 root=Path(root)
 if read(root/'runtime.json').get('elytra_ready')is not True:raise ValueError('draft Elytra recipe requires rebuilt fixture and maintained raw checker')
 manifest=read(root/'manifest.json');scenario=read(root/'scenario.json');deadline=time.monotonic()+scenario['timeout_seconds']-5
 journal={'run_id':manifest['run_id'],'run_hash':manifest['run_hash'],'phases':[],'native_commands':[],'inputs':[],'setup':[]};drivers=[];counter=0
 def save():write(root/'evidence/elytra-phases.json',journal)
 def wait(predicate):
  while True:
   if time.monotonic()>=deadline or not alive(read(root/'owner.json')):raise ValueError('bounded Elytra phase/owner deadline')
   value=predicate()
   if value:return value
   time.sleep(.05)
 def log(relative):
  path=inside(root,relative)
  if not path.exists():return ''
  if path.stat().st_size>32*1024*1024:raise ValueError('native log bound')
  return path.read_text(errors='replace')
 def rows(relative,tag):return [json.loads(s.split(tag+' ',1)[1]) for s in log(relative).splitlines() if tag+' 'in s]
 target_log='elytra-target/logs/latest.log';observer_log='instances/lss-rig-client/minecraft/logs/latest.log'
 def command(text,expected=None,phase=None,kind=None):
  nonlocal counter
  counter+=1;name='elytra-'+str(counter).zfill(3)+'.json';request={'launch_id':'server','command':text,'timeout_seconds':8,'response_contains':('[Server] '+expected if expected else None)};write(root/'commands'/name,request)
  value=wait(lambda:read(root/'commands/results'/name) if (root/'commands/results'/name).exists() else None)
  if value.get('status')!=('response_observed' if expected else 'submitted'):raise ValueError('actual native command failed: '+text)
  row={'request':name,'command':text,'result':value,'observed_ns':time.monotonic_ns()}
  if phase:row.update(phase=phase,kind=kind);journal['native_commands'].append(row)
  else:journal['setup'].append(row)
  save()
 def phase(name,capture=False):
  p={'id':name,'start_ns':time.monotonic_ns()};flying=name=='gliding';crouch=name=='crouched';ground=name not in ('falling','gliding')
  def ready():
   native=[r for r in rows(target_log,'LSS_ELYTRA_NATIVE') if r['nano_time']>=p['start_ns'] and r['survival'] and r['elytra'] and r['on_ground']==ground and r['fall_flying']==flying and r['crouching']==crouch]
   draw=[r for r in rows(observer_log,'LSS_ELYTRA_SUBMIT') if r['nano_time']>=p['start_ns'] and r['native_absent'] and r['elytra'] and r['fall_flying']==flying and r['crouching']==crouch]
   if len(native)<3 or len(draw)<3:return False
   if name=='falling' and any(r[-1]['y']>=r[0]['y']-.1 for r in (native,draw)):return False
   if name=='gliding' and any(sum((r[-1][k]-r[0][k])**2 for k in ('x','y','z'))<1 for r in (native,draw)):return False
   return True
  wait(ready)
  for kind,(text,marker) in predicates(name,manifest['run_id']).items():command(text,marker,name,kind)
  if name=='gliding':
   command(landing_command()) # Observer-only camera tracks the actual native subject.
   time.sleep(.2)
  if capture:
   from elytra_camera import framed
   observer_driver.key('F1');time.sleep(.2)
   framing_after=time.monotonic_ns()
   wait(lambda:framed(rows(observer_log,'LSS_ELYTRA_CAMERA'),rows(observer_log,'LSS_ELYTRA_SUBMIT'),framing_after,manifest['run_id'],rows(target_log,'LSS_ELYTRA_SESSION')[0]['uuid']))
   try:observer_driver.capture('elytra-gliding.png')
   finally:observer_driver.key('F1')
   journal['capture']={'artifact':'elytra-gliding.png','sha256':sha(root/'evidence/elytra-gliding.png'),'time_ns':time.monotonic_ns(),'window':observer_window,'process':observer_identity}
  p['end_ns']=time.monotonic_ns();journal['phases'].append(p);save()
 @contextlib.contextmanager
 def held(driver,key):
  code=driver.code(key);driver.focus();start=time.monotonic_ns();driver.test.XTestFakeKeyEvent(driver.display,code,1,0);driver.x.XFlush(driver.display)
  try:yield
  finally:
   driver.verify_release();driver.test.XTestFakeKeyEvent(driver.display,code,0,0);driver.x.XFlush(driver.display);journal['inputs'].append(dict(action='hold',key=key,start_ns=start,end_ns=time.monotonic_ns(),game_root='elytra-target',window=target_window,process=target_identity));save()
 try:
  wait(lambda:len(rows(target_log,'LSS_ELYTRA_SESSION'))==1 and len(rows(observer_log,'LSS_ELYTRA_SESSION'))==1)
  target_window,target_identity=wait(lambda:find(root,'elytra-target'));observer_window,observer_identity=wait(lambda:find(root,'instances/lss-rig-client/minecraft'))
  target_driver=XInput(root,target_window,target_identity);observer_driver=XInput(root,observer_window,observer_identity);drivers=[target_driver,observer_driver]
  for text in setup_commands():command(text)
  phase('equipped')
  with held(target_driver,'Shift_L'):phase('crouched')
  phase('standing_recovered')
  from elytra_phase_barrier import elevated
  for text in falling_commands():command(text)
  after_teleport=time.monotonic_ns()
  wait(lambda:elevated(rows(target_log,'LSS_ELYTRA_NATIVE'),rows(observer_log,'LSS_ELYTRA_SUBMIT'),after_teleport,manifest['run_id']))
  journal['falling_barrier']={'after_command_ns':after_teleport,'observed_ns':time.monotonic_ns(),'minimum_y_exclusive':140}
  save()
  phase('falling')
  start=time.monotonic_ns();target_driver.key('space',.08);journal['inputs'].append(dict(action='press',key='space',start_ns=start,end_ns=time.monotonic_ns(),game_root='elytra-target',window=target_window,process=target_identity));save()
  phase('gliding',True)
  from elytra_input import look_down
  capture_start=time.monotonic_ns();target_driver.click(480,270);wait(lambda:any(r.get('mouse_grabbed')is True and r['nano_time']>=capture_start for r in rows(target_log,'LSS_ELYTRA_NATIVE')));journal['inputs'].append(dict(action='capture-mouse',x=480,y=270,button=1,start_ns=capture_start,end_ns=time.monotonic_ns(),game_root='elytra-target',window=target_window,process=target_identity));save()
  start=time.monotonic_ns();look_down(target_driver);journal['inputs'].append(dict(action='relative-look',dx=0,dy=400,start_ns=start,end_ns=time.monotonic_ns(),game_root='elytra-target',window=target_window,process=target_identity));save()
  look_end=journal['inputs'][-1]['end_ns']
  wait(lambda:any(r['nano_time']>=look_end and r.get('mouse_grabbed')is True and r.get('pitch',0)>40 for r in rows(target_log,'LSS_ELYTRA_NATIVE')))
  # Genuine look input steers native flight into the unchanged bounded landing zone.
  command(landing_command())
  phase('landed')
  make_proof(root,manifest)
 except Exception as error:
  save();prior=read(root/'proof.json') if (root/'proof.json').exists() else {};proof={k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')};proof.update(ready=False,handshake=False,test_count=0,assertions={},failures=list(prior.get('failures',[]))+['Elytra controller: '+str(error)]);write(root/'proof.json',proof)
 finally:
  for driver in drivers:driver.close()
 while True:time.sleep(1)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('run',type=Path);run(p.parse_args().run)
