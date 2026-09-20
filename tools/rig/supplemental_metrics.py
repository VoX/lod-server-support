"""Descriptive latency/GC observations, separate from preregistered regression metrics."""
from metrics import percentile


def gc_delta(rows,start,end):
    samples=[]
    for row in rows:
        if not start<=row.get('time_ns',-1)<=end:continue
        if row.get('event')=='jvm_gc':sample=row
        elif row.get('event')=='product_metrics':sample=row.get('jvm',{})
        else:continue
        samples.append((row['time_ns'],sample))
    samples.sort(key=lambda item:item[0])
    out={'samples':len(samples),'observed_start_ns':samples[0][0] if samples else None,
         'observed_end_ns':samples[-1][0] if samples else None}
    for key in ('gc_count','gc_time_ms'):
        values=[sample.get(key) for _,sample in samples]
        valid=len(values)>=2 and all(type(value) is int and value>=0 for value in values)
        valid=valid and all(a<=b for a,b in zip(values,values[1:]))
        out[key+'_delta']=values[-1]-values[0] if valid else None
    return out


def assemble(targets,consumers,server_events,start,end):
    result={}
    for subject,rows in consumers.items():
        cohort=[target for target in targets if target['subject']==subject and start<=target['offered_ns']<end]
        durations=[]
        for target in cohort:
            if type(target.get('resolved_ns')) is not int or target['resolved_ns']<target['offered_ns']:
                raise ValueError('latency cohort contains unresolved or reversed target')
            durations.append((target['resolved_ns']-target['offered_ns'])/1_000_000)
        result[subject]={'latency_samples':len(durations),**{f'offer_to_commit_p{percent}_ms':percentile(durations,percent/100) for percent in (50,95,99)},
                         'gc':gc_delta(rows,start,end)}
    result['server']={'gc':gc_delta(server_events,start,end)}
    return {'subjects':result,'latency_cohort':'targets offered during measurement, including outcomes in bounded drain and declared fault time',
            'gc_interval':'difference across actual first/last observations inside measurement; missing/reset/unknown counters remain null',
            'regression_gate':False}
