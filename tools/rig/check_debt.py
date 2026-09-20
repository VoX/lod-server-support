"""Check preregistered capacity and actual zero-debt drain observations."""
PLAYER_FIELDS=('held_sync','held_gen','send_queue','backlog')
GLOBAL_FIELDS=(('disk','pending'),('generation','active'),('store','queue'))


def check(rows,bounds,drain_start_ns,required_subjects):
    errors=[];metrics=[row for row in rows if row.get('event')=='product_metrics']
    required=set(required_subjects)
    if not metrics:return {'status':'failed','errors':['product debt samples absent']}
    if set(bounds)!={*PLAYER_FIELDS,'disk.pending','generation.active','store.queue'}:
        return {'status':'failed','errors':['preregistered capacity bounds incomplete']}
    zero_samples=[]
    for row in metrics:
        values={};names=set()
        for player in row.get('players',[]):
            name=player.get('name');names.add(name)
            if name not in required:
                errors.append('unregistered workload subject in debt samples');continue
            for field in PLAYER_FIELDS:values[name+'.'+field]=(field,player.get(field))
        for group,field in GLOBAL_FIELDS:values[group+'.'+field]=(group+'.'+field,row.get(group,{}).get(field))
        for name,(bound,value) in values.items():
            ceiling=bounds[bound]
            if not isinstance(ceiling,int) or ceiling<0 or not isinstance(value,int) or not 0<=value<=ceiling:
                errors.append('capacity missing/exceeded: '+name)
        # Drain is witnessed while all expected registered subjects still exist;
        # an empty player map after destruction cannot prove debt release.
        if (names==required and row.get('time_ns',0)>=drain_start_ns
                and values and all(isinstance(value,int) and value==0 for _,value in values.values())):
            zero_samples.append(row['time_ns'])
    zero_samples=sorted(set(zero_samples))
    witnessed=next((b for a,b in zip(zero_samples,zero_samples[1:]) if b-a>=1_000_000_000 and b-drain_start_ns<=120_000_000_000),None)
    if witnessed is None:errors.append('two bounded zero-debt observations before drain deadline absent')
    return {'status':'failed' if errors else 'passed','errors':sorted(set(errors)),
            'drain_seconds':None if witnessed is None else (witnessed-drain_start_ns)/1_000_000_000}
