#!/usr/bin/env python3
"""Early two-client Folia feasibility; no workload/performance acceptance implied."""
import argparse
import hashlib
import bisect
import json
import re
from pathlib import Path
from performance import overlap

def handshakes(root, joins):
    server=(root/'server.private.log').read_text(errors='replace')
    result=[]
    occurrences={}
    for connection,row in sorted(joins.items(),key=lambda entry:entry[1]['time_ns']):
        subject=row['subject']
        if not re.fullmatch('RigSubject[ABCD]',subject):continue
        client=root/('client-'+subject[-1]+'.private.log')
        text=client.read_text(errors='replace') if client.exists() else ''
        occurrences[subject]=occurrences.get(subject,0)+1
        accepted=(server.count(f'Player {subject} registered for LSS LOD request processing')>=occurrences[subject]
                  and text.count('Server session config received (protocol v20,')>=occurrences[subject])
        result.append({'connection_id':connection,'subject':subject,'accepted':accepted})
    return result

def check(rows, ticks=None, accepted=None, allow_reconnect=False, include_samples=False):
    errors=[]
    joins={r['connection_id']:r for r in rows if r.get('event')=='join'}
    endings={r['connection_id']:r for r in rows if r.get('event') in ('quit','session_end')}
    if len(joins)!=sum(r.get('event')=='join' for r in rows):errors.append('reused connection identity')
    # This narrow feasibility lane has one connection per subject; reconnect
    # workload evidence requires its richer per-session client fixture instead.
    if not allow_reconnect and len({r['subject'] for r in joins.values()})!=len(joins):errors.append('ambiguous repeated subject handshake')
    intervals=[(r['subject'],r['time_ns'],endings[c]['time_ns']) for c,r in joins.items() if c in endings]
    if not any(a[0]!=b[0] and max(a[1],b[1])<min(a[2],b[2]) for i,a in enumerate(intervals) for b in intervals[i+1:]):
        errors.append('two independently connected simultaneous complete sessions absent')
    admitted={(r['connection_id'],r['subject']) for r in (accepted or []) if r.get('accepted') is True}
    if len(admitted)<2 or any((c,r['subject']) not in admitted for c,r in joins.items()):errors.append('actual LSS registration/client acceptance absent')
    registrations={}
    for row in rows:
        if row.get('event')!='product_registration_observed':continue
        connection=row.get('connection_id');joined=joins.get(connection);ended=endings.get(connection)
        if not joined or not ended or row.get('subject')!=joined['subject'] or not joined['time_ns']<=row.get('time_ns',-1)<=ended['time_ns']:
            errors.append('product registration outside its joined session');continue
        registrations[connection]=min(registrations.get(connection,row['time_ns']),row['time_ns'])
    if any(connection not in registrations for connection in joins):errors.append('structured current-session product registration absent')
    samples=[]
    for sample in rows:
        if sample.get('event')!='owning_work':continue
        connection=sample.get('connection_id');start=joins.get(connection);end=endings.get(connection)
        if not start or not end or sample.get('subject')!=start['subject'] or not start['time_ns']<=sample['start_ns']<sample['end_ns']<=end['time_ns']:
            errors.append('owning work outside its joined session');continue
        if (connection,sample['subject']) not in admitted or sample['start_ns']<registrations.get(connection,float('inf')):continue
        samples.append(sample)
    qualified=samples
    if ticks is not None:
        if not any(r.get('event')=='timing_applied' for r in ticks):errors.append('exact timing transformer absent')
        if not any(r.get('event')=='timing_closed' for r in ticks):errors.append('timing writer incomplete')
        if any(r.get('event')=='timing_failure' or r.get('failed') or r.get('overflow') for r in ticks):errors.append('timing instrumentation failure')
        by_region={}
        for tick in ticks:
            if tick.get('event')=='owning_tick':by_region.setdefault(tick['region_identity'],[]).append(tick)
        indexes={}
        for region,values in by_region.items():
            values.sort(key=lambda t:t['start_ns']);indexes[region]=[t['start_ns'] for t in values]
        qualified=[]
        for sample in samples:
            region=sample['region_identity'];values=by_region.get(region,[])
            at=bisect.bisect_right(indexes.get(region,[]),sample['start_ns'])-1
            if at>=0 and values[at]['start_ns']<=sample['start_ns'] and sample['end_ns']<=values[at]['end_ns']:
                tick=values[at]
                if tick['start_ns']>=registrations[sample['connection_id']] and tick['end_ns']<=endings[sample['connection_id']]['time_ns']:
                    qualified.append(dict(sample,start_ns=tick['start_ns'],end_ns=tick['end_ns']))
    if not overlap(qualified):errors.append('genuine distinct owning-region work overlap absent')
    if any(r.get('event') in ('ownership_failed','teleport_failed') or r.get('overflow') for r in rows):errors.append('fixture failure/overflow')
    if not any(r.get('event')=='writer_closed' for r in rows):errors.append('incomplete evidence writer shutdown')
    result={'status':'failed' if errors else 'passed','errors':list(dict.fromkeys(errors)),'owning_samples':len(samples),'qualified_tick_samples':len(qualified),'sessions':len(joins),'scope':'two-client-owning-region-feasibility-only'}
    if include_samples:result['region_samples']=qualified
    return result

def load_rows(path):return [json.loads(line) for line in path.read_text().splitlines()]
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('run');a=p.parse_args();root=Path(a.run)
    rows=load_rows(root/'evidence/region-events.jsonl');joins={r['connection_id']:r for r in rows if r.get('event')=='join'}
    ticks=load_rows(root/'evidence/tick-events.jsonl') if (root/'evidence/tick-events.jsonl').exists() else None
    accepted=handshakes(root,joins)
    result=check(rows,ticks,accepted)
    manifest=json.loads((root/'manifest.json').read_text())
    result['checker_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    result.update({k:manifest[k] for k in ('run_id','profile_hash','scenario_hash','runtime_hash')})
    if 'run_hash' in manifest:result['run_hash']=manifest['run_hash']
    (root/'evidence/regions-result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2));raise SystemExit(result['status']!='passed')
