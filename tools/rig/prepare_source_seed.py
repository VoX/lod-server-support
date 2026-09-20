#!/usr/bin/env python3
"""Derive a disposable server-only Paper seed recipe from a locked source lane."""
import argparse
import copy
from pathlib import Path
from rig import read,write


def prepare(source,destination,measured=False):
    source=Path(source); destination=Path(destination)
    runtime=copy.deepcopy(read(source/'runtime.json'))
    server=read(Path(runtime['server_profile']['path']))
    if server.get('platform')!='paper' or server.get('route')!='native':raise ValueError('native Paper source recipe required')
    launches=[item for item in runtime['launches'] if item['id']=='server']
    if len(launches)!=1:raise ValueError('one server required')
    runtime['launches']=launches;runtime['client_profiles']=[];runtime['ready_conditions']=[]
    runtime.pop('require_gpu',None)
    runtime['private_display']=False;runtime['backend']='linux-headless'
    runtime['immutable_trees']={k:v for k,v in runtime.get('immutable_trees',{}).items() if k.startswith('server/')}
    if any(item['target'].endswith(('level.dat','.mca','.sqlite')) for item in runtime['stage_files']):raise ValueError('fresh seed recipe must not import world data')
    runtime['stage_files']=[s for s in runtime['stage_files'] if s['target'].startswith('server/')]
    runtime['generated_files']={k:v for k,v in runtime['generated_files'].items() if k.startswith('server/')}
    runtime.pop('world_digest',None)
    runtime['launches'][0]['ready_marker']='LSS_RIG_SOURCES_READY'
    if measured:
        argv=runtime['launches'][0]['argv']
        argv[:]=[arg for arg in argv if not arg.startswith('-Dlss.rig.measuredWorkload=')]
        argv.insert(1,'-Dlss.rig.measuredWorkload=true')
    scenario={'schema_version':1,'id':'paper-source-seed','version':1,'execution_route':'source-seed','checker':'source-seed',
        'requires_handshake':False,'timeout_seconds':360,'observe_seconds':3,'required_test_count':16,
        'assertions':['seed_world_ready'],'human_reviews':[]}
    destination.mkdir(parents=True,exist_ok=False)
    for name,value in [('runtime',runtime),('profile',read(source/'profile.json')),('scenario',scenario)]:write(destination/(name+'.json'),value)
    return destination

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('destination');p.add_argument('--measured',action='store_true');a=p.parse_args();print(prepare(a.source,a.destination,a.measured))
