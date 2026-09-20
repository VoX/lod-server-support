"""Native server acceptance: two real sessions, disk→persistent store→store body."""
import hashlib,json

def check(evidence):
    errors=[]
    def require(ok,message):
        if not ok:errors.append(message)
    run=evidence['run_id'];rows=evidence['events'];target=evidence['target']
    require(all(row.get('run_id')==run for row in rows),'foreign run evidence')
    required=['world_fresh','target_seeded','target_unloaded','store_absent','handshake_first','body_first','store_persisted','disconnect_first','handshake_second','body_second','cleanup']
    by={}
    for row in rows:
        if row.get('event') in required:
            require(row['event'] not in by,'duplicate phase evidence');by[row['event']]=row
    require(set(by)==set(required),'missing native phase')
    if set(by)!=set(required):return dict(status='failed',errors=errors)
    previous=-1
    for event in required:
        now=by[event].get('time_ns');require(type(now) is int and now>previous,'phase order/timing invalid')
        if type(now) is int:previous=now
    require(by['world_fresh'].get('world_preexisting') is False,'world was not fresh')
    require(by['store_absent'].get('present') is False,'preexisting target store row')
    require(by['target_unloaded'].get('native_loaded') is False,'target still native loaded')
    require(by['target_seeded'].get('native_expected_blocks')==target['expected_blocks'],'native target seed differs from independent recipe')
    first=by['handshake_first'];second=by['handshake_second']
    for row in (first,second):
        require(row.get('protocol')==20 and row.get('server_observed') is True and row.get('client_observed') is True,'actual two-sided v20 handshake absent')
    require(first.get('connection_id')!=second.get('connection_id'),'second connection reused old identity')
    require(by['disconnect_first'].get('connection_id')==first.get('connection_id'),'first session retirement unproved')
    for name,connection,source in [('body_first',first['connection_id'],1),('body_second',second['connection_id'],3)]:
        row=by[name]
        require(row.get('connection_id')==connection and row.get('lease_active') is True,'foreign/stale delivery receipt')
        require(row.get('source')==source,'wrong native source route: '+name)
        require(row.get('chunk_x')==target['chunk_x'] and row.get('chunk_z')==target['chunk_z'] and row.get('dimension')==target['dimension'],'wrong target body')
        require(row.get('decoded_blocks')==target['expected_blocks'],'independent decoded target mismatch')
        require(row.get('wire_association')=='exact' and type(row.get('wire_capture_id')) is int and type(row.get('body_id')) is int,'missing exact wire/receipt association')
        require(type(row.get('body_bytes')) is int and row['body_bytes']>0,'missing actual body bytes')
        require(type(row.get('received_ns')) is int and row['received_ns']<=row['time_ns'],'invalid body receipt time')
        raw=bytes.fromhex(row.get('body_hex',''))
        require(row.get('body_bytes')==len(raw),'body size differs from native bytes')
        require(row.get('body_sha256')==hashlib.sha256(raw).hexdigest(),'body digest mismatch')
        handshake=first if name=='body_first' else second
        require(type(row.get('received_ns')) is int and row['received_ns']>handshake['time_ns'],'body predates actual session config')
    persisted=by['store_persisted']
    require(persisted.get('present') is True and persisted.get('wire_format')==20 and persisted.get('uncompressed_bytes',0)>0,'committed v20 store row absent')
    require(persisted.get('column_timestamp')==by['body_first'].get('column_timestamp')==by['body_second'].get('column_timestamp'),'persisted/body timestamp mismatch')
    require(persisted.get('uncompressed_bytes')==by['body_first'].get('body_bytes')==by['body_second'].get('body_bytes'),'persisted/body size mismatch')
    require(persisted.get('raw_body_sha256')==by['body_first']['body_sha256'],'persisted content differs from actual disk body')
    require(by['body_first']['body_sha256']==by['body_second']['body_sha256'],'disk/store body byte parity failed')
    require(by['body_second']['received_ns']>second['time_ns'],'second body predates its handshake')
    require(by['cleanup'].get('complete') is True and by['cleanup'].get('owned_processes_alive')==0,'cleanup incomplete')
    return dict(status='failed' if errors else 'passed',errors=errors,assertions={'store_write':not errors,'store_read':not errors,'body_matches_expected':not errors},scope='native-server-store-smoke',performance_acceptance=False)
