#!/usr/bin/env python3
"""Own exact native server and two sequential fresh client processes; no external PID actions."""
import hashlib,json,os,queue,subprocess,sys,threading,time
from pathlib import Path
from store_witness import observe
from check_server_smoke import check

def run(root,command):
    root=Path(root).resolve();manifest=json.loads((root/'manifest.json').read_text());run_id=manifest['run_id'];events=[];children=[];logs=[];deadline=time.monotonic()+360
    evidence=root/'evidence';evidence.mkdir(exist_ok=True)
    def emit(event,**fields):
        row=dict(event=event,run_id=run_id,time_ns=time.monotonic_ns(),**fields);events.append(row);return row
    def bound():
        if time.monotonic()>deadline:raise TimeoutError('native server smoke deadline expired')
    def argv(parts):return [p.replace('{run}',str(root)).replace('{run_id}',run_id) for p in parts]
    def launch(spec,name):
        log=(evidence/(name+'.log')).open('x');logs.append(log)
        proc=subprocess.Popen(argv(spec['argv']),cwd=root/spec['cwd'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,env=dict(os.environ,**spec.get('env',{})))
        children.append(proc);records=[]
        def pump():
            for line in proc.stdout:
                records.append((time.monotonic_ns(),line));log.write(line);log.flush()
        threading.Thread(target=pump,daemon=True).start();return proc,records
    def wait(predicate,process=None):
        while True:
            bound();value=predicate()
            if value:return value
            if process is not None and process.poll() is not None:raise RuntimeError('native process exited before required evidence')
            time.sleep(.05)
    def rows(phase):
        path=evidence/('client-'+phase+'.jsonl')
        if not path.exists():return []
        raw=path.read_bytes();return [json.loads(line) for line in raw[:raw.rfind(b'\n')+1].splitlines()]
    def client_event(phase,event):
        values=[r for r in rows(phase) if r.get('event')==event]
        if len(values)>1:raise ValueError('duplicate client phase event')
        return values[0] if values else None
    def validate_body(phase,body):
        captures=[r for r in rows(phase) if r.get('event')=='wire_capture' and r.get('wire_capture_id')==body.get('wire_capture_id')]
        if len(captures)!=1:raise ValueError('body missing unique raw wire capture')
        capture=captures[0]
        for field in ('run_id','connection_id','source','chunk_x','chunk_z','column_timestamp','body_hex'):
            if capture.get(field)!=body.get(field):raise ValueError('wire/body association mismatch: '+field)
        if capture['arrival_ns']!=body['received_ns']:raise ValueError('wire/body arrival mismatch')
    server=None;failure=None
    try:
        if (root/'server/world').exists():raise ValueError('owned smoke world already exists')
        emit('world_fresh',world_preexisting=False)
        server,server_rows=launch(command['server'],'smoke-server')
        wait(lambda:any('Done (' in line for _,line in server_rows),server)
        def console(text,expect=None):
            start=len(server_rows);token='LSS_SMOKE_'+str(time.monotonic_ns());server.stdin.write(text+'\nsay '+token+'\n');server.stdin.flush()
            wait(lambda:any(token in line for _,line in server_rows[start:]),server)
            response=''.join(line for _,line in server_rows[start:]);
            if expect and expect not in response:raise ValueError('native console premise missing: '+expect)
            return response
        for text in command['recipe']['native_console_setup']:
            if not text.startswith('forceload remove'):console(text)
        expected=command['recipe']['target']['expected_blocks']
        for position,block in expected.items():console('execute if block '+position.replace(',',' ')+' '+block+' run say LSS_SMOKE_BLOCK_OK','LSS_SMOKE_BLOCK_OK')
        emit('target_seeded',native_expected_blocks=expected)
        console('save-all flush');console('forceload remove 128 128')
        wait(lambda:'LSS_SMOKE_UNLOADED' in console('execute unless loaded 128 64 128 run say LSS_SMOKE_UNLOADED'),server)
        emit('target_unloaded',native_loaded=False)
        db=root/command['store_database'];absent=observe(db,root,'minecraft:overworld',8,8)
        if absent['present']:raise ValueError('target store row predates actual client disk delivery')
        emit('store_absent',**absent)
        for phase in ('first','second'):
            start=len(server_rows);client,clientlog=launch(command['clients'][phase],'smoke-client-'+phase)
            client_handshake=wait(lambda:client_event(phase,'client_handshake'),client)
            if client_handshake.get('protocol')!=20 or client_handshake.get('run_id')!=run_id:raise ValueError('native client v20 identity missing')
            wait(lambda:any('handshake received from RigSubjectA (protocol v20,' in line for _,line in server_rows[start:]),server)
            # Preserve actual client-config observation time; body can arrive while server log worker drains.
            event=emit('handshake_'+phase,protocol=20,server_observed=True,client_observed=True,connection_id=client_handshake['connection_id']);event['time_ns']=client_handshake['time_ns']
            body=wait(lambda:client_event(phase,'body_'+phase),client);validate_body(phase,body);events.append(body)
            if phase=='first':
                persisted=wait(lambda:(r if (r:=observe(db,root,'minecraft:overworld',8,8))['present'] else None),server)
                emit('store_persisted',**persisted)
            (evidence/('stop-'+phase)).write_text('stop\n')
            wait(lambda:client.poll() is not None)
            closed=wait(lambda:client_event(phase,'client_closed'))
            if closed.get('overflow'):raise ValueError('client evidence overflow')
            if phase=='first':
                wait(lambda:any('RigSubjectA lost connection:' in line for _,line in server_rows[start:]),server)
                emit('disconnect_first',connection_id=client_handshake['connection_id'])
        console('save-all flush');server.stdin.write('stop\n');server.stdin.flush();wait(lambda:server.poll() is not None)
    except Exception as error:failure=str(error)
    finally:
        for child in reversed(children):
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
        for log in logs:log.flush()
    emit('cleanup',complete=all(p.poll() is not None for p in children),owned_processes_alive=sum(p.poll() is None for p in children))
    value=dict(run_id=run_id,target=command['recipe']['target'],events=events)
    path=evidence/'native-server-smoke.json';path.write_text(json.dumps(value,indent=2)+'\n')
    result=check(value)
    if failure:result['status']='failed';result['errors'].append(failure)
    (evidence/'native-server-smoke-result.json').write_text(json.dumps(result,indent=2)+'\n')
    proof={key:manifest[key] for key in ('run_id','run_hash','scenario_hash','profile_hash')}
    proof.update(ready=server is not None,handshake=any(r.get('event')=='handshake_second' for r in events),test_count=3 if result.get('status')=='passed' else 0,assertions=result['assertions'] if result.get('status')=='passed' else {},failures=result['errors'],evidence={'native-server-smoke.json':hashlib.sha256(path.read_bytes()).hexdigest()})
    proof['server_smoke_report']=dict(artifact='native-server-smoke.json',artifact_sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    from check_server_smoke_report import check_report
    proof['failures'].extend(check_report(proof,manifest,command['recipe'],root))
    if proof['failures']:proof['assertions']={}
    (root/'proof.json').write_text(json.dumps(proof,indent=2)+'\n')
    # Rig collects proof then stops this owned wrapper, as with native gametest wrapper.
    while True:time.sleep(1)
if __name__=='__main__':
    root=Path(sys.argv[1]);run(root,json.loads((root/'server-smoke-command.json').read_text()))
