"""Bind map observations, native reference and user screenshot to one owned run."""
from pathlib import Path
import json,re
from check_xaero_map import inspect as check_rows
from prepare_xaero_map import PROFILES

def load_rows(root,require_closed=False):
 from rig import regular
 path=regular(Path(root)/'evidence/xaero-map.jsonl')
 if path.stat().st_size>32*1024*1024:raise ValueError('map evidence bound exceeded')
 body=path.read_bytes()
 if require_closed and body and not body.endswith(b'\n'):raise ValueError('closed map evidence has an incomplete trailing record')
 return [json.loads(row)for row in body.splitlines(keepends=True)if row.endswith(b'\n')]

def inspect(root,manifest,require_closed=True,prefix_count=None):
 from rig import read,digest,regular,sha
 root=Path(root);profile,runtime,scenario=[read(root/(name+'.json'))for name in ('profile','runtime','scenario')]
 for key,value in [('profile',profile),('runtime',runtime),('scenario',scenario)]:
  if digest(value)!=manifest.get(key+'_hash'):raise ValueError('map '+key+' identity changed')
 if profile.get('id')not in PROFILES or scenario.get('checker')!='xaero-map':raise ValueError('exact native map lane required')
 if {x['id']for x in runtime.get('launches',[])}!={'server','client','map-controller'}:raise ValueError('exact owned map composition required')
 oracle=read(regular(root/'evidence/xaero-map-oracle.json'))
 if oracle.get('run_hash')!=manifest['run_hash']:raise ValueError('map oracle run binding changed')
 rows=load_rows(root,require_closed=require_closed)
 if prefix_count is not None:
  if type(prefix_count)is not int or not 0<prefix_count<=len(rows):raise ValueError('map live prefix absent/invalid')
  rows=rows[:prefix_count]
 assertions,errors=check_rows(rows,oracle,manifest['run_id'],allow_open=not require_closed)
 from xaero_map_viewport import framed,rectangle,capture_stable
 viewport=read(regular(root/'evidence/xaero-map-viewport.json'))
 native=viewport.get('native_viewport',{})
 if viewport.get('run_id')!=manifest['run_id'] or viewport.get('run_hash')!=manifest['run_hash'] or native not in rows or native.get('event')!='map_viewport' or not framed(native) or viewport.get('target_rectangle')!=rectangle(native) or not capture_stable(rows,viewport):
  errors.append('target boundary not framed by retained actual native viewport')
 log=regular(root/'instances/lss-rig-client/minecraft/logs/latest.log')
 if log.stat().st_size>32*1024*1024:raise ValueError('native log bound exceeded')
 text=log.read_text(errors='replace');handshake=bool(re.search(r'Server session config received \(protocol v20, LOD distance: \d+ chunks, enabled: true\)',text))
 if not handshake:errors.append('actual enabled native v20 negotiation absent')
 if any(word in text for word in ['Trying to save cache for a region with cache not prepared','[XAERO-MAP-FIXTURE] EVIDENCE_FAILED']):errors.append('native map save/observer failure')
 closed=bool(rows and rows[-1].get('event')=='observer_closed')
 if require_closed and '[XAERO-MAP-FIXTURE] CLOSED overflow=false pending=0' not in text:errors.append('map writer process closure receipt absent')
 return dict(run_id=manifest['run_id'],run_hash=manifest['run_hash'],passed=not errors,assertions=assertions,errors=errors,handshake=handshake,closed=closed,observations_count=len(rows),observations_sha256=digest(rows),oracle_sha256=sha(root/'evidence/xaero-map-oracle.json'))

def make_proof(root,manifest,review_artifacts=None,require_closed=True):
 from rig import read,write,sha
 root=Path(root);report=inspect(root,manifest,require_closed)
 path=root/'evidence/xaero-map-report.json';write(path,report)
 prior=read(root/'proof.json')if(root/'proof.json').is_file()else{}
 failures=list(dict.fromkeys([str(x)for x in prior.get('failures',[])]+report['errors']))
 proof={key:manifest[key]for key in ('run_id','run_hash','profile_hash','scenario_hash')}
 evidence={name:sha(root/'evidence'/name)for name in ['xaero-map-report.json','xaero-map-oracle.json','xaero-map-commands.json','xaero-map-viewport.json']}
 # Raw append-only stream remains live until normal writer shutdown. The final
 # postcleanup report additionally freezes its exact full bytes.
 if report['closed']:evidence['xaero-map.jsonl']=sha(root/'evidence/xaero-map.jsonl')
 proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=5 if report['passed']else 0,assertions=report['assertions'],failures=failures,map_report=report,evidence=evidence,reviews=prior.get('reviews',{}),review_artifacts=prior.get('review_artifacts',{})if review_artifacts is None else review_artifacts)
 write(root/'proof.json',proof);return proof

def check_report(proof,manifest,root):
 try:
  report=proof.get('map_report',{});closed=report.get('closed')is True
  if not closed and manifest.get('status')!='running':return ['map observation writer must close before acceptance']
  actual=inspect(root,manifest,require_closed=closed,prefix_count=None if closed else report.get('observations_count'))
  if report!=actual:return ['map native evidence report changed or missing']
  return actual['errors']
 except (ValueError,OSError,KeyError,TypeError)as error:return ['map evidence invalid: '+str(error)]
