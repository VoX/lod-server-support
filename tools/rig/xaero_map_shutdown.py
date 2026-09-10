"""Run-owned native stop request after independently checked map semantics."""
from pathlib import Path
import time
KEYS={'fresh_body_received','bridge_write','boundary_continuity','shading_valid','save_race_safe'}

def request_stop(root,manifest,assertions):
 from rig import write
 if set(assertions)!=KEYS or any(value is not True for value in assertions.values()):raise ValueError('native stop requires all five raw checks')
 path=Path(root)/'evidence/xaero-map-stop-client'
 if path.exists():raise ValueError('native stop request already exists')
 value=dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],phase='all_raw_checks_passed',requested_ns=time.monotonic_ns())
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
