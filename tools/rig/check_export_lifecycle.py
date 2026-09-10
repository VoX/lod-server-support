"""Require ordered actual native export events bound to this run, reject duplicates/failures."""
import re
EVENTS=['READY_V20']+[f'{route}_{phase}' for route in ['SCREEN','COMMAND'] for phase in ['REAL_IO_HELD','SINK_RESERVED','DISCONNECT_DROPPED_SINK','NATIVE_SAME_DIMENSION_REPLACEMENT','OLD_COMPLETION_SUPPRESSED','FRESH_SUCCESS']]+['REAL_SUBMISSION_REJECTED_AND_RELEASED','POST_REJECTION_COMMAND_SUCCESS','PASS']
ASSERTIONS=['screen-held-export-retired','command-held-export-retired','native-same-dimension-replacement','fresh-screen-and-command','actual-submission-rejection-releases']
def check(text,run_id):
 rows=re.findall(r'\[EXPORT-FIXTURE\] ([A-Z0-9_]+) run=([^\s]+)',text)
 errors=[]
 if any(identity!=run_id for _,identity in rows):errors.append('foreign run event')
 observed=[event for event,identity in rows if identity==run_id]
 if observed!=EVENTS:errors.append('missing, duplicated, failed, or reordered native export events')
 return {'passed':not errors,'errors':errors,'events':observed,'assertions':{key:not errors for key in ASSERTIONS}}

def inspect(root,manifest):
 from pathlib import Path
 from rig import read,inside,regular,digest
 root=Path(root);runtime=read(root/'runtime.json');scenario=read(root/'scenario.json')
 if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:raise ValueError('changed export lifecycle inputs')
 if scenario.get('checker')!='export-lifecycle':raise ValueError('export lifecycle checker not selected')
 path=regular(inside(root,'instances/lss-rig-client/minecraft/logs/latest.log'))
 if path.stat().st_size>32*1024*1024:raise ValueError('export lifecycle log bound exceeded')
 result=check(path.read_text(errors='replace'),manifest['run_id'])
 return dict(result,run_id=manifest['run_id'],run_hash=manifest['run_hash'],handshake='READY_V20' in result['events'],events_sha256=digest(result['events']))

def make_proof(root,manifest):
 from pathlib import Path
 from rig import write,sha
 root=Path(root);report=inspect(root,manifest);dest=root/'evidence/export-lifecycle.json';write(dest,report)
 proof={key:manifest[key] for key in ['run_id','run_hash','profile_hash','scenario_hash']}
 proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=5 if report['passed'] else 0,assertions=report['assertions'],failures=report['errors'],export_report=report,evidence={'export-lifecycle.json':sha(dest)})
 write(root/'proof.json',proof);return proof

def check_report(proof,manifest,scenario,root):
 try:
  actual=inspect(root,manifest)
  if proof.get('export_report')!=actual:return ['native export report changed or missing']
  return actual['errors']
 except (ValueError,OSError,KeyError,TypeError) as e:return ['invalid native export report: '+str(e)]
