"""Run-owned native stop request after independently checked map semantics."""
from pathlib import Path
import time
KEYS={'fresh_body_received','bridge_write','boundary_continuity','shading_valid','save_race_safe'}

def request_stop(root,manifest,assertions):
 from rig import write
 if set(assertions)!=KEYS or any(value is not True for value in assertions.values()):raise ValueError('native stop requires all five raw checks')
 path=Path(root)/'evidence/xaero-map-stop-client'
 if path.exists():raise ValueError('native stop request already exists')
 value=dict(**{key:manifest[key]for key in ('run_id','run_hash','profile_hash','scenario_hash')},phase='all_raw_checks_passed',requested_ns=time.monotonic_ns())
 write(path,value);return value

def validate_stop(request,rows,manifest,oracle):
 from check_xaero_map import inspect
 if request.get('run_id')!=manifest['run_id'] or request.get('run_hash')!=manifest['run_hash'] or request.get('phase')!='all_raw_checks_passed':raise ValueError('native stop request identity/phase mismatch')
 stamp=request.get('requested_ns')
 if type(stamp)is not int or stamp<=0:raise ValueError('native stop request timestamp missing')
 stops=[r for r in rows if r.get('event')=='native_client_stop_requested']
 if len(stops)!=1:raise ValueError('exactly one actual native client stop required')
 stop=stops[0]
 if stop.get('client_thread')is not True or stop.get('request_run_id')!=manifest['run_id'] or stop.get('request_path')!='xaero-map-stop-client' or stop.get('requested_ns')!=stamp or not stamp<=stop['time_ns']<rows[-1]['time_ns']:raise ValueError('actual native owner stop does not match request/closure')
 flags,errors=inspect([r for r in rows if r['time_ns']<=stamp],oracle,manifest['run_id'],allow_open=True)
 if errors or not all(flags.values()):raise ValueError('native stop preceded complete raw map checks')
 return True


def validate_close(root,manifest,rows,publication=False):
 from rig import read,regular,sha
 root=Path(root);base=root/'evidence'
 if (base/'xaero-map-native-close-failed.json').exists():raise ValueError('native close handshake failed')
 receipt=read(regular(base/'xaero-map-native-close.json'))
 request=read(regular(base/'xaero-map-stop-client'))
 for key in ('run_id','run_hash','profile_hash','scenario_hash'):
  if receipt.get(key)!=manifest[key] or request.get(key)!=manifest[key]:raise ValueError('native close identity mismatch')
 if receipt.get('requested_ns')!=request.get('requested_ns'):raise ValueError('native close request mismatch')
 if receipt.get('writer_joined')is not True or receipt.get('overflow')is not False or type(receipt.get('pending'))is not int or receipt['pending']!=0:raise ValueError('native close writer failed')
 stamp=receipt.get('time_ns');deadline=receipt.get('proof_deadline_ns')
 if type(stamp)is not int or type(deadline)is not int or deadline-stamp!=10_000_000_000 or not rows or rows[-1].get('event')!='observer_closed' or not request['requested_ns']<rows[-1]['time_ns']<=stamp:raise ValueError('native close order mismatch')
 if receipt.get('stream_sha256')!=sha(regular(base/'xaero-map.jsonl')) or receipt.get('request_sha256')!=sha(regular(base/'xaero-map-stop-client')):raise ValueError('native close frozen bytes changed')
 if publication and time.monotonic_ns()>=deadline:raise ValueError('native close proof publication deadline expired')
 return receipt
