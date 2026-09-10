"""Run only under the exclusive Java slot after testClasses compilation."""
import argparse,hashlib,json,subprocess,tempfile,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java',required=True);p.add_argument('--classpath',required=True);a=p.parse_args()
def write(path,value):
 tmp=path.with_suffix('.tmp');tmp.write_text(json.dumps(value));tmp.replace(path)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
results=[]
for mode in ['valid','missing','wrong','late','unrequested','overflow','pending','livewriter','markerwritefailure']:
 with tempfile.TemporaryDirectory()as d:
  root=Path(d);e=root/'evidence';e.mkdir();m=dict(run_id='R',run_hash='H',profile_hash='P',scenario_hash='S');write(root/'manifest.json',m)
  write(e/'xaero-map-stop-client',dict(**m,phase='all_raw_checks_passed',requested_ns=time.monotonic_ns()))
  start=time.monotonic();proc=subprocess.Popen([a.java,'-cp',a.classpath,'dev.vox.lssfixture.xaeromap.NativeCloseProcessControl',d,mode],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  try:
   deadline=start+3
   receipt=e/'xaero-map-native-close.json'
   while not receipt.exists() and proc.poll()is None and time.monotonic()<deadline:time.sleep(.01)
   if mode in ['unrequested','overflow','pending','livewriter']:
    assert proc.wait(timeout=3)==2;assert not receipt.exists();assert (e/'xaero-map-native-close-failed.json').exists()
   else:
    assert receipt.exists() and proc.poll()is None
    r=json.loads(receipt.read_text());assert r['writer_joined'] and r['pending']==0 and not r['overflow'];assert r['stream_sha256']==sha(e/'xaero-map.jsonl')
    initial=receipt.read_bytes();time.sleep(.15);assert proc.poll()is None
    proof=dict(**m,ready=True,handshake=True,test_count=5,failures=[],assertions=dict.fromkeys(['fresh_body_received','bridge_write','boundary_continuity','shading_valid','save_race_safe'],True),map_report=dict(run_id='R',run_hash='H',closed=True,passed=True,errors=[]),evidence={name:sha(e/name)for name in ['xaero-map-native-close.json','xaero-map.jsonl','xaero-map-stop-client']})
    if mode in ['wrong','markerwritefailure']:proof['run_hash']='wrong'
    if mode=='markerwritefailure':(e/'xaero-map-native-close-failed.json.tmp').mkdir()
    if mode in ['valid','wrong','markerwritefailure']:write(root/'proof.json',proof)
    code=proc.wait(timeout=12);assert code==(0 if mode=='valid'else 2)
    if mode!='markerwritefailure':assert receipt.read_bytes()==initial
    else:
     assert 'error'in json.loads(receipt.read_text());assert receipt.read_bytes()!=initial
    if mode not in ['valid','markerwritefailure']:
     failure=e/'xaero-map-native-close-failed.json';assert failure.exists();before=failure.read_bytes()
     if mode=='late':write(root/'proof.json',proof);assert failure.read_bytes()==before
   assert time.monotonic()-start<14
   results.append(dict(mode=mode,passed=True))
  finally:
   if proc.poll()is None:proc.kill();proc.wait()
print(json.dumps(results,indent=2))
