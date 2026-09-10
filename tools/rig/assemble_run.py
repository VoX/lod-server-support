#!/usr/bin/env python3
"""Assemble measured reports from a collected, exact-input, four-source run."""
import argparse
import json
from pathlib import Path
from rig import read,sha,regular,inside,alive,digest
from check_regions import load_rows,check as region_check,handshakes
from check_concurrent_sources import check as source_check
from check_debt import check as debt_check
from normalize_workload import normalize
from assemble_metrics import assemble
from performance import correctness,sample_errors
from measurement_inputs import fingerprints
from check_measured_schedule import check as schedule_check
from supplemental_metrics import assemble as supplemental_metrics
from check_xaero_progress import check as xaero_progress_check
from measured_regions import bind as bind_measured_regions


def assemble_run(root):
    root=Path(root).resolve();manifest=read(root/'manifest.json');runtime=read(root/'runtime.json')
    collected=read(root/'evidence/result.json')
    for field in ('run_id','run_hash','profile_hash','scenario_hash'):
        if collected.get(field)!=manifest.get(field):raise ValueError('collection identity changed')
    if collected.get('status')!='passed' or collected.get('cleanup')!='complete':raise ValueError('successful collected correctness run required')
    if digest(runtime)!=manifest['runtime_hash']:raise ValueError('runtime bytes changed')
    processes=read(root/'processes.json')
    if any(alive(owner) for owner in processes) or alive(read(root/'supervisor.json')):raise ValueError('owned runtime still live')
    metadata=runtime.get('measurement',{});binding=runtime.get('measurement_context')
    if not binding or not metadata:raise ValueError('measurement intent and input identities must be bound before launch')
    artifact_identity=metadata['artifact_identity'];paths=metadata['artifact_paths']
    if set(paths)!=set(artifact_identity['artifact_hashes']):raise ValueError('production artifact paths incomplete')
    staged={row['target']:row['sha256'] for row in manifest['run_manifest']['staged_inputs']}
    for platform,path in paths.items():
        expected=artifact_identity['artifact_hashes'][platform]
        if staged.get(path)!=expected or sha(regular(inside(root,path)))!=expected:raise ValueError('arm artifact differs from staged runtime')
    fixture_paths=metadata['fixture_paths']
    if not isinstance(fixture_paths,list) or not fixture_paths or len(set(fixture_paths))!=len(fixture_paths):raise ValueError('explicit fixture artifact paths required')
    fixtures={path:sha(regular(inside(root,path))) for path in fixture_paths}
    if any(staged.get(path)!=value for path,value in fixtures.items()):raise ValueError('fixture artifact differs from staged runtime')
    actual_inputs=fingerprints(runtime,paths,fixture_paths,read(root/'gpu.json'))
    for key in ('hardware_hash','jvm_hash','workload_hash'):
        if metadata.get(key)!=actual_inputs[key]:raise ValueError('measured runtime/host differs from preregistered '+key)
    evidence=root/'evidence';oracle=load_rows(evidence/'oracle.jsonl')
    consumers={p.stem.removeprefix('consumer-'):load_rows(p) for p in evidence.glob('consumer-*.jsonl')}
    if len(consumers)!=4:raise ValueError('four measured consumers required')
    for subject,rows in consumers.items():
        if any(row.get('run_id')!=manifest['run_id'] or row.get('subject')!=subject for row in rows):raise ValueError('mixed consumer identity')
    platform=metadata['platform'];events=load_rows(inside(root,metadata['server_events']))
    if any(row.get('run_id')!=manifest['run_id'] for row in events+oracle):raise ValueError('mixed server/oracle run identity')
    starts=[row['time_ns'] for row in events+oracle if row.get('event') in ('workload_start','workload_started')]
    if len(starts)!=1:raise ValueError('one actual all-subject workload origin required')
    schedule=schedule_check(oracle,events,starts[0])
    if schedule['status']!='passed':raise ValueError('preregistered offered load failed: '+str(schedule['errors']))
    start=starts[0]+120_000_000_000;end=start+600_000_000_000
    closes=[row['time_ns'] for row in events if row.get('event')=='offers_closed']
    if len(closes)!=1 or closes[0]<end:raise ValueError('offered workload ended before measurement')
    bridge=xaero_progress_check(consumers,start,end,read(root/'gpu.json'))
    if bridge['status']!='passed':raise ValueError('actual measured Xaero progress failed: '+str(bridge['errors']))
    bounds=metadata['capacity_bounds'];sources=source_check(oracle,consumers,events,bounds)
    if sources['status']!='passed':raise ValueError('four-source correctness failed: '+str(sources['errors']))
    for launch in runtime['launches']:
        log=root/(launch['id']+'.private.log')
        if log.exists() and 'Unexpected failure delivering disk read' in log.read_text(errors='replace'):
            raise ValueError('one-result-per-submit capacity premise violated')
    debt=debt_check(events,bounds,end,set(consumers));ticks=load_rows(evidence/'tick-events.jsonl')
    regions=None
    if platform=='folia':
        region_rows=load_rows(inside(root,metadata['region_events']))
        if any(row.get('run_id')!=manifest['run_id'] for row in region_rows if row.get('event')!='writer_closed'):raise ValueError('mixed owning-region run identity')
        joins={row['connection_id']:row for row in region_rows if row.get('event')=='join'}
        regions=region_check(region_rows,ticks,handshakes(root,joins),allow_reconnect=True,include_samples=True)
        regions=bind_measured_regions(regions,events,start,end)
    normalized=normalize(oracle,consumers,events,platform=platform,start_ns=start,end_ns=end,debt_result=debt,cleanup_complete=True,region_result=regions)
    rss=load_rows(evidence/'rss-samples.jsonl');launch_ids=metadata['subject_launch_ids'];owners={}
    for subject,launch in launch_ids.items():
        identities={json.dumps(row.get('process_identity'),sort_keys=True) for row in rss if row.get('subject')==launch}
        if len(identities)!=1:raise ValueError('missing or changing measured process identity')
        owner=json.loads(identities.pop())
        if not owner or owner not in processes:raise ValueError('RSS process not in owned launch evidence')
        owners[subject]=owner
    metrics=assemble(manifest['run_id'],consumers,ticks,rss,normalized['oracle'],normalized['faults'],owners,launch_ids,start,end)
    report={**normalized,'run_id':manifest['run_id'],'run_hash':manifest['run_hash'],'measurement_context':binding,
            'artifact_identity':artifact_identity,'warmup_seconds':120,'metrics':metrics,
            'world_digest':runtime['world_digest'],'profile_hash':manifest['profile_hash'],'fixture_hash':digest(fixtures),
            **{key:metadata[key] for key in ('hardware_hash','jvm_hash','workload_hash')}}
    raw_paths=[evidence/'oracle.jsonl',evidence/'tick-events.jsonl',evidence/'rss-samples.jsonl',inside(root,metadata['server_events']),*evidence.glob('consumer-*.jsonl')]
    if platform=='folia':raw_paths.append(inside(root,metadata['region_events']))
    report['xaero_progress']=bridge
    report['supplemental']=supplemental_metrics(normalized['oracle'],consumers,events,start,end)
    report['raw_evidence_sha256']={str(path.relative_to(root)):sha(regular(path)) for path in raw_paths}
    report['correctness_errors']=correctness(report);report['sample_errors']=sample_errors(report)
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('run',type=Path);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    result=assemble_run(args.run)
    with args.output.open('x') as stream:json.dump(result,stream,indent=2,sort_keys=True);stream.write('\n')
    print(json.dumps({'run_id':result['run_id'],'correctness_errors':result['correctness_errors'],'sample_errors':result['sample_errors']}))
