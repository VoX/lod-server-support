"""Native no-client Paper prefill proof, including actual saved FULL records."""
import json
from pathlib import Path
from rig import read,regular,sha,digest
from mca_fixture import DOMAIN,verify_full


def inspect(root,manifest):
    root=Path(root);runtime=read(root/'runtime.json');scenario=read(root/'scenario.json');errors=[]
    if scenario.get('execution_route')!='source-prefill' or scenario.get('requires_handshake') is not False:errors.append('prefill applicability required')
    if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:errors.append('prefill input changed')
    launches=runtime.get('launches',[])
    if len(launches)!=1 or launches[0].get('id')!='server' or launches[0].get('stop_stdin')!='stop' or runtime.get('client_profiles'):errors.append('one owned gracefully stopped server and no clients required')
    profile=read(root/'participants/server.json');reference=runtime.get('server_profile',{})
    if profile.get('platform')!='paper' or profile.get('route')!='native' or digest(profile)!=reference.get('profile_hash'):errors.append('exact native Paper participant required')
    plugins=[a for a in reference.get('candidate_artifacts',[]) if a.get('kind')=='plugin']
    if len(plugins)!=1 or plugins[0].get('metadata',{}).get('paper',{}).get('name')!='LssRigNativePrefill':errors.append('prefill must exclude LSS and other plugin extensions')
    path=regular(root/'evidence/prefill-events.jsonl')
    if path.stat().st_size>8*1024*1024:raise ValueError('prefill event bound')
    rows=[json.loads(line) for line in path.read_text().splitlines()]
    if any(row.get('run_id')!=manifest['run_id'] for row in rows):errors.append('foreign prefill evidence')
    start=[r for r in rows if r.get('event')=='prefill_started'];complete=[r for r in rows if r.get('event')=='prefill_complete'];closed=[r for r in rows if r.get('event')=='prefill_closed'];full=[r for r in rows if r.get('event')=='prefill_full']
    if len(start)!=1 or start[0].get('expected')!=len(DOMAIN) or start[0].get('max_pending')!=8:errors.append('exact bounded prefill start absent')
    if len(complete)!=1 or complete[0].get('completed')!=len(DOMAIN):errors.append('prefill completion absent')
    if len(start)==1 and len(complete)==1:
        begin=start[0].get('time_ns');end=complete[0].get('time_ns')
        if type(begin) is not int or type(end) is not int or not 0<end-begin<=300_000_000_000:errors.append('native prefill completion deadline invalid')
        elif any(type(r.get('time_ns')) is not int or not begin<=r['time_ns']<=end for r in full):errors.append('FULL completion outside native construction interval')
    if len(closed)!=1 or closed[0].get('overflow') is not False or not rows or rows[-1]!=closed[0]:errors.append('prefill writer incomplete')
    if any(r.get('event')=='prefill_failed' for r in rows):errors.append('native prefill failed')
    if len(full)!=len(DOMAIN) or {(r.get('chunk_x'),r.get('chunk_z')) for r in full}!=set(DOMAIN):errors.append('native FULL completion domain absent/duplicate')
    if list((root/'evidence').glob('consumer-*.jsonl')):errors.append('prefill has client evidence')
    stopped=read(root/'evidence/source-prefill-stop.json')
    if stopped.get('run_id')!=manifest['run_id'] or stopped.get('server_returncode')!=0:errors.append('native prefill server did not stop cleanly')
    world=root/'server/world'
    if any(p.is_file() and ('lss-lod' in p.parts or p.suffix in ('.sqlite','.db')) for p in world.rglob('*')):errors.append('prefill world contains extension store')
    saved=verify_full(world)
    return {'status':'failed' if errors else 'passed','errors':errors,'test_count':len(DOMAIN),
            **{k:manifest[k] for k in ('run_id','run_hash','profile_hash','scenario_hash')},
            'raw_evidence_sha256':sha(path),'saved_full':saved,'scope':'native-prefill-construction-only'}


def check_report(proof,manifest,root):
    try:
        report=inspect(root,manifest)
        if digest(report)!=digest(proof.get('prefill_report')):return ['prefill raw report missing/stale']
        return report['errors']
    except (OSError,ValueError,KeyError,TypeError) as error:return ['prefill proof invalid: '+str(error)]
