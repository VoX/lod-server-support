#!/usr/bin/env python3
"""Stage one exact native Fabric/Paper/production-Neo smoke row; never creates/runs it."""
import argparse,copy,hashlib,json,sys
from pathlib import Path
from prepare_native_client import prepare as prepare_client
from prepare_server_smoke import prepare as prepare_smoke

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,d):Path(p).write_text(json.dumps(d,indent=2)+'\n')
def assemble(spec,output):
 out=Path(output)
 if out.exists():raise ValueError('fresh platform recipe root required')
 source=Path(spec['source']).resolve();platform=spec['platform']
 if platform not in ('fabric','paper','neoforge'):raise ValueError('native platform required')
 props=dict(x.split('=',1) for x in (source/'gradle.properties').read_text().splitlines() if '='in x and not x.startswith('#'))
 mc=props['minecraft_version'];line='.'.join(mc.split('.')[:2]) if mc.startswith('26.') else mc;java=str(Path(spec['java']).resolve());client=Path(spec['client_candidate']);server=Path(spec['server_candidate'])
 for key,path in [('client_candidate',client),('server_candidate',server),('fixture',Path(spec['fixture']))]:
  if path.is_symlink() or sha(path)!=spec[key+'_sha256']:raise ValueError('changed exact input: '+key)
 out.mkdir(parents=True)
 sys.path.insert(0,str(source/'tools/compat'));sys.path.insert(0,str(source/'tools/rig'))
 from catalog import inspect_jar,digest,Invalid,validate_profile
 from prepare_prism import verify_bootstrap
 from rig import endpoint
 host,port=endpoint(spec.get('endpoint','[::1]:25576'))
 cache=dict(spec.get('cache',{}))
 def artifact(path,kind=None):
  path=Path(path);checksum=sha(path)
  try:row=inspect_jar(path);metadata=row['metadata']
  except Invalid as e:
   if 'no recognized mod metadata' not in str(e):raise
   row=dict(file=path.name,sha256=checksum,metadata={});metadata={}
  fabric=metadata.get('fabric');paper=metadata.get('paper');maven=metadata.get('neoforge',metadata.get('maven',{}))
  mods=maven.get('mods',[]) if isinstance(maven,dict) else []
  mid,version=(fabric['id'],fabric['version']) if fabric else ((paper['name'],str(paper['version'])) if paper else ((mods[0]['modId'],mods[0]['version']) if mods else ('library-'+checksum[:16],'locked')))
  row.update(id=mid,version=str(version),source='cache:sha256:'+checksum,enabled=True,kind=kind or ('mod' if fabric or mods else ('plugin' if paper else 'library')))
  cache[checksum]=str(path.resolve());return row
 client_row=artifact(client);server_row=artifact(server)
 if 'fabric' not in client_row['metadata'] or platform not in server_row['metadata']:raise ValueError('actual candidate loader metadata differs from participant')
 runtime=dict(backend='linux-headless',private_display=True,require_gpu=False,bind_endpoint=spec.get('endpoint','[::1]:25576'),client_endpoint=spec.get('endpoint','[::1]:25576'),java=java,cache=cache,candidate_artifacts=[client_row],stage_files=[],generated_files={},launches=[],gpu_environment={'ALSOFT_DRIVERS':'null'})
 if spec.get('range_runtime'):runtime['range_runtime']=spec['range_runtime']
 def stage(path,target):
  path=Path(path)
  if path.is_symlink() or not path.is_file():raise ValueError('regular native input required')
  runtime['stage_files'].append(dict(source=str(path.resolve()),target=target,sha256=sha(path)))
 if platform=='neoforge':
  closure=Path(spec['neo_closure']);identity=json.loads((closure/'closure.json').read_text());installer=json.loads(Path(spec['installer_identity']).read_text())
  if identity.get('status')!='native-installed-closure' or identity.get('minecraft')!=mc or identity.get('neoforge')!=props['neoforge_version'] or identity.get('installer_sha256')!=installer['sha256']:raise ValueError('exact production installer closure required')
  if sha(installer['path'])!=installer['sha256']:raise ValueError('installer changed')
  entry=dict(id='neoforge-installer',version=props['neoforge_version'],file=Path(installer['path']).name,sha256=installer['sha256'],source=installer['url'],metadata={},kind='server',enabled=True)
  cache[installer['sha256']]=installer['path']
  server_profile=dict(schema_version=1,id='mc'+line.replace('.','')+'-neoforge-server-smoke',line=line,platform='neoforge',route='native',status='unverified',components=[{'uid':'net.minecraft','version':mc},{'uid':'net.neoforged','version':props['neoforge_version']},{'uid':'java','version':'25' if mc.startswith('26.') else '21'}],artifacts=[entry],capabilities=['native-server','handshake','store'],limitations=['Production installer dependency preparation; no live smoke acceptance yet.'])
  server_profile_path=out/'server-profile.json';write(server_profile_path,server_profile)
  server_argv=identity['launch']
 else:
  server_profile_path=Path(spec['server_profile']);server_profile=json.loads(server_profile_path.read_text())
  if server_profile['platform']!=platform or server_profile['line']!=line:raise ValueError('native server profile differs')
  if platform=='fabric' and spec.get('fabric_closure'):
   installed=Path(spec['fabric_closure']);identity=json.loads((installed/'closure.json').read_text());components={x['uid']:x['version'] for x in server_profile['components']}
   if identity.get('status')!='native-installed-fabric-closure' or identity.get('minecraft')!=mc or identity.get('loader')!=components.get('net.fabricmc.fabric-loader'):raise ValueError('exact installed Fabric closure required')
   for name,checksum in identity['files'].items():
    if Path(name).is_absolute() or '..'in Path(name).parts or name.startswith(('world/','mods/')):raise ValueError('unsafe installed Fabric closure member')
    path=installed/name
    if sha(path)!=checksum:raise ValueError('installed Fabric closure changed')
    stage(path,'server/'+name)
   runtime.setdefault('immutable_trees',{})['server/libraries']={name[len('libraries/'):]:value for name,value in identity['files'].items() if name.startswith('libraries/')}
   for entry in server_profile['artifacts']:
    if entry.get('enabled',True) and entry.get('kind','mod')=='mod':
     path=Path(cache[entry['sha256']])
     if sha(path)!=entry['sha256']:raise ValueError('changed server dependency')
     stage(path,'server/mods/'+entry['file'])
   server_argv=identity['launch']
  else:
   bootstrap=Path(spec['server_bootstrap']);verify_bootstrap(bootstrap,server_profile);name='paper.jar' if platform=='paper' else 'fabric-server-launch.jar';stage(bootstrap,'server/'+name)
   # The exact profile's server artifact must be this bootstrap, if it carries one.
   engines=[x for x in server_profile['artifacts'] if x.get('kind')=='server' and x.get('enabled',True)]
   if engines and sha(bootstrap) not in {x['sha256'] for x in engines}:raise ValueError('bootstrap artifact differs from locked server engine')
   libraries=Path(spec['server_dependency_root'])
   if not any((libraries/'libraries').rglob('*.jar')):raise ValueError('complete native server dependency closure missing')
   for directory in ('libraries','versions','cache'):
    frozen={}
    for path in sorted((libraries/directory).rglob('*')):
     if path.is_symlink():raise ValueError('symlink server dependency')
     if path.is_file():stage(path,'server/'+directory+'/'+str(path.relative_to(libraries/directory)));frozen[str(path.relative_to(libraries/directory))]=sha(path)
    runtime.setdefault('immutable_trees',{})['server/'+directory]=frozen
   for entry in server_profile['artifacts']:
    if entry.get('enabled',True) and entry.get('kind','mod') in ('mod','plugin'):
     path=Path(cache[entry['sha256']])
     if sha(path)!=entry['sha256']:raise ValueError('changed server dependency')
     stage(path,'server/'+('plugins' if platform=='paper' else 'mods')+'/'+entry['file'])
   server_argv=[java,'-Xms512M','-Xmx2G','-Dlss.rig.runId={run_id}','-jar',name,'nogui']
 validate_profile(server_profile,allow_unresolved_ranges=True)
 runtime['server_profile']=dict(path=str(server_profile_path.resolve()),id=server_profile['id'],profile_hash=digest(server_profile),candidate_artifacts=[server_row])
 stage(server,'server/'+('plugins' if platform=='paper' else 'mods')+'/lod-server-support-'+platform+'.jar')
 runtime['generated_files'].update({'server/eula.txt':'eula=true\n','server/server.properties':f'server-ip={host}\nserver-port={port}\nonline-mode=false\nlevel-name=world\nlevel-seed=project-improvements-functional\nspawn-protection=0\nallow-flight=true\nenforce-secure-profile=false\n'})
 runtime['launches']=[dict(id='server',cwd='server',argv=server_argv,stop_stdin='stop')]
 runtime_path=prepare_client(runtime,spec['capture'],source,client,out/'client-closure');runtime=json.loads(runtime_path.read_text())
 # Derive the actual named native dependency lock, never reuse an optional renderer profile.
 cp=next(x for x in runtime['launches'] if x['id']=='smoke-template')['argv'];classpath=cp[cp.index('-cp')+1].split(':');artifacts=[]
 for value in classpath:
  target=value.removeprefix('{run}/');row=next(x for x in runtime['stage_files'] if x['target']==target);entry=artifact(row['source']);artifacts.append(entry)
 profile=dict(schema_version=1,id='mc'+line.replace('.','')+'-native-smoke-client',line=line,platform='fabric',route='native',status='unverified',components=json.loads((Path(spec['capture'])/'target-components.json').read_text()),artifacts=artifacts,capabilities=['actual-client','test-only-consumer','handshake','store'],limitations=['Captured native named client; explicit test-only decoded-body consumer, no terrain renderer claim.'])
 validate_profile(profile,allow_unresolved_ranges=True);write(out/'profile.json',profile)
 runtime['cache'].update(cache)
 prepare_smoke(runtime,'smoke-template',spec['fixture'],out/'recipe',platform,'server/world/lss-lod/store.db',spec.get('neo_closure'))
 write(out/'inputs.json',spec)
 return dict(profile=str(out/'profile.json'),runtime=str(out/'recipe/runtime.json'),scenario=str(out/'recipe/scenario.json'),platform=platform,line=line,created=False)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--spec',required=True);p.add_argument('--output',required=True);a=p.parse_args();print(json.dumps(assemble(json.loads(Path(a.spec).read_text()),a.output)))
