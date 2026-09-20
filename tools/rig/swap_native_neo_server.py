#!/usr/bin/env python3
"""Replace only an owned recipe's server with an exact installed native Neo closure."""
import argparse,copy,hashlib,json,re,sys
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def swap(runtime,source,closure,installer_identity,candidate,fixtures,output):
 source=Path(source).resolve();closure=Path(closure).resolve();candidate=Path(candidate).resolve();out=Path(output)
 if out.exists():raise ValueError('fresh native server swap output required')
 props=dict(x.split('=',1) for x in (source/'gradle.properties').read_text().splitlines() if '='in x and not x.startswith('#'))
 mc=props['minecraft_version'];line='.'.join(mc.split('.')[:2]) if mc.startswith('26.') else mc
 identity=json.loads((closure/'closure.json').read_text());installer=json.loads(Path(installer_identity).read_text())
 if identity.get('status')!='native-installed-closure' or identity.get('minecraft')!=mc or identity.get('neoforge')!=props['neoforge_version'] or identity.get('installer_sha256')!=installer.get('sha256'):raise ValueError('exact production native Neo closure required')
 if sha(installer['path'])!=installer['sha256']:raise ValueError('installed bootstrap origin changed')
 sys.path.insert(0,str(source/'tools/compat'))
 from catalog import inspect_jar,digest,validate_profile
 d=copy.deepcopy(runtime);servers=[row for row in d['launches'] if row.get('id')=='server']
 if len(servers)!=1:raise ValueError('exact owned server launch required')
 old=servers[0];selected=[]
 if not old.get('argv') or Path(old['argv'][0]).name!='java':raise ValueError('swap requires a direct native Java server recipe')
 for path in [candidate,*map(Path,fixtures)]:
  if path.is_symlink() or not path.is_file():raise ValueError('regular native candidate/fixture required')
  row=inspect_jar(path);metadata=row['metadata'].get('neoforge')
  if not metadata or not metadata.get('mods'):raise ValueError('native Neo metadata required; no Fabric fixture substitution')
  mod=metadata['mods'][0];row.update(id=mod['modId'],version=str(mod['version']),source='cache:sha256:'+row['sha256'],kind='mod',enabled=True)
  d['cache'][row['sha256']]=str(path.resolve());selected.append((path,row))
 if len({row['id'] for _,row in selected})!=len(selected):raise ValueError('duplicate native candidate/fixture mod identity')
 out.mkdir(parents=True)
 bootstrap=dict(id='neoforge-installer',version=props['neoforge_version'],file=Path(installer['path']).name,sha256=installer['sha256'],source=installer['url'],metadata={},kind='server',enabled=True)
 profile=dict(schema_version=1,id='mc'+line.replace('.','')+'-neoforge-server',line=line,platform='neoforge',route='native',status='unverified',components=[dict(uid='net.minecraft',version=mc),dict(uid='net.neoforged',version=props['neoforge_version']),dict(uid='java',version='25' if mc.startswith('26.') else '21')],artifacts=[bootstrap],capabilities=['native-server','handshake','store'],limitations=['Exact production installer closure; fixture-specific native acceptance remains separate.'])
 validate_profile(profile,allow_unresolved_ranges=True);profile_path=out/'server-profile.json';profile_path.write_text(json.dumps(profile,indent=2)+'\n')
 d['cache'][installer['sha256']]=installer['path'];d['server_profile']=dict(path=str(profile_path),id=profile['id'],profile_hash=digest(profile),candidate_artifacts=[r for _,r in selected])
 d['stage_files']=[row for row in d['stage_files'] if not row['target'].startswith('server/')]
 d['immutable_trees']={k:v for k,v in d.get('immutable_trees',{}).items() if not k.startswith('server/')}
 frozen={}
 for name,checksum in identity['files'].items():
  if Path(name).is_absolute() or '..'in Path(name).parts or name.startswith(('world/','mods/','plugins/')):raise ValueError('non-bootstrap content in installed closure')
  path=closure/name
  if path.is_symlink() or sha(path)!=checksum:raise ValueError('native installed closure changed')
  d['stage_files'].append(dict(source=str(path),target='server/'+name,sha256=checksum));frozen[name]=checksum
 # Pin immutable libraries; installation metadata/launch argument files are separately staged and hashed.
 d['immutable_trees']['server/libraries']={name[len('libraries/'):]:value for name,value in frozen.items() if name.startswith('libraries/')}
 for index,(path,row) in enumerate(selected):
  name='lod-server-support-neoforge.jar' if index==0 else path.name
  d['stage_files'].append(dict(source=str(path),target='server/mods/'+name,sha256=row['sha256']))
 # Preserve only explicit LSS-owned fixture properties from the previous native Java launch.
 jvm=[arg for arg in old['argv'] if re.fullmatch(r'-Dlss\.[A-Za-z0-9_.]+=[^\r\n]*',arg)]
 if not any(x.startswith('-Dlss.rig.runId=') for x in jvm):jvm.append('-Dlss.rig.runId={run_id}')
 args=list(identity['launch']);args[1:1]=jvm
 replacement=dict(old,argv=args,cwd='server',stop_stdin='stop');d['launches']=[replacement if row.get('id')=='server' else row for row in d['launches']]
 d['native_server_swap']=dict(platform='neoforge',minecraft=mc,neoforge=props['neoforge_version'],installer_sha256=installer['sha256'],closure_sha256=sha(closure/'closure.json'),candidate_sha256=selected[0][1]['sha256'],fixtures=[row['sha256'] for _,row in selected[1:]],previous_server_profile=runtime.get('server_profile',{}).get('id'))
 (out/'runtime.json').write_text(json.dumps(d,indent=2)+'\n');return out/'runtime.json'
if __name__=='__main__':
 p=argparse.ArgumentParser()
 for name in ('runtime','source','closure','installer-identity','candidate','output'):p.add_argument('--'+name,required=True)
 p.add_argument('--fixture',action='append',default=[]);a=p.parse_args();print(swap(json.loads(Path(a.runtime).read_text()),a.source,a.closure,a.installer_identity,a.candidate,a.fixture,a.output))
