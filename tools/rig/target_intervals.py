"""Strict independently applied cell revisions. No superseded successes."""
LIMIT=120_000_000_000

def recovery_origin(offered, faults, as_of):
    origin=offered
    for fault in sorted(faults,key=lambda row:row['start_ns']):
        start,end=fault['start_ns'],fault['end_ns']
        if type(start) is not int or type(end) is not int or end<start:
            raise ValueError('invalid registered fault interval')
        if start>as_of:break
        if end<offered:continue
        # A later fault cannot resurrect an obligation that already expired.
        if start>origin+LIMIT:break
        origin=max(origin,end)
    return origin

def intervals(oracle, strict=False):
    targets={};applied={};cells={};errors=[]
    measured_run=any('target_sequence' in row for row in oracle if row.get('event')=='target')
    for row in oracle:
        if row.get('event')=='target':
            if row['id'] in targets:errors.append('duplicate target revision')
            targets[row['id']]=row
        elif row.get('event') in ('edit_applied','target_ready'):
            if row['id'] in applied:errors.append('duplicate owner application')
            applied[row['id']]=row
    for id,target in targets.items():
        required=('chunk_x','chunk_z','block_y','dimension','world_generation','cell_revision','predecessor_id')
        measured='target_sequence' in target
        if strict and measured and type(target.get('target_sequence')) is not int:
            errors.append('missing measured offer sequence: '+id)
        if strict and any(key not in target for key in required):
            errors.append('missing strict cell revision evidence: '+id);continue
        if not all(key in target for key in required):continue
        revision=target['cell_revision'];event=applied.get(id)
        if type(revision) is not int or revision<1:errors.append('invalid cell revision: '+id);continue
        if event is None:continue  # Main checker rejects absent application.
        if event.get('cell_revision')!=revision or event.get('predecessor_id')!=target['predecessor_id']:
            errors.append('owner revision differs from offered revision: '+id)
        key=(target['subject'],target['dimension'],target['world_generation'],target['chunk_x'],target['chunk_z'],target['block_y'])
        cells.setdefault(key,[]).append((revision,id,event['time_ns'],target))
    bounds={}
    for rows in cells.values():
        rows.sort()
        for index,(revision,id,at,target) in enumerate(rows):
            previous=rows[index-1] if index else None
            if revision!=index+1 or target['predecessor_id']!=(previous[1] if previous else None):
                errors.append('noncontiguous cell revision chain: '+id)
            if previous and (at<=previous[2] or target['offered_ns']<=previous[3]['offered_ns']):
                errors.append('owner cell application order changed: '+id)
            bounds[id]=(at,rows[index+1][2] if index+1<len(rows) else None)
    return bounds,errors

def delivery_errors(target,row,bounds,strict=False):
    errors=[];id=target['id'];measured='target_sequence' in target or strict and target.get('requires_ack') is True
    if id in bounds:
        start,end=bounds[id]
        received,resolved=row.get('body_received_ns'),row.get('resolved_ns')
        if (type(received) is not int or type(resolved) is not int or received<start or resolved<received
                or end is not None and resolved>=end):errors.append('delivery outside original current revision: '+id)
        if row.get('cell_revision')!=target['cell_revision']:errors.append('wrong delivery cell revision: '+id)
        for field in ('chunk_x','chunk_z','dimension','world_generation'):
            if row.get(field)!=target.get(field):errors.append('wrong delivery cell identity: '+id)
    if strict:
        body=row.get('body_id');capture=row.get('wire_capture_id')
        valid=lambda value:type(value) is int and value>=0 or isinstance(value,str) and bool(value)
        if not valid(body) or not valid(capture) or row.get('wire_association')!='exact':
            errors.append('missing unambiguous wire-to-receipt association: '+id)
    return errors

def wire_errors(rows, strict):
    if not strict:return []
    errors=[];captures={};bindings={}
    for row in rows:
        if row.get('event')!='wire_capture':continue
        key=(row.get('connection_id'),row.get('wire_capture_id'))
        if key in captures:errors.append('duplicate wire capture identity')
        captures[key]=row
    for row in rows:
        if row.get('event')!='target_committed':continue
        key=(row.get('connection_id'),row.get('wire_capture_id'));wire=captures.get(key)
        if wire is None:errors.append('commit lacks independent wire capture: '+row['id']);continue
        for field in ('chunk_x','chunk_z','body_bytes','column_timestamp','local_session','dimension','source'):
            if field not in wire or wire.get(field)!=row.get(field):errors.append('wire/callback field mismatch: '+field)
        if wire.get('arrival_ns')!=row.get('body_received_ns'):errors.append('wire receipt time mismatch')
        body=row.get('body_id')
        if key in bindings and bindings[key]!=body:errors.append('wire capture reused by distinct callback receipts')
        bindings[key]=body
    return errors
