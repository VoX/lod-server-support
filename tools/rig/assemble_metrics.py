"""Pure metric assembly from exact run-bound observations; no acceptance shortcuts."""
from metrics import duration_metrics, rss_metrics, eligible_seconds


def assemble(run_id, consumers, tick_rows, rss_rows, oracle, faults, owners, launch_ids, start_ns, end_ns):
    subjects=set(consumers)
    if set(owners)!={'server',*subjects} or set(launch_ids)!={'server',*subjects}:
        raise ValueError('explicit process and launch identities required for every subject')
    if len(set(launch_ids.values()))!=len(launch_ids):
        raise ValueError('launch identity reused')
    if not isinstance(start_ns,int) or not isinstance(end_ns,int) or end_ns<=start_ns:
        raise ValueError('invalid measurement interval')
    targets={target['id']:target for target in oracle}
    if len(targets)!=len(oracle):raise ValueError('duplicate target oracle identity')
    metrics={}; all_frames=[]; useful_rates=[]
    for subject,rows in consumers.items():
        if any(row.get('run_id')!=run_id or row.get('subject')!=subject for row in rows):
            raise ValueError('stale or mixed consumer observations')
        frames=[row for row in rows if row.get('event')=='frame'];all_frames.extend(frames)
        sample=duration_metrics(frames,start_ns,end_ns,'frame')
        rss_result=rss_metrics(rss_rows,launch_ids[subject],owners[subject],start_ns,end_ns)
        sample['errors'].extend(rss_result.pop('errors'));sample.update(rss_result)
        bodies={}
        for row in rows:
            if row.get('event')!='target_committed' or not start_ns<=row.get('resolved_ns',-1)<end_ns:continue
            target=targets.get(row.get('id'))
            if target is None or target.get('subject')!=subject or target.get('actual')!=target.get('expected') or target.get('actual') is None:
                raise ValueError('body has no independently validated target outcome')
            if row.get('connection_id')!=target.get('delivery_session') or row.get('lease_active') is not True:
                raise ValueError('body belongs to stale delivery session')
            body_id=row.get('body_id');size=row.get('body_bytes')
            valid_id=(isinstance(body_id,str) and bool(body_id)) or (isinstance(body_id,int) and not isinstance(body_id,bool) and body_id>=0)
            if not valid_id or not isinstance(size,int) or isinstance(size,bool) or size<=0:
                raise ValueError('actual body identity and byte count required')
            key=(row['connection_id'],body_id)
            if key in bodies and bodies[key]!=size:raise ValueError('inconsistent body byte count')
            bodies[key]=size
        seconds=eligible_seconds(oracle,faults,subject,start_ns,end_ns)
        sample['eligible_seconds']=seconds;sample['useful_body_count']=len(bodies);sample['useful_body_bytes']=sum(bodies.values())
        sample['useful_bytes_per_second']=sum(bodies.values())/seconds if seconds>0 else None
        useful_rates.append(sample['useful_bytes_per_second']);metrics[subject]=sample
    server=duration_metrics(tick_rows,start_ns,end_ns,'tick')
    rss_result=rss_metrics(rss_rows,launch_ids['server'],owners['server'],start_ns,end_ns)
    server['errors'].extend(rss_result.pop('errors'));server.update(rss_result)
    total_rate=None if any(value is None for value in useful_rates) else sum(useful_rates)
    server['useful_bytes_per_second']=total_rate;metrics['server']=server
    # Aggregate peak is the simultaneous total, not a sum of unrelated peaks.
    scheduled={}
    for row in rss_rows:
        if start_ns<=row.get('scheduled_ns',-1)<end_ns and row.get('subject') in launch_ids.values():
            at=row['scheduled_ns'];slot=scheduled.setdefault(at,{})
            if row['subject'] in slot:raise ValueError('duplicate scheduled RSS sample')
            slot[row['subject']]=row
    aggregate_rows=[]
    by_launch={launch_ids[subject]:owners[subject] for subject in owners}
    for at,slot in scheduled.items():
        complete=set(slot)==set(by_launch) and all(row.get('missing') is False and row.get('process_identity')==by_launch[name]
                and isinstance(row.get('rss_bytes'),int) and not isinstance(row.get('rss_bytes'),bool) and row['rss_bytes']>=0 for name,row in slot.items())
        row={'subject':'aggregate','scheduled_ns':at,'process_identity':owners,'missing':not complete}
        if complete:row['rss_bytes']=sum(item['rss_bytes'] for item in slot.values())
        aggregate_rows.append(row)
    aggregate=duration_metrics(tick_rows,start_ns,end_ns,'tick')
    frame_result=duration_metrics(all_frames,start_ns,end_ns,'frame')
    aggregate['errors'].extend(frame_result.pop('errors'));aggregate.update(frame_result)
    rss_result=rss_metrics(aggregate_rows,'aggregate',owners,start_ns,end_ns)
    aggregate['errors'].extend(rss_result.pop('errors'));aggregate.update(rss_result)
    aggregate['useful_bytes_per_second']=total_rate
    aggregate['aggregation_policy']='pooled frame/tick samples; sum of eligible-subject useful rates; simultaneous owned-process RSS total'
    metrics['aggregate']=aggregate
    return metrics
