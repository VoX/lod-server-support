"""Read-only actual bridge progress gate; no synthetic map/render acceptance."""

def check(consumers,start_ns,end_ns,gpu):
    errors=[];deltas={}
    renderer=str(gpu.get('renderer','')).split('OpenGL renderer string:',1)[-1].strip().lower()
    if gpu.get('accelerated') is not True or not renderer or any(word in renderer for word in ('llvmpipe','softpipe')):
        errors.append('actual accelerated GPU evidence absent')
    expected={'RigSubject'+letter for letter in 'ABCD'}
    if set(consumers)!=expected:errors.append('four Xaero observer subjects required')
    for subject,events in consumers.items():
        actual=[row for row in events if row.get('event')=='client_gpu']
        actual_renderer=str(actual[0].get('renderer','')).strip().lower() if len(actual)==1 else ''
        if len(actual)!=1 or actual[0].get('context_current') is not True or actual_renderer in ('','null','unknown') or any(word in actual_renderer for word in ('llvmpipe','softpipe')):
            errors.append('actual game GPU context missing or software-rendered: '+subject)
        observed=[row for row in events if row.get('event')=='xaero_bridge']
        if any(type(row.get('time_ns')) is not int for row in observed):errors.append('invalid Xaero observation timestamp: '+subject)
        rows=[row for row in observed if type(row.get('time_ns')) is int and start_ns<=row['time_ns']<=end_ns]
        previous_time=-1;previous_generation=-1;generations={};valid=True
        for row in rows:
            now=row.get('time_ns');generation=row.get('bridge_generation');written=row.get('written')
            if (type(now) is not int or now<=previous_time or type(generation) is not int or generation<previous_generation
                    or type(written) is not int or type(row.get('instance_present')) is not bool):
                errors.append('invalid Xaero observation identity/order: '+subject);valid=False;break
            previous_time=now;previous_generation=generation
            if not row['instance_present']:
                if written!=-1:errors.append('absent bridge supplied write counter: '+subject);valid=False
                continue
            if written<0:errors.append('actual bridge counter unavailable: '+subject);valid=False;continue
            values=generations.setdefault(generation,[])
            if values and written<values[-1]:errors.append('bridge counter regressed within instance: '+subject);valid=False
            values.append(written)
        delta=sum(values[-1]-values[0] for values in generations.values())
        deltas[subject]=delta
        if not valid or delta<=0:errors.append('actual Xaero writes did not progress during measurement: '+subject)
    return {'status':'failed' if errors else 'passed','written_deltas':deltas,'errors':errors}
