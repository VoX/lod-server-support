#!/usr/bin/env python3
"""Paper/Fabric adapter around the shared independent-target and debt checkers."""
import argparse
import json
from pathlib import Path
from check_workload import check as targets
from check_debt import check as debt
from check_regions import load_rows
from rig import digest, sha


def armed_hold(oracle, consumers):
    """Sparse-only receipt trigger; join exact identities, never nearest timestamps."""
    arms=[r for r in oracle if r.get('event')=='slow_consumer_arm']
    triggers=[r for rows in consumers.values() for r in rows if r.get('event')=='slow_consumer_triggered']
    echoes=[r for r in oracle if r.get('event')=='slow_consumer']
    if len(arms)!=1 or len(triggers)!=1 or len(echoes)!=1:return False
    arm=arms[0];trigger=triggers[0];echo=echoes[0]
    arm_fields=('run_id','subject','connection_id','target_id','armed_ns','deadline_ns','duration_ns')
    fields=arm_fields+('body_id','wire_capture_id','start_ns','end_ns')
    if any(k not in arm for k in arm_fields) or any(k not in trigger or k not in echo for k in fields):return False
    if any(trigger[k]!=arm[k] for k in arm_fields) or any(trigger[k]!=echo[k] for k in fields):return False
    if arm['subject']!='RigSubjectD' or any(type(arm[k])is not str or not arm[k] for k in ('run_id','connection_id','target_id')):return False
    if any(type(trigger[k])is not int for k in ('armed_ns','deadline_ns','duration_ns','body_id','wire_capture_id','start_ns','end_ns')):return False
    start=trigger['start_ns'];end=trigger['end_ns']
    if not (0<=arm['armed_ns']<=start<arm['deadline_ns'] and arm['deadline_ns']==arm['armed_ns']+120_000_000_000
            and arm['duration_ns']==20_000_000_000 and end==start+arm['duration_ns']
            and trigger['body_id']>0 and trigger['wire_capture_id']>0):return False
    targets=[r for r in oracle if r.get('event')=='target' and r.get('id')==arm['target_id']]
    if len(targets)!=1:return False
    target=targets[0]
    if any(target.get(k)!=arm[k] for k in ('run_id','subject','connection_id')):return False
    rows=consumers.get('RigSubjectD',[])
    if trigger not in rows:return False
    wires=[r for r in rows if r.get('event')=='wire_capture' and r.get('wire_capture_id')==trigger['wire_capture_id']]
    if len(wires)!=1:return False
    wire=wires[0]
    if any(wire.get(k)!=arm[k] for k in ('run_id','subject','connection_id')):return False
    if any(k not in target or wire.get(k)!=target[k] for k in ('chunk_x','chunk_z','dimension')):return False
    if type(target.get('offered_ns'))is not int or type(wire.get('arrival_ns'))is not int or not arm['armed_ns']<=target['offered_ns']<=wire['arrival_ns']<=start:return False
    applied=[r.get('time_ns') for r in oracle if r.get('event') in ('edit_applied','target_ready') and r.get('id')==arm['target_id']]
    if wire.get('source')!=target.get('expected_source') or not applied or any(type(t)is not int or not 0<t<=wire['arrival_ns'] for t in applied):return False
    paired=[r for r in rows if r.get('event') in ('acceptance_deferred','acceptance_released')
            and r.get('body_id')==trigger['body_id'] and r.get('wire_capture_id')==trigger['wire_capture_id']]
    deferred=[r for r in paired if r['event']=='acceptance_deferred'];released=[r for r in paired if r['event']=='acceptance_released']
    if len(deferred)!=1 or len(released)!=1:return False
    if any(r.get('run_id')!=arm['run_id'] or r.get('subject')!=arm['subject']
           or any(type(r.get(k))is not int for k in ('time_ns','body_id','wire_capture_id')) for r in paired):return False
    committed=[r for r in rows if r.get('event')=='target_committed' and r.get('id')==arm['target_id']]
    # A rejected receipt may be replaced; commit identity need not equal trigger body.
    if len(committed)!=1 or type(committed[0].get('resolved_ns'))is not int or committed[0]['resolved_ns']<end:return False
    return start<=deferred[0]['time_ns']<end<=released[0]['time_ns']


def scheduled_hold(oracle, consumers):
    """Aggregate timed hold coverage; existing events do not identify receipts."""
    if any(r.get('event')=='slow_consumer_arm' for r in oracle) or any(r.get('event')=='slow_consumer_triggered' for rows in consumers.values() for r in rows):
        return armed_hold(oracle,consumers)
    pauses=[row for row in oracle if row.get('event')=='slow_consumer']
    if len(pauses)!=1 or pauses[0].get('subject')!='RigSubjectD':return False
    start=pauses[0].get('start_ns');end=pauses[0].get('end_ns')
    if type(start)is not int or type(end)is not int or not 0<=start<end:return False
    rows=[row for row in consumers.get('RigSubjectD',[])
          if row.get('event') in ('acceptance_deferred','acceptance_released')]
    if any(row.get('subject')!='RigSubjectD' or type(row.get('time_ns'))is not int
           or row['time_ns']<0 for row in rows):return False
    # Concurrent producers do not promise journal arrival order. At equal times,
    # count defers before releases for a conservative outstanding lower bound.
    rows=sorted(rows,key=lambda row:(row['time_ns'],row['event']=='acceptance_released'))
    # Each in-window release conservatively consumes an in-window defer first.
    # An older receipt can retire during the pause; do not reject other live holds.
    outstanding=0
    for row in rows:
        if start<=row['time_ns']<end:
            if row['event']=='acceptance_deferred':outstanding+=1
            else:outstanding=max(0,outstanding-1)
    return outstanding>0 and any(row['event']=='acceptance_released' and row['time_ns']>=end for row in rows)



def check(oracle,consumers,events,bounds):
    hold_ok=scheduled_hold(oracle,consumers)
    # Malformed fault intervals cannot safely enter the shared recovery arithmetic.
    pauses=[row for row in oracle if row.get('event')=='slow_consumer']
    if any(type(row.get('start_ns'))is not int or type(row.get('end_ns'))is not int
           or not 0<=row['start_ns']<row['end_ns'] for row in pauses):
        return dict(status='failed',errors=['actual scheduled deferred consumer hold/release absent or invalid'],
                    scope='four-client-source-correctness-only',performance_acceptance=False)
    result=targets(oracle,consumers)
    errors=list(result['errors'])
    if not hold_ok:errors.append('actual scheduled deferred consumer hold/release absent or invalid')
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
