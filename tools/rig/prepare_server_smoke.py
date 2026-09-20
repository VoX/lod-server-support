#!/usr/bin/env python3
"""Create two fresh direct-client roots from an exact staged native client closure."""
import argparse,copy,hashlib,json,sys
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def prepare(runtime,client_id,fixture,output,platform,store_database,neo_closure=None):
    out=Path(output)
    if platform not in ('fabric','paper','neoforge'):raise ValueError('unsupported native server platform')
    if '..' in Path(store_database).parts or Path(store_database).is_absolute():raise ValueError('store must be owned relative path')
    if out.exists():raise ValueError('refuse existing recipe output')
    d=copy.deepcopy(runtime);fixture=Path(fixture)
    profile_ref=d.get('server_profile')
    if not profile_ref:raise ValueError('exact native server dependency profile required')
    profile=json.loads(Path(profile_ref['path']).read_text())
    if profile.get('platform')!=platform:raise ValueError('server profile platform differs from native launch')
    if fixture.is_symlink() or not fixture.is_file():raise ValueError('explicit fixture jar required')
    clients=[r for r in d['launches'] if r['id']==client_id]
    if len(clients)!=1 or not any('KnotClient' in token for token in clients[0]['argv']):raise ValueError('actual captured direct KnotClient closure required; no launcher profile mutation')
    client=clients[0];prefix=client['cwd']
    if prefix in ('','.','server') or '..' in Path(prefix).parts:raise ValueError('unsafe client root')
    server=next((r for r in d['launches'] if r['id']=='server'),None)
    if server is None:raise ValueError('exact server launch required')
    out.mkdir(parents=True);recipe=json.loads((Path(__file__).parent/'scenarios/server-smoke.json').read_text());phases={}
    old_stages=list(d['stage_files']);old_generated=dict(d['generated_files'])
    # Keep shared immutable dependencies and server files; remove unrelated authenticated observer roots.
    d['stage_files']=[r for r in old_stages if not r['target'].startswith((prefix+'/', 'instances/'))]
    d['generated_files']={p:v for p,v in old_generated.items() if not p.startswith((prefix+'/', 'instances/'))}
    for phase in ('first','second'):
        destination='smoke-'+phase
        for row in old_stages:
            if not row['target'].startswith(prefix+'/'):continue
            new=dict(row,target=destination+row['target'][len(prefix):])
            # Fixture selection is explicit. Reject base recipes carrying unrelated custom test mods.
            if '/mods/' in new['target'] and ('fixture' in new['target'] or 'rig-' in new['target']):continue
            d['stage_files'].append(new)
        for path,value in old_generated.items():
            if path.startswith(prefix+'/'):d['generated_files'][destination+path[len(prefix):]]=value
        d['stage_files'].append(dict(source=str(fixture.resolve()),sha256=sha(fixture),target=destination+'/mods/lss-server-smoke-client.jar'))
        d['generated_files'][destination+'/config/lss-client-config.json']=json.dumps(recipe['client_config'])+'\n'
        d['generated_files'][destination+'/options.txt']='maxFps:30\nrenderDistance:2\nsimulationDistance:2\npauseOnLostFocus:false\nenableVsync:false\nonboardAccessibility:false\n'
        args=[value.replace('{run}/'+prefix,'{run}/'+destination) for value in client['argv'] if not value.startswith('-Dlss.rig.elytraTarget=')]
        if '--username' not in args or '--gameDir' not in args:raise ValueError('direct client identity/game root absent')
        args[args.index('--username')+1]='RigSubjectA'
        args[1:1]=['-Dlss.smoke.connection={run_id}-'+phase,'-Dlss.smoke.phase='+phase,'-Dlss.smoke.evidence={run}/evidence']
        phases[phase]=dict(argv=args,cwd=destination,env=client.get('env',{}))
    server_config='server/plugins/LodServerSupport/lss-server-config.json' if platform=='paper' else 'server/config/lss-server-config.json'
    d['generated_files'][server_config]=json.dumps(recipe['server_config'])+'\n'
    properties={line.split('=',1)[0]:line.split('=',1)[1] for line in d['generated_files']['server/server.properties'].splitlines() if '=' in line}
    properties.update(recipe['server_properties']);d['generated_files']['server/server.properties']=''.join(str(k)+'='+str(v).lower()+'\n' for k,v in properties.items())
    if neo_closure:
        closure=Path(neo_closure);identity=json.loads((closure/'closure.json').read_text())
        if identity.get('candidate_sha256') and not any(r.get('sha256')==identity['candidate_sha256'] for r in d['server_profile']['candidate_artifacts']):raise ValueError('captured Neo candidate differs from exact server manifest')
        if identity.get('status') != 'native-installed-closure':raise ValueError('Neo native installed closure required')
        d['stage_files']=[r for r in d['stage_files'] if not r['target'].startswith('server/') or '/mods/' in r['target']]
        d['immutable_trees']={k:v for k,v in d.get('immutable_trees',{}).items() if not k.startswith('server/')}
        for name,digest in identity['files'].items():
            path=closure/name
            if sha(path)!=digest:raise ValueError('changed Neo closure')
            d['stage_files'].append(dict(source=str(path.resolve()),sha256=digest,target='server/'+name))
        server=dict(server,argv=identity['launch'],cwd='server',env=identity.get('environment',{}))
    command=dict(server=server,clients=phases,store_database=store_database,recipe=recipe)
    d['generated_files']['server-smoke-command.json']=json.dumps(command,indent=2)+'\n'
    for name in ('run_server_smoke.py','store_witness.py','check_server_smoke.py','check_server_smoke_report.py'):
        path=Path(__file__).parent/name;d['stage_files'].append(dict(source=str(path.resolve()),sha256=sha(path),target='smoke-tools/'+name))
    d['launches']=[dict(id='server',cwd='server',argv=[sys.executable,'{run}/smoke-tools/run_server_smoke.py','{run}'])]
    import zipfile
    with zipfile.ZipFile(fixture) as jar:metadata=json.loads(jar.read('fabric.mod.json'))
    fixture_row=dict(id=metadata['id'],version=metadata['version'],file=fixture.name,sha256=sha(fixture),source='cache:sha256:'+sha(fixture),enabled=True,kind='mod',metadata={'fabric':metadata})
    d['candidate_artifacts'].append(fixture_row);d['cache'][sha(fixture)]=str(fixture.resolve())
    d['smoke_fixture_identity']=dict(sha256=sha(fixture),client_role='explicit-test-only-consumer',native_server_platform=platform)
    (out/'runtime.json').write_text(json.dumps(d,indent=2)+'\n');(out/'scenario.json').write_text(json.dumps(recipe,indent=2)+'\n')
    return out/'runtime.json'
if __name__=='__main__':
    p=argparse.ArgumentParser()
    for n in ('runtime','client-launch-id','fixture','output','platform','store-database'):p.add_argument('--'+n,required=True)
    p.add_argument('--neo-closure');a=p.parse_args();print(prepare(json.loads(Path(a.runtime).read_text()),a.client_launch_id,a.fixture,a.output,a.platform,a.store_database,a.neo_closure))
