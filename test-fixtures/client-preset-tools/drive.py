"""Actual owned command route; launch only after native DirectConnect and inspected world frame."""
import argparse,json,time,sys,hashlib
from pathlib import Path
import checks
from verify import verify
from checks import allowed_change,fresh_feedback,verify_exports,verify_tool_identity,verify_active_rig
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--joined-layout',required=True,type=Path);p.add_argument('--repo',required=True,type=Path);a=p.parse_args();r=a.root.resolve();tree=verify_tool_identity(r,a.repo,{'entrypoint':__file__,'checks.py':checks.__file__,'verify.py':verify.__code__.co_filename});sys.path.insert(0,str(tree/'tools/rig'))
from rig import read,write,inside,regular,sha,alive
from native_window import find
from private_input import XInput
from ui_snapshot_wait import observe_after_action
verify_active_rig(tree,{name:sys.modules[name] for name in ('rig','native_window','private_input','ui_snapshot_wait')})
manifest=read(r/'manifest.json');layout=read(a.joined_layout)
if layout['run_hash']!=manifest['run_hash'] or sha(regular(inside(r/'evidence',layout['inspected_screenshot'])))!=layout['inspected_screenshot_sha256']:raise ValueError('actual inspected joined-screen binding required')
window,owner=find(r,'instances/lss-rig-client/minecraft');d=XInput(r,window,owner);g=r/'instances/lss-rig-client/minecraft';cfg=g/'config/lss-client-config.json';log=g/'logs/latest.log';e=r/'evidence/standalone-presets';e.mkdir(exist_ok=False);original=cfg.read_bytes();baseline=json.loads(original);assert baseline['receiveServerLods']is False and baseline['enableXaeroMapBridge']is False
receipt={k:manifest[k]for k in ['run_id','run_hash','profile_hash','scenario_hash']};receipt.update(steps=[],snapshots={},configs={},screens={},assertions={},status='running');deadline=time.monotonic()+600;action_ms=0;blocker=cfg.with_name(cfg.name+'.tmp');blocker_owned=False
def save():write(e/'receipt.json',receipt)
def wait(f):
 while time.monotonic()<deadline:
  if not alive(read(r/'owner.json')):raise ValueError('owned supervisor ended')
  v=f()
  if v:return v
  time.sleep(.1)
 raise ValueError('native command deadline')
def config(label):
 raw=cfg.read_bytes();(e/(label+'-config.json')).write_bytes(raw);receipt['configs'][label]=json.loads(raw);return json.loads(raw)
def command(text,feedback=None):
 global action_ms
 offset=log.stat().st_size;d.key('t');time.sleep(.4);d.text(text);time.sleep(.15);d.key('Return');action_ms=time.time_ns()//1000000
 if feedback:wait(lambda:fresh_feedback(log.read_bytes()[offset:].decode(errors='replace'),feedback))
 receipt['steps'].append({'command':text,'feedback':feedback,'log_offset':offset,'action_completed_ms':action_ms});save();time.sleep(.2)
def capture(name):
 d.focus();time.sleep(.2);d.capture('standalone-presets/'+name+'.png');receipt['screens'][name]={'artifact':'standalone-presets/'+name+'.png','sha256':sha(e/(name+'.png')),'window':window,'process':owner};save()
def export(label,receive,writes=None,button=False):
 def request(end):
  before={x.name for x in (g/'lss-diagnostics').glob('*.json')}
  if button:d.click(*layout['status_export'])
  else:command('/lss diagnostics export','Diagnostics exported:') # Path-bearing feedback handled below instead.
  while time.monotonic()<end:
   files=[x for x in (g/'lss-diagnostics').glob('*.json')if x.name not in before]
   if len(files)>1:raise ValueError('ambiguous export')
   if files:return files[0].read_bytes()
   time.sleep(.1)
  raise ValueError('export deadline')
 def retain(i,raw):(e/(label+'-export-'+str(i)+'.json')).write_bytes(raw)
 raw,timing=observe_after_action(request,action_ms,receive,retain,timeout=8);snapshot=json.loads(raw);verify_exports(snapshot,receive,writes)
 if snapshot.get('connected')is not True:raise ValueError('native connected state missing')
 receipt['snapshots'][label]=dict(snapshot=snapshot,timing=timing);save();return snapshot
# Diagnostics path contains a dynamic suffix. Require native [CHAT] prefix, never arbitrary echoed user text.
original_feedback=fresh_feedback
def fresh_feedback(text,expected):
 if expected in ('Diagnostics exported:','Client preset preview:'):return any(('[CHAT] '+expected+' ')in line for line in text.splitlines())
 return original_feedback(text,expected)
try:
 config('baseline');command('/lss status');time.sleep(.7);capture('standalone-status');export('standalone',False,button=True);d.click(*layout['status_done']);time.sleep(.3);receipt['assertions']['standalone_export']=True
 for mode,patch in [('map-only',{'receiveServerLods':True}),('map-only-xaero-writes',{'receiveServerLods':True,'enableXaeroMapBridge':True})]:
  command('/lss preset '+mode,'Client preset preview:');time.sleep(.5);allowed_change(baseline,config(mode+'-preview'),{});capture(mode+'-preview')
  command('/lss preset apply','Client preset applied; saved.');allowed_change(baseline,config(mode+'-applied'),patch);export(mode+'-applied',True,True if len(patch)==2 else None)
  command('/lss preset undo','Last client preset settings restored; saved.');allowed_change(baseline,config(mode+'-undone'),{});export(mode+'-undone',False,False if len(patch)==2 else None)
  receipt['assertions']['map_only'if mode=='map-only'else'explicit_xaero_writes']=True
 command('/lss preset map-only-xaero-writes','Client preset preview:');time.sleep(.5);allowed_change(baseline,config('failed-preview'),{});failed_disk=cfg.read_bytes();(e/'failed-before-bytes.json').write_bytes(failed_disk);assert not blocker.exists();blocker.mkdir();blocker_owned=True
 command('/lss preset apply','Client preset applied; not saved — check client log.');assert cfg.read_bytes()==failed_disk;export('applied-not-saved',True,True);capture('applied-not-saved');blocker.rmdir();blocker_owned=False
 command('/lss preset undo','Last client preset settings restored; saved.');allowed_change(baseline,config('restored'),{});export('restored',False,False)
 if cfg.read_bytes()!=original:cfg.write_bytes(original) # Restore formatting only after effective/configured values independently match.
 assert cfg.read_bytes()==original;receipt['assertions'].update(applied_not_saved=True,unrelated_preserved=True,restored=True);receipt['status']='raw-controls-complete';save()
 verify(r,tree)
 write(e/'pending-screen-review.json',{'run_hash':manifest['run_hash'],'artifact':'standalone-presets/standalone-status.png','sha256':sha(e/'standalone-status.png'),'status':'raw controls passed; actual screen inspection required before finalization'})
except Exception as error:receipt['status']='failed';receipt['error']=str(error);save();raise
finally:
 if blocker_owned:blocker.rmdir()
 d.close()
