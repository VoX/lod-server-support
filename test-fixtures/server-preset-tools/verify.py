"""Independent run-bound receipt recomputation, invoked before proof and after collect."""
import json,re,sys
from pathlib import Path

def verify_active_dependencies(tool_root,staged):
 from rig import sha,regular
 for name in ('server_control_smoke.py','conservative_native.py','check_conservative_native.py'):
  if sha(regular(Path(tool_root)/name))!=staged['preset-tools/'+name]:raise ValueError('active preset dependency differs from staged bytes: '+name)

def verify(root,tool_root,require_cleanup=False):
 root=Path(root).resolve();sys.path[:0]=[str(tool_root),str(Path(tool_root).parent/'compat')]
 from rig import read,sha,digest,regular,inside,alive
 from server_control_smoke import verify_receipt
 manifest=read(root/'manifest.json');runtime=read(root/'runtime.json');scenario=read(root/'scenario.json');report=read(root/'evidence/preset-report.json');contract=runtime['preset_contract']
 if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:raise ValueError('run inputs changed')
 for k in ('run_id','run_hash','profile_hash','scenario_hash'):
  if report.get(k)!=manifest[k]:raise ValueError('preset report identity differs')
 if set(report['receipts'])!=set(contract['modes']):raise ValueError('required native modes absent')
 staged={r['target']:r['sha256'] for r in manifest['run_manifest']['staged_inputs']}
 for name in ('drive.py','verify.py','server_control_smoke.py','conservative_native.py','check_conservative_native.py'):
  target='preset-tools/'+name
  if sha(regular(inside(root,target)))!=staged.get(target):raise ValueError('preset orchestration source identity changed')
 verify_active_dependencies(tool_root,staged)
 for target,expected in (contract['production_artifacts']|contract['fixture_artifacts']).items():
  if staged.get(target)!=expected or sha(regular(inside(root,target)))!=expected:raise ValueError('candidate artifact identity differs')
 participant=read(root/'participants/server.json')
 if digest(participant)!=contract['server_profile_hash'] or participant['platform']!=contract['platform']:raise ValueError('native server platform lock differs')
 rows=[json.loads(line) for line in (root/'evidence/client-first.jsonl').read_text().splitlines() if line.strip()]
 if any(r.get('event') in ('wire_capture','body_first','body_second') for r in rows):raise ValueError('unexpected chunk8 body route in preset-only observer')
 if require_cleanup:
  closed=[r for r in rows if r.get('event')=='client_closed']
  if len(closed)!=1 or closed[0].get('overflow') is not False:raise ValueError('native observer writer/closure failure')
 sessions=[r for r in rows if r.get('event')=='client_handshake']
 if len(sessions)!=1 or sessions[0].get('run_id')!=manifest['run_id'] or sessions[0].get('protocol')!=20 or sessions[0].get('phase')!='first':raise ValueError('real single native observer handshake absent')
 log=(root/'server.private.log').read_text(errors='replace');client=(root/'preset-client.private.log').read_text(errors='replace')
 for failure in ('independent smoke block mismatch','unexpected session replacement in one-phase client','invalid body size','unexpected wire codec','owned smoke context required'):
  if failure in client:raise ValueError('native observer fixture failed: '+failure)
 if len(re.findall(re.escape('handshake received from RigSubjectA (protocol v20,'),log))!=1 or not re.search(r'Server session config received \(protocol v20,[^\n]*enabled: true\)',client):raise ValueError('native client/server v20 readback absent')
 results={}
 for mode,name in report['receipts'].items():
  receipt=regular(inside(root,name))
  if mode=='conservative-numeric':
   from check_conservative_native import verify as verify_numeric
   result=verify_numeric(root,receipt);result['mode']=mode
  else:result=verify_receipt(root,receipt)
  if result['mode']!=mode:raise ValueError('receipt mode differs')
  results[mode]=result
 if 'after-restart' in contract['modes']:
  previous=Path(contract['previous_run']).resolve();prior_report=read(previous/'evidence/preset-report.json');prior=previous/prior_report['receipts']['stage-restart']
  verify_receipt(previous,prior)
  old=read(previous/'manifest.json');old_result=read(previous/'evidence/result.json')
  if old['run_id']==manifest['run_id'] or old_result.get('cleanup')!='complete' or old_result.get('status')!='passed':raise ValueError('restart parent not a clean separate successful owned run')
  if sha(prior)!=contract['previous_receipt_sha256'] or sha(root/'evidence/previous-stage-receipt.json')!=sha(prior):raise ValueError('staged prior receipt changed')
  after=read(root/report['receipts']['after-restart'])
  if after['previous_receipt_sha256']!=sha(prior):raise ValueError('after-restart did not validate exact staged receipt')
 if require_cleanup:
  result=read(root/'evidence/result.json')
  if result.get('cleanup')!='complete' or result.get('status')!='passed':raise ValueError('native preset run did not pass and clean up')
  if any(alive(p) for p in read(root/'processes.json')):raise ValueError('owned processes remain alive')
 return dict(status='passed',run_id=manifest['run_id'],run_hash=manifest['run_hash'],platform=contract['platform'],modes=results,cleanup_verified=require_cleanup)
if __name__=='__main__':print(json.dumps(verify(Path(sys.argv[1]),Path(sys.argv[2]),'--cleanup' in sys.argv)))
