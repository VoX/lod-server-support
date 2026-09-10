"""Derive metrics from bounded raw observations, retaining missing data explicitly."""
import math


def percentile(values, fraction):
    """Nearest-rank percentile, fixed before experiments; never fill missing samples."""
    values=sorted(values)
    if not values:return None
    return values[max(0,math.ceil(fraction*len(values))-1)]


def duration_metrics(rows, start, end, kind):
    durations=[];delays=[];errors=[]
    expected='owning_tick' if kind=='tick' else 'frame'
    for row in rows:
        if row.get('event')!=expected:continue
        if start<=row['start_ns']<row['end_ns']<=end:
            if row.get('failed'):errors.append('failed owning tick');continue
            durations.append((row['end_ns']-row['start_ns'])/1_000_000)
    if kind=='tick':
        for row in rows:
            if row.get('event')=='tick_metric' and start<=row['start_ns']<row['end_ns']<=end:
                delays.append(max(0,row['start_ns']-row['scheduled_ns'])/1_000_000)
    out={kind+'_samples':len(durations),'errors':errors}
    for label,values in ((('tick_execution' if kind=='tick' else 'frame'),durations),*([('tick_delay',delays)] if kind=='tick' else [])):
        for pct in (50,95,99):out[f'{label}_p{pct}_ms']=percentile(values,pct/100)
    if kind=='tick':out['tick_delay_samples']=len(delays)
    return out


def rss_metrics(rows, subject, owner, start, end):
    """Schedule completeness is derived from the window, not the rows received."""
    selected=[r for r in rows if r.get('subject')==subject and start<=r['scheduled_ns']<end]
    scheduled=math.ceil((end-start)/1_000_000_000)
    valid=[];seen=set();seen_seconds=set();errors=[]
    for row in selected:
        at=row['scheduled_ns']
        if at in seen:errors.append('duplicate RSS observation');continue
        seen.add(at)
        second=(at-start)//1_000_000_000
        if second in seen_seconds:errors.append('multiple RSS observations in scheduled second');continue
        seen_seconds.add(second)
        if row.get('process_identity')!=owner:errors.append('RSS process identity changed');continue
        if not row.get('missing') and isinstance(row.get('rss_bytes'),int) and not isinstance(row.get('rss_bytes'),bool) and row['rss_bytes']>=0:
            valid.append(row['rss_bytes'])
    return {'rss_scheduled':scheduled,'rss_observed':len(valid),'peak_rss_bytes':max(valid) if valid else None,
            'process_identity':owner,'errors':errors}


def eligible_seconds(targets, faults, subject, start, end):
    """Union of offered, unresolved target intervals excluding explicit stalls."""
    events={start:[0,0],end:[0,0]}
    def interval(a,b,kind):
        a=max(start,a);b=min(end,b)
        if a>=b:return
        events.setdefault(a,[0,0])[kind]+=1
        events.setdefault(b,[0,0])[kind]-=1
    for target in targets:
        if target['subject']==subject:interval(target['offered_ns'],target.get('resolved_ns',end),0)
    for fault in faults:
        if fault.get('subject')==subject and fault.get('kind')=='slow-consumer':interval(fault['start_ns'],fault['end_ns'],1)
    active=blocked=elapsed=0;previous=start
    for at,delta in sorted(events.items()):
        if active>0 and blocked==0:elapsed+=at-previous
        active+=delta[0];blocked+=delta[1];previous=at
    return elapsed/1_000_000_000
