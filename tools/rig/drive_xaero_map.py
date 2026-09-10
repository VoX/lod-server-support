#!/usr/bin/env python3
"""Own native commands/input and retain observations; never invent human review."""
from pathlib import Path
import argparse,time,re
from rig import read,write,regular,alive,sha
from check_xaero_map_run import load_rows,make_proof
from check_xaero_map import inspect
from xaero_map_terrain import build,height

def run(root):
 m=read(root/'manifest.json');s=read(root/'scenario.json');deadline=time.monotonic()+s['timeout_seconds']-8
 def check_time():
  if time.monotonic()>=deadline or not alive(read(root/'owner.json')):raise ValueError('owned map controller deadline/retirement')
 def wait(predicate):
  while True:
   check_time();value=predicate()
   if value:return value
   time.sleep(.05)
 game=root/'instances/lss-rig-client/minecraft';log=game/'logs/latest.log'
 def logged():
  if not log.is_file():return ''
  if log.stat().st_size>32*1024*1024:raise ValueError('native log bound')
  return log.read_text(errors='replace')
 wait(lambda:'Server session config received (protocol v20,'in logged())
 match=re.search(r'Setting user: ([A-Za-z0-9_]+)',logged())
 if not match:raise ValueError('actual native observer identity absent')
 subject=match[1];terrain=build(subject);queue=root/'commands';receipts=[]
 def command(text,expected=None):
  check_time();name='map-'+str(len(receipts)).zfill(4)+'.json'
  write(queue/name,dict(launch_id='server',command=text,timeout_seconds=30,response_contains=expected))
  receipt=wait(lambda:read(queue/'results'/name)if(queue/'results'/name).is_file()else None)
  if receipt.get('status')!=('response_observed'if expected else'submitted'):raise ValueError('native command failed: '+text)
  receipts.append(dict(request=name,command=text,result=receipt,observed_ns=time.monotonic_ns()))
  write(root/'evidence/xaero-map-commands.json',dict(run_id=m['run_id'],run_hash=m['run_hash'],receipts=receipts))
  return receipts[-1]['observed_ns']
 started=time.monotonic_ns()
 for text in terrain['prepare_commands']:command(text,'Saved the game'if text=='save-all flush'else None)
 ready=time.monotonic_ns();oracle=dict(run_id=m['run_id'],run_hash=m['run_hash'],targets=terrain['targets'])
 for t in oracle['targets']:t.update(seed_started_ns=started,seed_ready_ns=ready,native_visit_started_ns=2**63-1)
 def save_oracle():write(root/'evidence/xaero-map-oracle.json',oracle)
 save_oracle()
 def rows():return load_rows(root)if(root/'evidence/xaero-map.jsonl').is_file()else[]
 def initial():
  data=rows()
  for t in oracle['targets']:
   p=tuple(t['chunk'])
   if not any((r.get('chunk_x'),r.get('chunk_z'))==p and r.get('event')=='native_texture'and r.get('native_writer')is False and [v[0]for v in r.get('pixels',[])]==t['initial_floor_y']for r in data):return False
  return True
 wait(initial)
 from native_window import find
 from private_input import XInput
 window,identity=find(root,'instances/lss-rig-client/minecraft');driver=XInput(root,window,identity)
 try:
  driver.focus();opened=time.monotonic_ns();driver.key('m')
  wait(lambda:any(r.get('event')=='map_screen_rendered' and r.get('frame')==2 and r['time_ns']>opened for r in rows()))
  driver.capture('xaero-map-boundary.png');driver.key('Escape')
 finally:driver.close()
 visit_start=time.monotonic_ns()
 command(terrain['native_visit_command'],'Teleported '+subject)
 for t in oracle['targets']:t['native_visit_started_ns']=visit_start
 save_oracle()
 def matched():
  flags,errors=inspect(rows(),oracle,m['run_id'],allow_open=True)
  return flags['boundary_continuity']and flags['shading_valid']
 wait(matched)
 command(terrain['return_command'],'Teleported '+subject)
 # A run-owned arm file only enables the delay at an actual native saver call.
 (root/'evidence/xaero-map-arm-save').touch(exist_ok=False)
 start=wait(lambda:next((r for r in rows()if r.get('event')=='save_pause_begin'),None))
 selected=next((t for t in oracle['targets']if(t['chunk'][0]//32,t['chunk'][1]//32)==(start.get('region_x'),start.get('region_z'))),None)
 if selected is None:raise ValueError('native saver selected no prepared target')
 for t in oracle['targets']:
  t['edited_during_save']=t is selected
  if t is not selected:t['final_block']=t['initial_block']
 save_oracle()
 x0=selected['chunk'][0]*16
 for x in range(x0,x0+16):command(f'fill {x} 64 256 {x} {height(x)} 271 diamond_block')
 command('save-all flush','Saved the game')
 wait(lambda:all(inspect(rows(),oracle,m['run_id'],allow_open=True)[0].values()))
 artifacts={'visual_render':{'artifact':'xaero-map-boundary.png','artifact_sha256':sha(root/'evidence/xaero-map-boundary.png')}}
 make_proof(root,m,review_artifacts=artifacts,require_closed=False)
 # Supervisor sees provisional semantic proof and closes native writers; its
 # registered postcleanup adapter must rebuild and enforce final closed evidence.
 while True:time.sleep(1)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('run',type=Path);a=p.parse_args();run(a.run)
