#!/usr/bin/env python3
"""Place two real connected native subjects on native vehicles in an owned server."""
import argparse,json,time,math
from pathlib import Path
from contextlib import contextmanager
from rig import read,write,inside,regular,alive
from check_seated_run import make_proof

def shared_deadline(scenario, now):
 seconds=scenario.get('observe_seconds',scenario['timeout_seconds'])
 if isinstance(seconds,bool) or not isinstance(seconds,(int,float)) or not math.isfinite(seconds) or seconds<=10:
  raise ValueError('invalid bounded seated observation budget')
 # Controller is launched before the outer observation timer. Reserve five
 # seconds for focused capture/proof; setup and draw share the remaining time.
 return now+seconds-5

@contextmanager
def held_spyglass(driver):
 # Native right-mouse use on this exact owned display; release even on failed capture.
 driver.focus()
 driver.test.XTestFakeButtonEvent(driver.display,3,1,0);driver.x.XFlush(driver.display)
 try:yield
 finally:
  driver.verify_release()
  driver.test.XTestFakeButtonEvent(driver.display,3,0,0);driver.x.XFlush(driver.display)

def capture_pair(root,manifest,driver,deadline):
 from rig import sha
 client=root/'instances/lss-rig-client/minecraft/logs/latest.log'
 gate=root/'evidence/seated-healthy-captured.txt'
 if gate.exists():raise ValueError('healthy capture gate already exists')
 def wait(event,limit):
  while True:
   if time.monotonic()>=limit:raise ValueError('bounded seated capture deadline: '+event)
   if not alive(read(root/'owner.json')):raise ValueError('owner retired during seated capture')
   driver.verify()
   if client.stat().st_size>32*1024*1024:raise ValueError('client log bound')
   text=client.read_text(errors='replace')
   if '[WI9-FIXTURE] PREMISE_FAILED ' in text or '[WI9-FIXTURE] FAIL_OR_INCONCLUSIVE ' in text:
    raise ValueError('native seated premise/recovery failed')
   if event!='PASS_SAME_FRAME' and '[WI9-FIXTURE] INJECTED ' in text:
    raise ValueError('fault preceded healthy native capture')
   if '[WI9-FIXTURE] '+event+' ' in text:return
   time.sleep(.05)
 driver.key('1') # select the server-equipped hotbar spyglass
 with held_spyglass(driver):
  limit=min(deadline,time.monotonic()+9)
  wait('HEALTHY_READY',limit)
  time.sleep(.75) # settle the native spyglass projection, retaining name tags/HUD
  wait('HEALTHY_READY',limit)
  driver.capture('seated-healthy.png')
  healthy=dict(artifact='seated-healthy.png',artifact_sha256=sha(root/'evidence/seated-healthy.png'))
  if time.monotonic()>=limit:raise ValueError('healthy capture exceeded bounded native use')
  # Exclusive creation: a partial/failed capture can never open the fault gate.
  with gate.open('x') as stream:stream.write(manifest['run_id']+'\n')
  wait('PASS_SAME_FRAME',limit)
  time.sleep(.5)
  wait('PASS_SAME_FRAME',limit)
  driver.capture('seated-recovery.png')
  recovery=dict(artifact='seated-recovery.png',artifact_sha256=sha(root/'evidence/seated-recovery.png'))
  if time.monotonic()>=limit:raise ValueError('recovery capture exceeded bounded native use')
 receipt=dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],healthy=healthy,recovery=recovery,
              gate_sha256=sha(gate),mode='native-spyglass; healthy-before-fault; recovery-after-same-frame-proof')
 write(root/'evidence/seated-captures.json',receipt)
 return {'visual_render':healthy}

def run(root):
 manifest=read(root/'manifest.json');runtime=read(root/'runtime.json')
 if runtime.get('seated_capture_version')!=2:raise ValueError('fresh gated seated capture recipe required')
 if {x['id'] for x in runtime['launches']}!={'server','client','seated-target-a','seated-target-b','seated-controller'}:
  raise ValueError('exact owned observer/two-subject composition required')
 if not alive(read(root/'owner.json')):raise ValueError('owner not alive')
 deadline=shared_deadline(read(root/'scenario.json'),time.monotonic())
 log=root/'server.private.log'
 while True:
  if time.monotonic()>deadline:raise ValueError('actual subject join deadline')
  if log.exists() and log.stat().st_size>32*1024*1024:raise ValueError('server log bound')
  text=log.read_text(errors='replace') if log.exists() else ''
  client_logs=[root/name/'logs/latest.log' for name in ('seated-target-a','seated-target-b','instances/lss-rig-client/minecraft')]
  negotiated=all(path.is_file() and path.stat().st_size<=32*1024*1024 and any('Server session config received (protocol v20,' in line and 'enabled: true)' in line for line in path.read_text(errors='replace').splitlines()) for path in client_logs)
  if negotiated and all(name+' joined the game' in text for name in ('SeatedSubjectA','SeatedSubjectB')):break
  time.sleep(.1)
 queue=inside(root,'commands');receipts=[]
 def send(command,expected):
  if not alive(read(root/'owner.json')):raise ValueError('owner retired')
  if time.monotonic()>=deadline:raise ValueError('shared seated setup deadline')
  name='seated-'+str(len(receipts)).zfill(2)+'.json'
  write(queue/name,dict(launch_id='server',command=command,timeout_seconds=15,response_contains=expected))
  result=queue/'results'/name;limit=min(deadline,time.monotonic()+18)
  while not result.exists():
   if time.monotonic()>limit:raise ValueError('native command timeout')
   time.sleep(.05)
  observed=read(regular(result))
  if observed.get('status')!='response_observed':raise ValueError('native command not observed')
  receipts.append(dict(request=name,command=command,expected=expected,result=observed))
  write(root/'evidence/seated-native-commands.json',dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],receipts=receipts))
 send('gamemode creative @a','game mode to Creative Mode')
 send('time set day','Set the time to 1000')
 send('weather clear','Set the weather to clear')
 observer='@a[name=!SeatedSubjectA,name=!SeatedSubjectB]'
 send('tp '+observer+' 0 -60 0 0 0','Teleported')
 send('item replace entity '+observer+' hotbar.0 with minecraft:spyglass','Replaced a slot')
 for suffix,x,z in [('a',-2,144),('b',2,144)]:
  subject='SeatedSubject'+suffix.upper();tag='lss_seated_'+suffix
  send('tp '+subject+' '+str(x)+' -59 '+str(z)+' 180 0','Teleported '+subject)
  send('summon minecraft:boat '+str(x)+' -59 '+str(z)+' {Tags:["'+tag+'"],Invulnerable:1b,NoGravity:1b}','Summoned new Oak Boat')
  send('ride '+subject+' mount @e[tag='+tag+',limit=1]',subject+' started riding Oak Boat')
 from native_window import find
 from private_input import XInput
 window,identity=find(root,'instances/lss-rig-client/minecraft')
 driver=XInput(root,window,identity)
 try:artifacts=capture_pair(root,manifest,driver,deadline)
 finally:driver.close()
 make_proof(root,manifest,review_artifacts=artifacts)
 # Semantics remain separate from actual user review of the healthy native PNG.
 while True:time.sleep(1)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('run',type=Path);a=p.parse_args();run(a.run)
