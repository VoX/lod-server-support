#!/usr/bin/env python3
"""Exercise actual far-player send-admission refusal and recovery on an owned server."""
import argparse,json,time,math,re
from pathlib import Path
from rig import read,write,inside,regular,alive
from check_send_admission_run import make_proof

def shared_deadline(scenario, now):
 seconds=scenario.get('observe_seconds',scenario['timeout_seconds'])
 if isinstance(seconds,bool) or not isinstance(seconds,(int,float)) or not math.isfinite(seconds) or seconds<=10:
  raise ValueError('invalid bounded seated observation budget')
 # Controller is launched before the outer observation timer. Reserve five
 # seconds for focused capture/proof; setup and draw share the remaining time.
 return now+seconds-5

def baseline_epochs(text):
 return [int(value) for value in re.findall(r'\[WI6-FIXTURE\] BASELINE_ROSTER tick=\d+ epoch=(\d+) ',text)]

def fresh_baseline(text,previous):
 """Observe the post-toggle native roster and subsequent accepted update."""
 rows=[]
 for match in re.finditer(r'\[WI6-FIXTURE\] (BASELINE_ROSTER|BASELINE_UPDATE) tick=\d+ epoch=(\d+)(?: |$)',text,re.MULTILINE):
  rows.append((match.group(1),int(match.group(2))))
 rosters=[epoch for kind,epoch in rows if kind=='BASELINE_ROSTER' and epoch>previous]
 if not rosters:return False
 latest=rosters[-1]
 return any(kind=='BASELINE_UPDATE' and epoch==latest for kind,epoch in rows[rows.index(('BASELINE_ROSTER',latest))+1:])

def run(root):
 manifest=read(root/'manifest.json');runtime=read(root/'runtime.json')
 if {x['id'] for x in runtime['launches']}!={'server','client','seated-target-a','seated-target-b','admission-controller'}:
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
  name='admission-'+str(len(receipts)).zfill(2)+'.json'
  write(queue/name,dict(launch_id='server',command=command,timeout_seconds=15,response_contains=expected))
  result=queue/'results'/name;limit=min(deadline,time.monotonic()+18)
  while not result.exists():
   if time.monotonic()>limit:raise ValueError('native command timeout')
   time.sleep(.05)
  observed=read(regular(result))
  if observed.get('status')!='response_observed':raise ValueError('native command not observed')
  receipts.append(dict(request=name,command=command,expected=expected,result=observed))
  write(root/'evidence/admission-native-commands.json',dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],receipts=receipts))
 send('gamemode creative @a','game mode to Creative Mode')
 send('lsslod set farPlayers on','farPlayers = on')
 send('tp @a[name=!SeatedSubjectA,name=!SeatedSubjectB] 0 -60 0 0 0','Teleported')
 send('tp SeatedSubjectA 0 -60 192 180 0','Teleported SeatedSubjectA')
 send('tp SeatedSubjectB 0 -60 16 0 0','Teleported SeatedSubjectB')
 while not fresh_baseline(log.read_text(errors='replace'),-1):
  if time.monotonic()>deadline:raise ValueError('initial native admission baseline deadline')
  if log.stat().st_size>32*1024*1024:raise ValueError('native log bound')
  time.sleep(.05)
 previous_epoch=max(baseline_epochs(log.read_text(errors='replace')))
 send('lsslod set farPlayers off','farPlayers = off')
 send('lsslod set farPlayers on','farPlayers = on')
 def wait_for(markers,predicate=lambda text:True):
  while True:
   if time.monotonic()>deadline:raise ValueError('native admission observation deadline')
   if log.stat().st_size>32*1024*1024:raise ValueError('native log bound')
   text=log.read_text(errors='replace')
   if '[WI6-FIXTURE] FAIL_OR_INCONCLUSIVE' in text:
    make_proof(root,manifest);raise ValueError('native admission fixture failed')
   if all(marker in text for marker in markers) and predicate(text):return
   time.sleep(.05)
 wait_for(['[WI6-FIXTURE] BASELINE_UPDATE ', '[WI6-FIXTURE] UNAFFECTED_BASELINE '],lambda text:fresh_baseline(text,previous_epoch))
 marker=inside(root,'server/wi6-hold-clear')
 with marker.open('x') as stream:stream.write(manifest['run_id']+'\n')
 wait_for(['[WI6-FIXTURE] ARMED '])
 send('lsslod set farPlayers off','farPlayers = off')
 wait_for(['count=3 epoch=', '[WI6-FIXTURE] UNAFFECTED_CLEAR_ACCEPTED '])
 send('lsslod set farPlayers on','farPlayers = on')
 wait_for(['[WI6-FIXTURE] PASS_SEND_ADMISSION '])
 make_proof(root,manifest)
 while True:time.sleep(1)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('run',type=Path);a=p.parse_args();run(a.run)
