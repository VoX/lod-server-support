"""Clone a collected no-extension native prefill, construct holes, then seed normally."""
import argparse,copy,json
from pathlib import Path
from rig import read,write,sha,digest,require_lock,alive
from check_prefill import inspect
from mca_fixture import carve,regular,HOLES


def prepare(prefill,source,destination):
    prefill=Path(prefill).resolve();source=Path(source);destination=Path(destination).resolve()
    manifest=read(prefill/'manifest.json');result=read(prefill/'evidence/result.json');proof=read(prefill/'proof.json')
    if result.get('status')!='passed' or result.get('cleanup')!='complete' or result.get('run_hash')!=manifest.get('run_hash') or manifest.get('status')!='passed':raise ValueError('collected passed native prefill required')
    if alive(read(prefill/'supervisor.json')) or any(alive(p) for p in read(prefill/'processes.json')):raise ValueError('prefill owner/process remains live')
    report=inspect(prefill,manifest)
    if report['status']!='passed' or digest(report)!=digest(proof.get('prefill_report')):raise ValueError('native prefill proof no longer matches actual saved world')
    runtime=copy.deepcopy(read(source/'runtime.json'));scenario=read(source/'scenario.json');profile=read(source/'profile.json')
    if scenario.get('checker')!='source-seed' or scenario.get('requires_handshake') is not False:raise ValueError('explicit normal source-seed recipe required')
    if runtime.get('world_digest') or any(s['target'].startswith('server/world/') for s in runtime['stage_files']):raise ValueError('source seed must not already contain another world')
    destination.mkdir(parents=True,exist_ok=False,mode=0o700)
    world=destination/'constructed-world';construction=carve(prefill/'server/world',world)
    construction['prefill_run_hash']=manifest['run_hash'];construction['prefill_report_hash']=digest(report)
    write(destination/'construction.json',construction)
    runtime['prefill_construction_hash']=digest(construction)
    runtime['generated_files']['prefill-construction.json']=json.dumps(construction,sort_keys=True)+'\n'
    for path in sorted(world.rglob('*')):
        if path.is_file():
            regular(path);runtime['stage_files'].append({'source':str(path),'target':'server/world/'+str(path.relative_to(world)),'sha256':sha(path)})
    for name,value in [('runtime',runtime),('scenario',scenario),('profile',profile)]:write(destination/(name+'.json'),value)
    return destination

if __name__=='__main__':
    require_lock();p=argparse.ArgumentParser();p.add_argument('prefill_run');p.add_argument('source_seed_recipe');p.add_argument('destination');a=p.parse_args();print(prepare(a.prefill_run,a.source_seed_recipe,a.destination))
