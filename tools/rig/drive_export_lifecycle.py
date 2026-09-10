"""Bounded controller: fixture owns native actions; supervisor owns process teardown."""
import sys,time
from pathlib import Path
from rig import read,inside,alive,write
from check_export_lifecycle import make_proof
root=Path(sys.argv[1]);deadline=time.monotonic()+420
while time.monotonic()<deadline:
 if not alive(read(root/'owner.json')):raise SystemExit('export supervisor ended')
 path=inside(root,'instances/lss-rig-client/minecraft/logs/latest.log')
 if path.exists():
  if path.stat().st_size>32*1024*1024:raise ValueError('fixture log bound')
  text=path.read_text(errors='replace')
  if '[EXPORT-FIXTURE] PASS run=' in text or '[EXPORT-FIXTURE] FAIL_' in text:
   make_proof(root,read(root/'manifest.json'))
   while time.monotonic()<deadline:time.sleep(.2)
   raise SystemExit('supervisor failed to teardown within controller deadline')
 time.sleep(.1)
write(root/'evidence/export-controller-error.json',{'error':'bounded deadline'})
raise SystemExit('export lifecycle deadline')
