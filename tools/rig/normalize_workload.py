"""Normalize independently checked raw workload facts for the fixed metric protocol."""
from check_workload import check
from target_intervals import recovery_origin


def normalize(oracle, consumers, session_rows, *, platform, start_ns, end_ns,
              debt_result, cleanup_complete, region_result=None):
    checked=check(oracle,consumers)
    if checked['status']!='passed':raise ValueError('raw target proof failed: '+str(checked['errors']))
    if debt_result.get('status')!='passed':raise ValueError('raw debt proof failed')
    joins={r['connection_id']:r for r in session_rows if r.get('event')=='join'}
    endings={r['connection_id']:r for r in session_rows if r.get('event') in ('quit','session_end')}
    if not joins or set(joins)!=set(endings):raise ValueError('complete raw connection intervals required')
    observed={}
    for row in session_rows:
        if row.get('event')!='product_registration_observed':continue
        key=row.get('connection_id');join=joins.get(key)
        if (join is None or row.get('subject')!=join['subject'] or type(row.get('time_ns')) is not int
                or not join['time_ns']<=row['time_ns']<endings[key]['time_ns']):
            raise ValueError('invalid product registration interval')
        observed[key]=min(observed.get(key,row['time_ns']),row['time_ns'])
    if set(observed)!=set(joins):raise ValueError('actual product registration observation required for every connection')
    sessions=[{'subject':r['subject'],'connection_id':key,'start_ns':observed[key],'end_ns':endings[key]['time_ns']} for key,r in joins.items()]
    if platform=='folia' and (not region_result or region_result.get('status')!='passed' or not region_result.get('region_samples')):
        raise ValueError('qualified owning tick proof required')
    outcomes={r['id']:r for rows in consumers.values() for r in rows if r.get('event')=='target_committed'}
    faults=[{'subject':r['subject'],'kind':'slow-consumer' if r['event']=='slow_consumer' else 'send-admission',
             'start_ns':r['start_ns'],'end_ns':r['end_ns']} for r in oracle if r.get('event') in ('slow_consumer','send_admission')]
    transfers={}
    for row in oracle:
        if row.get('event')=='session_transfer':transfers.setdefault(row['subject'],[]).append(row)
    for rows in transfers.values():rows.sort(key=lambda row:row['time_ns'])
    targets=[]
    for target in oracle:
        if target.get('event')!='target':continue
        outcome=outcomes[target['id']];connection=target['connection_id']
        for transfer in transfers.get(target['subject'],[]):
            if transfer['old_connection']==connection and transfer['time_ns']<=outcome['resolved_ns']:
                connection=transfer['connection_id']
        fault_removed=recovery_origin(target['offered_ns'],[f for f in faults if f['subject']==target['subject']],outcome['resolved_ns'])
        expected={'block':target.get('expected_block'),'source':target.get('expected_source')}
        actual={'block':outcome.get('expected_block'),'source':outcome.get('source') if 'expected_source' in target else None}
        targets.append(dict(target,resolved_ns=outcome['resolved_ns'],fault_removed_ns=fault_removed,
                            expected=expected,actual=actual,expected_session=connection,delivery_session=outcome['connection_id'],
                            body_id=outcome.get('body_id'),body_bytes=outcome['body_bytes']))
    windows=[]
    for subject in {r['subject'] for r in sessions}:
        own=[r for r in targets if r['subject']==subject]
        for start in range(start_ns,end_ns-29_999_999_999,30_000_000_000):
            end=start+30_000_000_000
            windows.append({'subject':subject,'start_ns':start,'duration_seconds':30,
                            'eligible':any(t['offered_ns']<end and t['resolved_ns']>start for t in own),
                            'useful_outcomes':sum(start<=t['resolved_ns']<end for t in own)})
    return {'platform':platform,'required_subjects':4,'measurement_start_ns':start_ns,'measurement_end_ns':end_ns,
            'sessions':sessions,'oracle':targets,'faults':faults,'progress_windows':windows,
            'region_samples':region_result.get('region_samples',[]) if region_result else [],
            'queue_bounds_ok':True,'drain_seconds':debt_result['drain_seconds'],'cleanup_complete':cleanup_complete}
