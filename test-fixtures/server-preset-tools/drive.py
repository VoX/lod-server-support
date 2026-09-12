import json,sys,time
from pathlib import Path
root=Path(sys.argv[1]);tools=Path(sys.argv[2]);sys.path[:0]=[str(tools),str(tools.parent/'compat')]
from rig import read,write,sha
from server_control_smoke import Driver,exercise
from verify import verify
from conservative_native import run as run_numeric
manifest=read(root/'manifest.json');runtime=read(root/'runtime.json');contract=runtime['preset_contract'];report={k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')};report['receipts']={};failure=None
try:
 deadline=time.monotonic()+30
 while not (root/'commands').is_dir():
  if time.monotonic()>deadline:raise TimeoutError('owned command queue absent')
  time.sleep(.1)
 for mode in contract['modes']:
  if mode=='conservative-numeric':
   _,receipt=run_numeric(root,root/contract['config'])
   report['receipts'][mode]=str(receipt.relative_to(root))
   continue
  driver=Driver(root,root/contract['config'],'lss',mode)
  try:exercise(driver,mode,root/'evidence/previous-stage-receipt.json' if mode=='after-restart' else None)
  except Exception as error:driver.finish(error);raise
  else:driver.finish()
  report['receipts'][mode]=str((driver.output/'receipt.json').relative_to(root))
 write(root/'evidence/preset-report.json',report)
 verify(root,tools)
except Exception as error:failure=str(error);report['error']=failure;write(root/'evidence/preset-report.json',report)
proof={k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')};proof.update(ready=failure is None,handshake=failure is None,test_count=len(contract['modes']) if failure is None else 0,assertions={mode:failure is None for mode in contract['modes']},failures=[failure] if failure else [])
proof['evidence']={str(p.relative_to(root/'evidence')):sha(p) for p in (root/'evidence').rglob('*') if p.is_file() and (p.name=='preset-report.json' or p.parent.name.startswith('settings-'))}
write(root/'proof.json',proof)
(root/'evidence/stop-first').touch()
# Supervisor owns termination; avoid a process-exited-before-proof race.
while True:time.sleep(1)
