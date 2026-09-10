#!/usr/bin/env python3
"""Paper/Fabric adapter around the shared independent-target and debt checkers."""
import argparse
import json
from pathlib import Path
from check_workload import check as targets
from check_debt import check as debt
from check_regions import load_rows
from rig import digest, sha


def check(oracle,consumers,events,bounds):
    result=targets(oracle,consumers)
    errors=list(result['errors'])
    ready=[row for row in events if row.get('event')=='source_preconditions_ready']
    closed=[row for row in events if row.get('event')=='writer_closed']
    offers_closed=[row for row in events if row.get('event')=='offers_closed']
    if len(ready)!=1:errors.append('source readiness premise absent or repeated')
    if len(closed)!=1 or closed[0].get('overflow') is not False:errors.append('server evidence incomplete')
    subjects={'RigSubject'+letter for letter in 'ABCD'}
    initial=[row for row in oracle if row.get('event')=='target' and row.get('id','').endswith('-initial')]
    for subject in subjects:
        rows=[row for row in initial if row['subject']==subject]
        if len(rows)!=4 or {row.get('expected_source') for row in rows}!={0,1,2,3}:
            errors.append('four initial source targets absent: '+subject)
    slow=consumers.get('RigSubjectD',[])
    if not any(row.get('event')=='acceptance_deferred' for row in slow) or not any(row.get('event')=='acceptance_released' for row in slow):
        errors.append('actual deferred consumer receipt/release absent')
    if not any(row.get('event')=='session_transfer' and row.get('subject')=='RigSubjectB' for row in oracle):
        errors.append('reconnect successor session absent')
    metrics=[row for row in events if row.get('event')=='product_metrics']
    if not any({p.get('name') for p in row.get('players',[])}>=subjects for row in metrics):
        errors.append('four simultaneous product registrations absent')
    if not any(row.get('adapter_denial_reads',0)>0 for row in metrics):errors.append('admission adapter never exercised')
    if len(offers_closed)!=1:
        errors.append('explicit offers-close boundary absent');drain={'status':'failed'}
    else:
        drain=debt(events,bounds,offers_closed[0]['time_ns'],subjects)
        errors.extend(drain.get('errors',[]))
    result.update(status='failed' if errors else 'passed',errors=errors,debt_check=drain,
                  scope='four-client-source-correctness-only',performance_acceptance=False)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run');p.add_argument('--bounds',required=True)
    a=p.parse_args();root=Path(a.run);evidence=root/'evidence'
    consumers={path.stem.removeprefix('consumer-'):load_rows(path) for path in evidence.glob('consumer-*.jsonl')}
    oracle=load_rows(evidence/'oracle.jsonl');events=load_rows(evidence/'server-events.jsonl')
    result=check(oracle,consumers,events,json.loads(Path(a.bounds).read_text()))
    manifest=json.loads((root/'manifest.json').read_text());run_id=manifest['run_id']
    if digest(manifest['run_manifest'])!=manifest['run_hash']:result['errors'].append('manifest run identity mismatch')
    if any(row.get('run_id')!=run_id for row in [*oracle,*events]):result['errors'].append('foreign/unbound server evidence row')
    for subject,rows in consumers.items():
        ready=[row for row in rows if row.get('event')=='consumer_ready']
        if len(ready)!=1 or ready[0].get('run_id')!=run_id or ready[0].get('subject')!=subject:result['errors'].append('consumer run identity absent/mismatched: '+subject)
        if any(row.get('run_id')!=run_id or row.get('subject')!=subject for row in rows):result['errors'].append('foreign consumer evidence row: '+subject)
    result.update(run_id=run_id,run_hash=manifest['run_hash'],profile_hash=manifest['profile_hash'],scenario_hash=manifest['scenario_hash'],raw_evidence_sha256={path.name:sha(path) for path in [evidence/'oracle.jsonl',evidence/'server-events.jsonl',*sorted(evidence.glob('consumer-*.jsonl'))]})
    if result['errors']:result['status']='failed'
    for path in root.rglob('*.log'):
        if path.is_symlink():continue
        if 'Unexpected failure delivering disk read' in path.read_text(errors='replace'):
            result['errors'].append('one-result-per-submit premise violated by unexpected disk delivery failure')
            result['status']='failed'
    (evidence/'source-correctness.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2));raise SystemExit(result['status']!='passed')
