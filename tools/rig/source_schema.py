"""Required current-revision and native loaded-owner evidence for new source runs."""
SUBJECTS={'RigSubject'+letter for letter in 'ABCD'}

def check(oracle, events, scenario, platform="folia"):
    errors=[]
    if scenario.get('target_schema')!=2:
        errors.append('source scenario must pin target_schema=2')
    targets=[r for r in oracle if r.get('event')=='target']
    required=('dimension','world_generation','cell_revision','predecessor_id')
    applied={};owners={}
    sessions={r.get('connection_id'):r for r in oracle if r.get('event')=='session'}
    ends={r.get('connection_id'):r for r in events if r.get('event') in ('quit','session_end')}
    initial=set()
    for target in targets:
        if not str(target.get('id','')).endswith('-initial'):continue
        subject,source=target.get('subject'),target.get('expected_source')
        key=(subject,source)
        if subject not in SUBJECTS or type(source) is not int or source not in range(4) or key in initial:
            errors.append('unexpected/duplicate initial source target');continue
        initial.add(key);offset=(ord(subject[-1])-65)*256
        coords=(offset+(-20 if source==2 else 8 if source in (0,1) else -8),-20 if source==2 else 8 if source in (0,3) else -8)
        block='diamond_block' if source==0 else 'bedrock' if source==2 else 'gold_block'
        if (target.get('chunk_x'),target.get('chunk_z'))!=coords or target.get('block_y')!=(-64 if source==2 else 64) or target.get('expected_block')!=block:
            errors.append('initial source oracle differs from native seeded geometry')
    if initial!={(subject,source) for subject in SUBJECTS for source in range(4)}:
        errors.append('sixteen initial source oracle targets required')
    for row in oracle:
        if row.get('event')=='edit_applied':applied[row['id']]=row
        if row.get('event')=='target_owner_precondition':
            if row['id'] in owners:errors.append('duplicate loaded owning-region premise')
            owners[row['id']]=row
    for target in targets:
        if any(key not in target for key in required):errors.append('missing mandatory source revision schema');continue
        if target.get('dimension')!='minecraft:overworld' or not isinstance(target.get('world_generation'),str) or not target['world_generation']:
            errors.append('invalid source world identity')
        if type(target.get('cell_revision')) is not int or target['cell_revision']<1:errors.append('invalid source cell revision')
        if target.get('expected_source')!=0:continue
        fact=owners.get(target['id']);mutation=applied.get(target['id'])
        if target.get('requires_ack') is not True or not fact or not mutation:
            errors.append('loaded target lacks ack-bound native mutation/ownership');continue
        if platform=='folia' and (fact.get('owns_region') is not True or fact.get('owner_name')!=target.get('subject')):
            errors.append('loaded target not owned by current player region')
        if any(fact.get(key)!=target.get(key) for key in ('subject','chunk_x','chunk_z')):
            errors.append('loaded owner premise addresses another target')
        observed,at,changed=fact.get('observed_ns'),fact.get('time_ns'),mutation.get('time_ns')
        if any(type(value) is not int for value in (observed,at,changed)) or not observed<=at<=changed or at-observed>250_000_000:
            errors.append('loaded owner premise stale or after mutation')
        if platform=='folia':
            region=fact.get('region_identity')
            if not isinstance(region,str) or not region or mutation.get('owner_region_identity')!=region:
                errors.append('loaded mutation region differs from native owner premise')
        else:
            owner=fact.get('owner_identity');thread=fact.get('thread_id')
            if fact.get('owner_kind')!='server-thread' or fact.get('owns_thread') is not True or type(thread) is not int or thread<1 or owner!='server-thread:'+str(thread) or mutation.get('owner_identity')!=owner or mutation.get('owner_kind')!='server-thread':
                errors.append('loaded mutation lacks actual native server-thread ownership')
        if not isinstance(fact.get('connection_id'),str) or not fact['connection_id']:
            errors.append('loaded owner premise lacks current connection')
        session=sessions.get(fact.get('connection_id'));end=ends.get(fact.get('connection_id'))
        if not session or not end or session.get('subject')!=target.get('subject') or end.get('subject')!=target.get('subject') or any(type(value) is not int for value in (session.get('time_ns'),end.get('time_ns'),at)) or not session['time_ns']<=at<=end['time_ns']:
            errors.append('loaded owner premise outside current source session')
    if not any('target_sequence' in target for target in targets):
        expected={(subject,(ord(subject[-1])-65)*256+x,0) for subject in SUBJECTS for x in (12,13,14)}
        seen=set()
        ready=[r.get('time_ns') for r in events if r.get('event')=='source_preconditions_ready']
        for row in events:
            if row.get('event')!='diagnostic_loaded_precondition':continue
            key=(row.get('subject'),row.get('chunk_x'),row.get('chunk_z'))
            if key not in expected or key in seen:errors.append('unexpected/duplicate diagnostic loaded cell')
            seen.add(key)
            if row.get('source')!=0 or row.get('block_y')!=64 or row.get('expected_block')!='gold_block' or any(row.get(k) is not True for k in ('loaded','retained','in_memory_present','disk_present')):
                errors.append('diagnostic edit cell not prepared and retained')
            if len(ready)!=1 or type(row.get('time_ns')) is not int or type(ready[0]) is not int or row['time_ns']>ready[0]:errors.append('diagnostic cell premise after readiness')
        if seen!=expected:errors.append('twelve distinct diagnostic loaded seed cells required')
        edits=[t for t in targets if t.get('expected_source')==0 and not t['id'].endswith('-initial')]
        if len(edits)!=12 or {(t.get('subject'),t.get('chunk_x'),t.get('chunk_z')) for t in edits}!=expected:
            errors.append('diagnostic edits must use twelve distinct prepared cells')
    return list(dict.fromkeys(errors))
