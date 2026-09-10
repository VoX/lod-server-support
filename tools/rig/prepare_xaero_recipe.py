#!/usr/bin/env python3
"""Derive an exact direct-client Xaero recipe; no game launch or dependency guesses."""
import argparse,copy,json,sys
from pathlib import Path
from rig import digest,sha,write,require_lock

def prepare(recipe,locked,cache,output):
    profile=json.loads((recipe/'profile.json').read_text());runtime=json.loads((recipe/'runtime.json').read_text());scenario=json.loads((recipe/'scenario.json').read_text())
    selected=[a for a in locked['artifacts'] if a['id'] in ('fabric-api','sodium','xaeroworldmap')]
    if {a['id'] for a in selected}!={'fabric-api','sodium','xaeroworldmap'}:raise ValueError('exact Xaero dependency subset absent')
    removed=[];retained=[]
    for artifact in profile['artifacts']:
        mod=artifact.get('metadata',{}).get('fabric',{}).get('id','')
        if mod=='fabric-api' or mod.startswith('fabric-') and mod!='fabricloader':removed.append(artifact)
        else:retained.append(artifact)
    if not removed:raise ValueError('prior direct Fabric API closure not identified')
    removals={'{run}/artifacts/'+a['file'] for a in removed}
    profile['artifacts']=retained+copy.deepcopy(selected)
    profile['id']='mc262-folia-xaero-client';profile['capabilities']=sorted(set(profile.get('capabilities',[]))|{'actual-xaero-map-bridge'})
    for a in selected:
        source=Path(cache[a['sha256']])
        if sha(source)!=a['sha256']:raise ValueError('selected Xaero bytes changed')
        runtime['cache'][a['sha256']]=str(source.resolve())
    for launch in runtime['launches']:
        if not launch['id'].startswith('client-'):continue
        argv=launch['argv'];at=argv.index('-cp')+1
        argv[at]=':'.join([item for item in argv[at].split(':') if item not in removals]+['{run}/artifacts/'+a['file'] for a in selected])
        argv.insert(1,'-Dlss.rig.requireXaero=true')
        config_path=launch['cwd']+'/config/lss-client-config.json'
        config=json.loads(runtime['generated_files'].get(config_path,'{}'))
        if not isinstance(config,dict):raise ValueError('existing client configuration must be an object')
        config.update(enableXaeroMapBridge=True,enableXaeroMapBackpressure=True)
        runtime['generated_files'][config_path]=json.dumps(config,sort_keys=True)+'\n'
    runtime['require_gpu']=True
    for participant in runtime.get('client_profiles',[]):
        participant.update(path=str((output/'profile.json').resolve()),id=profile['id'],profile_hash=digest(profile))
    from materialize import resolution
    combined=copy.deepcopy(profile);combined['artifacts']+=runtime['candidate_artifacts']
    result=resolution(combined,runtime['cache'],runtime.get('range_runtime'))
    if not result['ready']:raise ValueError('Xaero closure unresolved: '+str(result))
    output.mkdir(parents=True,exist_ok=False)
    write(output/'profile.json',profile);write(output/'runtime.json',runtime);write(output/'scenario.json',scenario)

if __name__=='__main__':
    require_lock();p=argparse.ArgumentParser();p.add_argument('--recipe',required=True);p.add_argument('--profile',required=True);p.add_argument('--cache',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
    prepare(Path(a.recipe),json.loads(Path(a.profile).read_text()),json.loads(Path(a.cache).read_text()),Path(a.output))
