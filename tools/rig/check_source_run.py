"""Run-owned mixed-source correctness proof, recomputed at collection."""
import json
from pathlib import Path
from rig import read, regular, sha, digest
from check_concurrent_sources import check as source_check
from check_regions import check as region_check, handshakes
from source_schema import check as schema_check
from native_sessions import check as native_sessions
from check_measured_schedule import check as schedule_check
from normalize_workload import normalize
from performance import correctness

SUBJECTS={'RigSubject'+letter for letter in 'ABCD'}
ASSERTIONS=('independent_target_delivery','actual_payload_source','bounded_debt_drain','owning_region_overlap')


def sustained_progress(oracle, consumers, events, platform, origin, debt, regions):
    """Validate all 24 thirty-second windows, including warmup, without metric claims.

    The owning controller separately certifies process cleanup. This pure raw
    workload gate supplies that delegated prerequisite only; it cannot mint a
    cleanup receipt or performance result.
    """
    try:
        normalized = normalize(oracle, consumers, events, platform=platform,
            start_ns=origin, end_ns=origin + 720_000_000_000,
            debt_result=debt, cleanup_complete=True, region_result=regions)
        errors = correctness(normalized)
        return {'status': 'failed' if errors else 'passed', 'errors': errors,
                'windows': normalized['progress_windows'],
                'scope': 'independent-oracle-progress-only'}
    except (KeyError, TypeError, ValueError) as error:
        return {'status': 'failed', 'errors': ['sustained progress unproven: ' + str(error)]}


def source_premises(events):
    errors=[];seen=set()
    ready=[r for r in events if r.get('event')=='source_preconditions_ready']
    ready_ns=ready[0].get('time_ns') if len(ready)==1 else None
    for row in (r for r in events if r.get('event')=='source_target_precondition'):
        subject=row.get('subject');source=row.get('source');key=(subject,source)
        if subject not in SUBJECTS or type(source) is not int or source not in range(4) or key in seen:
            errors.append('unexpected/duplicate native source premise');continue
        seen.add(key);offset=(ord(subject[-1])-65)*256
        coords=(offset+(-20 if source==2 else 8 if source in (0,1) else -8),-20 if source==2 else 8 if source in (0,3) else -8)
        if (row.get('chunk_x'),row.get('chunk_z'))!=coords or row.get('block_y')!=(-64 if source==2 else 64) or row.get('expected_block')!=('bedrock' if source==2 else 'gold_block'):
            errors.append('native source coordinate/block premise mismatch')
        if any(type(row.get(k)) is not bool for k in ('loaded','retained','in_memory_present','store_present','disk_present')):
            errors.append('native source premise must use observed booleans')
        if source==0:
            if not all(row.get(k) is True for k in ('loaded','retained','in_memory_present')):errors.append('loaded source not retained')
        elif row.get('loaded') is not False or row.get('retained') is not False or row.get('store_present')!=(source==3) or row.get('disk_present')!=(source!=2):
            errors.append('native store/disk/generation premise failed')
        if source==2 and row.get('in_memory_present') is not False:errors.append('generation source already has native protochunk')
        if type(row.get('time_ns')) is not int or type(ready_ns) is not int or row['time_ns']>ready_ns:errors.append('source premise after readiness')
    if seen!={(subject,source) for subject in SUBJECTS for source in range(4)}:errors.append('sixteen native source premises required')
    forbidden={'source_preparation_failed','workload_failed','edit_failed','oracle_ack_timeout','offer_schedule_failed'}
    if any(r.get('event') in forbidden for r in events):errors.append('source fixture reported failure')
    return errors


def inspect(root,manifest):
    root=Path(root);runtime=read(root/'runtime.json');scenario=read(root/'scenario.json');errors=[];hashes={}
    def rows(name):
        path=regular(root/'evidence'/name)
        if path.stat().st_size>128*1024*1024:raise ValueError('bounded evidence size exceeded: '+name)
        hashes[name]=sha(path)
        return [json.loads(line) for line in path.read_text().splitlines()]
    if digest(manifest['run_manifest'])!=manifest['run_hash'] or digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:
        errors.append('run input identity mismatch')
    platform=scenario.get('server_platform','folia')
    expected_assertions=ASSERTIONS if platform=='folia' else ASSERTIONS[:-1]
    if platform not in ('folia','paper','fabric') or scenario.get('checker')!='concurrent-sources' or set(scenario.get('assertions',[]))!=set(expected_assertions):errors.append('mixed-source checker applicability mismatch')
    launches=runtime.get('launches',[])
    if {r['id'] for r in launches}!={'server','client-A','client-B','client-C','client-D'}:errors.append('exact four-client composition required')
    server=runtime.get('server_profile',{});profile=read(root/'participants/server.json')
    if profile.get('platform')!=platform or digest(profile)!=server.get('profile_hash'):errors.append('exact declared native server profile required')
    oracle=rows('oracle.jsonl');events=rows('server-events.jsonl')
    consumers={subject:rows('consumer-'+subject+'.jsonl') for subject in sorted(SUBJECTS)}
    if {p.name for p in (root/'evidence').glob('consumer-*.jsonl')}!={'consumer-'+s+'.jsonl' for s in SUBJECTS}:errors.append('unexpected consumer evidence identity')
    for row in oracle+events:
        if row.get('run_id')!=manifest['run_id']:errors.append('foreign/unbound server evidence');break
    for subject,values in consumers.items():
        if any(r.get('run_id')!=manifest['run_id'] or r.get('subject')!=subject for r in values):errors.append('foreign consumer evidence: '+subject)
        if sum(r.get('event')=='consumer_ready' for r in values)!=1:errors.append('consumer readiness absent/repeated: '+subject)
    bounds=read(root/'debt-bounds.json')
    if digest(bounds)!=scenario.get('debt_bounds_hash'):errors.append('debt bounds not pinned in scenario')
    measured=any('-Dlss.rig.measuredWorkload=true' in launch.get('argv',[]) for launch in launches if launch.get('id')=='server')
    if measured!=(scenario.get('measured_workload') is True):errors.append('measured workload applicability differs from native producer flag')
    if measured:
        starts=[row.get('time_ns') for row in events+oracle if row.get('event') in ('workload_start','workload_started')]
        if len(starts)!=1 or type(starts[0]) is not int:errors.append('measured workload origin absent/repeated')
        else:errors.extend(schedule_check(oracle,events,starts[0])['errors'])
    elif any('target_sequence' in row for row in oracle if row.get('event')=='target'):
        errors.append('measured offers present in diagnostic scenario')
    result=source_check(oracle,consumers,events,bounds);errors.extend(result['errors']);errors.extend(source_premises(events));errors.extend(schema_check(oracle,events,scenario,platform))
    if platform=='folia':
        region_rows=rows('region-events.jsonl');ticks=rows('tick-events.jsonl')
        if any(r.get('run_id')!=manifest['run_id'] for r in region_rows if r.get('event')!='writer_closed'):errors.append('foreign region evidence')
        timing_ready=[r for r in ticks if r.get('event')=='timing_ready']
        if len(timing_ready)!=1 or timing_ready[0].get('run_id')!=manifest['run_id'] or any(r.get('run_id',manifest['run_id'])!=manifest['run_id'] for r in ticks):errors.append('foreign timing evidence')
        joins={r['connection_id']:r for r in region_rows if r.get('event')=='join'}
        accepted=handshakes(root,joins)
        regions=region_check(region_rows,ticks,accepted,allow_reconnect=True,include_samples=measured);errors.extend(regions['errors'])
        if {r['subject'] for r in joins.values()}!=SUBJECTS:errors.append('four owning-region subjects required')
    else:
        joins={r['connection_id']:r for r in events if r.get('event')=='join'}
        accepted=handshakes(root,joins)
        errors.extend(native_sessions(events,accepted))
        regions={'status':'not-applicable','reason':'native '+platform+' has one server thread, no Folia region model'}
    if measured and len(starts)==1 and type(starts[0]) is int:
        progress=sustained_progress(oracle,consumers,events,platform,starts[0],result['debt_check'],regions)
        errors.extend(progress['errors'])
        result['progress_check']=progress
    for path in sorted(root.glob('*.private.log')):
        hashes[path.name]=sha(regular(path))
        if 'Unexpected failure delivering disk read' in path.read_text(errors='replace'):errors.append('one-result-per-submit premise violated')
    return {'status':'failed' if errors else 'passed','errors':list(dict.fromkeys(errors)),
            **{k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')},
            'source_check':result,'region_check':regions,'raw_evidence_sha256':hashes,
            'handshake':bool(accepted) and all(r.get('accepted') is True for r in accepted),
            'scope':'four-client-mixed-source-correctness','server_platform':platform,'assertions':list(expected_assertions),'performance_acceptance':False}


def make_proof(report):
    passed=report['status']=='passed'
    return {**{k:report[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')},
            'ready':passed,'handshake':report['handshake'],'test_count':len(report['assertions']) if passed else 0,
            'assertions':{key:passed for key in report['assertions']},'failures':report['errors'],
            'source_run_report':report}


def check_report(proof,manifest,root):
    try:
        actual=inspect(root,manifest)
        if digest(actual)!=digest(proof.get('source_run_report')):return ['mixed-source raw report missing/stale']
        return actual['errors']
    except (ValueError,OSError,KeyError,TypeError) as error:
        return ['mixed-source raw proof invalid: '+str(error)]
