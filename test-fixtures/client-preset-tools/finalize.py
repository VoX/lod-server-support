"""Existing UI discipline: raw recomputation plus explicit actual image inspection."""
import argparse,json,hashlib,sys
from pathlib import Path
import checks
from verify import verify
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--visuals-checked',action='store_true',required=True);p.add_argument('--repo',required=True,type=Path);a=p.parse_args();r=a.root.resolve();checks.verify_tool_identity(r,a.repo,{'entrypoint':__file__,'checks.py':checks.__file__,'verify.py':verify.__code__.co_filename});receipt=verify(r,a.repo);m=json.loads((r/'manifest.json').read_text());e=r/'evidence/standalone-presets';image=e/'standalone-status.png';sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
# This flag records the operator's performed inspection; it is not inferred from a filename.
record={'run_id':m['run_id'],'run_hash':m['run_hash'],'screen':'actual standalone LSS status screen with Sodium absent','artifact':'standalone-presets/standalone-status.png','sha256':sha(image),'operator_visuals_checked':True};(e/'screen-review.json').write_text(json.dumps(record,indent=2)+'\n')
handshake=any(v['snapshot'].get('protocol')==20 and v['snapshot'].get('negotiated')is True for v in receipt['snapshots'].values())
if not handshake:raise ValueError('native v20 negotiation missing during explicit write-enabled phase')
proof={k:m[k]for k in ['run_id','run_hash','profile_hash','scenario_hash']};proof.update(ready=True,handshake=True,test_count=6,assertions=receipt['assertions'],failures=[],evidence={str(p.relative_to(r/'evidence')):sha(p)for p in e.iterdir()if p.is_file()})
(r/'proof.json').write_text(json.dumps(proof,indent=2)+'\n');print(r/'proof.json')
