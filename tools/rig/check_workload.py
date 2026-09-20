#!/usr/bin/env python3
"""Independent target delivery diagnostic; deliberately not performance acceptance."""
import argparse
import json
from pathlib import Path
from target_intervals import intervals, delivery_errors, recovery_origin, wire_errors
from check_regions import check as regions, handshakes, load_rows


def expected_sources(target):
    """Keep controlled route probes exact; validate the explicit repeated-edit policy."""
    if 'allowed_sources' not in target:
        return (target['expected_source'],) if 'expected_source' in target else None
    allowed=target['allowed_sources']
    if (type(target.get('target_sequence')) is not int or target['target_sequence']<=0
            or type(target.get('expected_source')) is not int or target['expected_source']!=0
            or type(allowed) is not list or allowed!=[0,1,3]
            or any(type(source) is not int for source in allowed)):
        raise ValueError('invalid measured source policy')
    return tuple(allowed)


def check(oracle, consumers, required_subjects=4):
    strict=any("target_sequence" in row or "cell_revision" in row for row in oracle if row.get("event")=="target")
    bounds,errors=intervals(oracle,strict)
    targets={}
    for row in oracle:
        if row.get('event')!='target':continue
        if row['id'] in targets:errors.append('duplicate oracle target: '+row['id'])
        targets[row['id']]=row
        try:expected_sources(row)
        except ValueError:errors.append('invalid target source policy: '+row['id'])
    applied={row['id']:row['time_ns'] for row in oracle if row.get('event') in ('edit_applied','target_ready')}
    acknowledged={row['id']:row['time_ns'] for row in oracle if row.get('event')=='target_acknowledged'}
    sessions={(row['subject'],row['connection_id']) for row in oracle if row.get('event')=='session'}
    subjects={row['subject'] for row in targets.values()}
    if len(subjects)!=required_subjects:errors.append('independent target subjects incomplete')
    transfers_by_subject={}
    faults_by_subject={}
    for event in oracle:
        if event.get("event")=="session_transfer":transfers_by_subject.setdefault(event.get("subject"),[]).append(event)
        if event.get("event") in ("slow_consumer","send_admission"):faults_by_subject.setdefault(event.get("subject"),[]).append(event)
    for values in transfers_by_subject.values():values.sort(key=lambda event:event["time_ns"])
    outcomes={}
    useful={subject:0 for subject in subjects}
    bodies={}
    for subject,rows in consumers.items():
        errors.extend(wire_errors(rows,strict))
        closed=[row for row in rows if row.get('event')=='consumer_closed']
        if len(closed)!=1 or closed[0].get('overflow') or closed[0].get('held')!=0:errors.append('consumer shutdown incomplete: '+subject)
        for row in rows:
            if row.get('event')!='target_committed':continue
            target=targets.get(row['id'])
            if target is None:errors.append('foreign target committed: '+row['id']);continue
            errors.extend(delivery_errors(target,row,bounds,strict))
            if row['id'] in outcomes:errors.append('duplicate target outcome: '+row['id']);continue
            expected_connection=target['connection_id']
            for transfer in transfers_by_subject.get(subject,[]):
                if transfer.get('event')=='session_transfer' and transfer.get('subject')==subject and transfer['old_connection']==expected_connection and transfer['time_ns']<=row.get('resolved_ns',0):
                    expected_connection=transfer['connection_id']
                    if strict and row.get('body_received_ns',-1)<transfer['time_ns']:
                        errors.append('transferred demand used pre-transfer wire receipt: '+row['id'])
            if (subject!=target['subject'] or row.get('subject')!=subject
                    or row.get('connection_id')!=expected_connection
                    or (subject,expected_connection) not in sessions
                    or row.get('lease_active') is not True):
                errors.append('stale/foreign target outcome: '+row['id']);continue
            if row.get('body_bytes',0)<=0:errors.append('target without actual body bytes: '+row['id']);continue
            try:allowed=expected_sources(target)
            except ValueError:continue
            if allowed is not None and (type(row.get('source')) is not int or row['source'] not in allowed):
                errors.append('target delivered from wrong source: '+row['id']);continue
            if 'expected_block' in target and row.get('expected_block')!=target['expected_block']:
                errors.append('target block oracle mismatch: '+row['id']);continue
            if row.get('resolved_ns',0)<applied.get(row['id'],float('inf')):errors.append('target committed before independent edit: '+row['id']);continue
            if 'body_id' in row and (type(row.get('body_received_ns')) is not int or not applied.get(row['id'],float('inf'))<=row['body_received_ns']<=row['resolved_ns']):
                errors.append('body receipt predates independent edit or lacks timing: '+row['id']);continue
            # The recovery clock starts once the independently registered fault
            # has ended; never infer a stall from request-manager silence.
            fault_end=recovery_origin(target['offered_ns'],faults_by_subject.get(subject,[]),row['resolved_ns'])
            if row['resolved_ns']-fault_end>120_000_000_000:errors.append('target recovery deadline exceeded: '+row['id'])
            outcomes[row['id']]=row
            body=(subject,expected_connection,row.get('body_id',row['id']))
            if body in bodies and bodies[body]!=row['body_bytes']:
                errors.append('inconsistent shared body size: '+row['id'])
            if body not in bodies:useful[subject]+=row['body_bytes']
            bodies[body]=row['body_bytes']
    for target_id in targets:
        if target_id not in applied:errors.append('oracle edit not applied: '+target_id)
        if target_id not in outcomes:errors.append('target not delivered: '+target_id)
        if targets[target_id].get('requires_ack') and not targets[target_id]['offered_ns']<=acknowledged.get(target_id,-1)<=applied.get(target_id,-2):
            errors.append('edit lacks prior client oracle acknowledgment: '+target_id)
    for subject in subjects:
        if subject not in consumers:errors.append('consumer evidence absent: '+subject)
    return {'status':'failed' if errors else 'passed','scope':'independent-target-delivery-diagnostic-only',
            'targets':len(targets),'committed':len(outcomes),'useful_body_bytes':useful,'errors':errors,
            'performance_acceptance':False}


def inspect(root):
    manifest=json.loads((root/'manifest.json').read_text())
    oracle=load_rows(root/'evidence/oracle.jsonl')
    consumers={path.stem.removeprefix('consumer-'):load_rows(path) for path in (root/'evidence').glob('consumer-*.jsonl')}
    result=check(oracle,consumers)
    for subject,events in consumers.items():
        if any(row.get('run_id')!=manifest['run_id'] or row.get('subject')!=subject for row in events):
            result['status']='failed';result['errors'].append('consumer run/subject identity mismatch: '+subject)
    rows=load_rows(root/'evidence/region-events.jsonl')
    joins={row['connection_id']:row for row in rows if row.get('event')=='join'}
    region_result=regions(rows,load_rows(root/'evidence/tick-events.jsonl'),handshakes(root,joins),allow_reconnect=True)
    active=set();simultaneous=0
    for row in sorted((r for r in rows if r.get('event') in ('join','quit','session_end')),key=lambda r:r['time_ns']):
        if row['event']=='join':active.add(row['connection_id'])
        else:active.discard(row['connection_id'])
        simultaneous=max(simultaneous,len({joins[c]['subject'] for c in active}))
    if simultaneous<4:result['status']='failed';result['errors'].append('four simultaneous registered subjects absent')
    result['region_check']=region_result
    if region_result['status']!='passed':result['status']='failed'
    if any(row.get('event')=='target_edit_failed' for row in rows):
        result['status']='failed';result['errors'].append('owning target edit failed')
    result.update({field:manifest[field] for field in ('run_id','run_hash','profile_hash','scenario_hash')})
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run');a=p.parse_args();root=Path(a.run)
    result=inspect(root)
    (root/'evidence/workload-diagnostic.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2));raise SystemExit(result['status']!='passed')
