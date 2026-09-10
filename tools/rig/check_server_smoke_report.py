"""Recompute semantic smoke results from retained native evidence at collection."""
import hashlib,json
from pathlib import Path
from check_server_smoke import check

def check_report(proof,manifest,scenario,root):
    errors=[];root=Path(root);evidence=root/'evidence';ref=proof.get('server_smoke_report',{})
    if scenario.get('execution_route')!='native-server-smoke' or scenario.get('requires_handshake') is not True:return ['native server smoke requires real handshake route']
    if ref.get('artifact')!='native-server-smoke.json':return ['native server smoke report absent']
    path=evidence/'native-server-smoke.json'
    if path.is_symlink() or not path.is_file():return ['native server smoke report missing/unsafe']
    if hashlib.sha256(path.read_bytes()).hexdigest()!=ref.get('artifact_sha256'):return ['native server smoke report hash mismatch']
    try:
        value=json.loads(path.read_text());result=check(value)
        if value.get('run_id')!=manifest['run_id']:errors.append('foreign server smoke run')
        if value.get('target')!=scenario.get('target'):errors.append('native smoke target differs from immutable scenario')
        errors.extend(result['errors'])
        server=(evidence/'smoke-server.log').read_text(errors='replace')
        if 'Done (' not in server or server.count('handshake received from RigSubjectA (protocol v20,')!=2:errors.append('actual server startup/two v20 handshake observations absent')
        events={row['event']:row for row in value['events']}
        for phase in ('first','second'):
            raw=(evidence/('client-'+phase+'.jsonl')).read_bytes()
            if not raw.endswith(b'\n'):errors.append('truncated native client evidence');continue
            rows=[json.loads(line) for line in raw.splitlines()]
            if any(row.get('run_id')!=manifest['run_id'] for row in rows):errors.append('foreign native client rows')
            bodies=[row for row in rows if row.get('event')=='body_'+phase]
            if len(bodies)!=1 or bodies[0]!=events.get('body_'+phase):errors.append('normalized body differs from native receipt evidence');continue
            body=bodies[0];captures=[row for row in rows if row.get('event')=='wire_capture' and row.get('wire_capture_id')==body.get('wire_capture_id')]
            if len(captures)!=1:errors.append('native exact payload capture absent');continue
            wire=captures[0]
            for field in ('connection_id','source','chunk_x','chunk_z','column_timestamp','body_hex'):
                if wire.get(field)!=body.get(field):errors.append('native wire/callback mismatch: '+field)
            if wire.get('arrival_ns')!=body.get('received_ns'):errors.append('native wire arrival mismatch')
            configs=[row for row in rows if row.get('event')=='client_handshake']
            if len(configs)!=1 or configs[0].get('protocol')!=20 or configs[0].get('connection_id')!=body.get('connection_id'):errors.append('native client accepted v20 session absent')
            closed=[row for row in rows if row.get('event')=='client_closed']
            if len(closed)!=1 or closed[0].get('overflow') is not False:errors.append('native client journal cleanup absent')
        if proof.get('test_count')!=3 or proof.get('assertions')!=result['assertions']:errors.append('server smoke assertion/count differs from native evidence')
    except (KeyError,ValueError,OSError,TypeError) as error:errors.append('invalid native smoke evidence: '+str(error))
    return errors
