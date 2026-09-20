"""One immutable run identity consumes a selected intent before native execution."""
from pathlib import Path

def context(root,runtime):
 from experiment import read,digest,ORDER
 root=Path(root).resolve();registration=read(root/'registration.json')
 if registration.get('claim_protocol')!=1 or registration.get('experiment_root')!=str(root):raise ValueError('experiment claim root/protocol differs')
 if runtime.get('measurement_experiment_root')!=str(root):raise ValueError('runtime experiment locator missing/differs')
 binding=runtime.get('measurement_context',{});phase=binding.get('phase');slot=binding.get('slot')
 if phase not in ('calibration','measured') or type(slot)is not int or not 0<=slot<(3 if phase=='calibration' else 6):raise ValueError('selected intent missing')
 if binding!=read(root/f'{phase}-{slot}-intent.json') or binding.get('registration_sha256')!=digest(registration):raise ValueError('selected intent changed')
 if binding.get('experiment_id')!=registration['experiment_id'] or binding.get('arm')!=('baseline' if phase=='calibration' else ORDER[slot]):raise ValueError('selected experiment/arm differs')
 if phase=='measured' and binding.get('calibration_sha256')!=digest(read(root/'frozen.json')):raise ValueError('selected frozen calibration changed')
 return root,registration,binding

def expected(run_root,runtime,manifest):
 from experiment import digest
 return {'schema_version':1,'intent_sha256':digest(runtime['measurement_context']),'run_root':str(Path(run_root).resolve()),
         **{k:manifest[k] for k in ('run_id','run_hash','runtime_hash')}}

def verify(root,run_root):
 from rig import read,regular,digest
 root=Path(root).resolve();run_root=Path(run_root).resolve();runtime=read(regular(run_root/'runtime.json'));m=read(regular(run_root/'manifest.json'))
 _,_,b=context(root,runtime)
 if digest(runtime)!=m.get('runtime_hash') or digest(m.get('run_manifest',{}))!=m.get('run_hash'):raise ValueError('claimed run inputs changed')
 value=read(regular(root/f"{b['phase']}-{b['slot']}-claim.json"))
 if value!=expected(run_root,runtime,m):raise ValueError('selected run claim differs')
 return value

def acquire(run_root,runtime,manifest):
 from experiment import create_file,require_no_failure,read
 if not runtime.get('measurement_context') and not runtime.get('measurement'):return
 locator=runtime.get('measurement_experiment_root')
 if not isinstance(locator,str) or not Path(locator).is_absolute():raise ValueError('measured runtime requires canonical experiment locator')
 root,_,b=context(locator,runtime);require_no_failure(root)
 if manifest.get('status')!='created' or (Path(run_root)/'launch-journal.json').exists() or (Path(run_root)/'owner.json').exists():raise ValueError('selected native attempt cannot restart')
 if (root/f"{b['phase']}-{b['slot']}-result.json").exists():raise ValueError('selected slot already completed')
 path=root/f"{b['phase']}-{b['slot']}-claim.json";value=expected(run_root,runtime,manifest)
 try:create_file(path,value)
 except FileExistsError:
  if read(path)!=value:raise ValueError('selected intent already claimed by another run')
 verify(root,run_root)

def verify_report(root,report):
 from experiment import read
 from assemble_run import assemble_run
 from review_state import ownership_errors
 root=Path(root).resolve();b=report.get('measurement_context',{});phase=b.get('phase');slot=b.get('slot')
 if phase not in ('calibration','measured') or type(slot)is not int or not 0<=slot<(3 if phase=='calibration' else 6):raise ValueError('report intent absent')
 claim=read(root/f'{phase}-{slot}-claim.json');run_root=Path(claim['run_root']);verify(root,run_root)
 errors=ownership_errors(run_root,read(run_root/'runtime.json'))
 if errors:raise ValueError('; '.join(errors))
 if assemble_run(run_root)!=report:raise ValueError('report differs from claimed raw run reassembly')
