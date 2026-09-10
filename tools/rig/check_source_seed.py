"""Native Paper seed prerequisites; deliberately no delivery/handshake claim."""
import json
from pathlib import Path
from rig import read, regular, sha, digest


def inspect(run, manifest):
    run=Path(run); errors=[]
    runtime=read(run/'runtime.json'); scenario=read(run/'scenario.json')
    if scenario.get('execution_route')!='source-seed' or scenario.get('requires_handshake') is not False:
        errors.append('source-seed applicability required')
    launches=runtime.get('launches',[])
    if len(launches)!=1 or launches[0].get('id')!='server' or launches[0].get('stop_stdin')!='stop':
        errors.append('exactly one gracefully stopped server launch required')
    if runtime.get('client_profiles'):errors.append('seed run must not contain client participants')
    server=runtime.get('server_profile',{})
    native=read(Path(server['path']))
    if native.get('platform')!='paper' or native.get('route')!='native' or digest(native)!=server.get('profile_hash') or native.get('id')!=server.get('id'):
        errors.append('native Paper server profile required')
    if digest(runtime)!=manifest.get('runtime_hash') or digest(scenario)!=manifest.get('scenario_hash'):
        errors.append('seed input identity changed')
    path=regular(run/'evidence/server-events.jsonl')
    if path.stat().st_size>4*1024*1024:raise ValueError('seed evidence exceeds bound')
    rows=[json.loads(line) for line in path.read_text().splitlines()]
    if any(row.get('run_id')!=manifest['run_id'] for row in rows):errors.append('foreign seed row')
    ready=[r for r in rows if r.get('event')=='source_preconditions_ready']
    closed=[r for r in rows if r.get('event')=='writer_closed']
    if len(ready)!=1 or type(ready[0].get('time_ns')) is not int:errors.append('source readiness missing or duplicated')
    if len(closed)!=1 or closed[0].get('overflow') is not False or rows[-1]!=closed[0]:errors.append('clean final writer closure required')
    forbidden={'join','session','session_transfer','product_registration','workload_started','edit_applied','oracle_ack_timeout','edit_failed','source_preparation_failed'}
    if any(r.get('event') in forbidden or r.get('players') for r in rows):errors.append('seed run had clients or workload failures')
    oracle=regular(run/'evidence/oracle.jsonl')
    if oracle.stat().st_size:errors.append('seed oracle must have no client targets/sessions')
    if list((run/'evidence').glob('consumer-*.jsonl')):errors.append('client evidence in seed run')
    facts=[r for r in rows if r.get('event')=='source_target_precondition']
    expected={('RigSubject'+chr(65+i),source):(i*256+(-20 if source==2 else 8 if source in (0,1) else -8),-20 if source==2 else 8 if source in (0,3) else -8) for i in range(4) for source in range(4)}
    started=[row for row in rows if row.get('event')=='source_preparation_started']
    start=started[0].get('time_ns') if len(started)==1 else None
    if type(start) is not int:errors.append('initial generation check must precede fixture seeding')
    initial=[row for row in rows if row.get('event')=='source_generation_initial_precondition']
    initial_seen=set()
    for row in initial:
        key=(row.get('subject'),row.get('source'))
        if key not in expected or key[1]!=2 or key in initial_seen:errors.append('unexpected/duplicate initial generation fact');continue
        initial_seen.add(key)
        if (row.get('chunk_x'),row.get('chunk_z'))!=expected[key] or row.get('block_y')!=-64 or row.get('expected_block')!='bedrock':errors.append('initial generation coordinate/block mismatch')
        if any(row.get(field) is not False for field in ('loaded','retained','in_memory_present','disk_present','store_present')):errors.append('generation target exists before fixture seeding')
        if type(row.get('time_ns')) is not int or type(start) is not int or row['time_ns']>start:errors.append('generation initial fact after seeding')
    if initial_seen!={(subject,source) for subject,source in expected if source==2} or len(initial)!=4:errors.append('four initial generation absence observations required')
    seen=set()
    for row in facts:
        key=(row.get('subject'),row.get('source'))
        if key in seen or key not in expected:errors.append('unexpected/duplicate seed target');continue
        seen.add(key)
        source=key[1]
        if (row.get('chunk_x'),row.get('chunk_z'))!=expected[key]:errors.append('seed target coordinate mismatch')
        if row.get('block_y')!=(-64 if source==2 else 64) or row.get('expected_block')!=('bedrock' if source==2 else 'gold_block'):errors.append('seed target block specification mismatch')
        if any(type(row.get(k)) is not bool for k in ('loaded','retained','store_present','disk_present','in_memory_present')):errors.append('seed source facts must be booleans')
        if source==0:
            if row.get('loaded') is not True or row.get('retained') is not True or row.get('in_memory_present') is not True:errors.append('loaded target not retained')
        elif row.get('loaded') is not False or row.get('retained') is not False or row.get('store_present')!=(source==3) or row.get('disk_present')!=(source!=2):
            errors.append('store/disk/untouched-generation prerequisite failed')
        if source==2 and row.get('in_memory_present') is not False:errors.append('generation target has a pending/native protochunk')
        if type(start) is not int or type(row.get('time_ns')) is not int or row['time_ns']<start:errors.append('final seed fact precedes fixture seeding')
        if not ready or type(row.get('time_ns')) is not int or row['time_ns']>ready[0].get('time_ns',0):errors.append('seed fact not observed before readiness')
    if set(expected)!=seen or len(facts)!=16:errors.append('all sixteen source facts required')
    measured=[r for r in rows if r.get('event')=='measured_loaded_precondition']
    flags=[arg for launch in launches for arg in launch.get('argv',[]) if arg.startswith('-Dlss.rig.measuredWorkload=')]
    if flags and flags not in (['-Dlss.rig.measuredWorkload=true'],['-Dlss.rig.measuredWorkload=false']):errors.append('ambiguous measured seed mode')
    expected_measured={(f'RigSubject{chr(65+i)}',i*256+x,z) for i in range(4) for x in range(12,28) for z in range(-7,9)} if flags==['-Dlss.rig.measuredWorkload=true'] else set()
    measured_seen=set()
    for row in measured:
        key=(row.get('subject'),row.get('chunk_x'),row.get('chunk_z'))
        if key in measured_seen or key not in expected_measured:errors.append('unexpected/duplicate measured seed cell')
        measured_seen.add(key)
        if (row.get('source')!=0 or row.get('block_y')!=64 or row.get('expected_block')!='gold_block'
                or row.get('loaded') is not True or row.get('retained') is not True or row.get('disk_present') is not True):
            errors.append('measured loaded cell not durably seeded and retained')
        if not ready or type(row.get('time_ns')) is not int or row['time_ns']>ready[0].get('time_ns',0):errors.append('measured seed fact after readiness')
    if measured_seen!=expected_measured or len(measured)!=len(expected_measured):errors.append('measured seed domain incomplete')
    prefill=None
    if runtime.get('prefill_construction_hash'):
        from mca_fixture import verify_full,HOLES
        construction=read(run/'prefill-construction.json')
        if digest(construction)!=runtime['prefill_construction_hash'] or construction.get('operation')!='explicit-offline-fixture-hole-construction' or construction.get('after',{}).get('holes')!=[list(point) for point in HOLES]:
            errors.append('prefill construction identity/geometry mismatch')
        prefill=verify_full(run/'server/world',holes=True)
    stop=read(run/'evidence/source-seed-stop.json')
    if stop.get('run_id')!=manifest['run_id'] or type(stop.get('server_returncode')) is not int or stop['server_returncode']!=0:errors.append('clean server exit required')
    return {'status':'failed' if errors else 'passed','errors':errors,'test_count':len(facts),'measured_target_count':len(measured),'prefill_saved_full':prefill,'artifacts':{str(p.relative_to(run/'evidence')):sha(p) for p in (path,oracle,run/'evidence/source-seed-stop.json')}}


def check_report(proof,manifest,scenario,run):
    try:
        result=inspect(run,manifest)
        if proof.get('source_seed_report')!=result: return ['source seed report differs from checked raw evidence']
        if proof.get('test_count')!=16 or type(proof.get('test_count')) is not int:return ['source seed requires sixteen observed targets']
        return result['errors']
    except (OSError,ValueError,TypeError,KeyError,IndexError) as error:
        return ['source seed evidence invalid: '+str(error)]
