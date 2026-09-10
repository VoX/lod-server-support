#!/usr/bin/env python3
"""Extend an owned Prism observer recipe with an independent, directly launched target."""
import argparse,json,hashlib,zipfile,shutil,sys
from pathlib import Path
p=argparse.ArgumentParser()
for flag in ('source-worktree','capture','runtime','candidate','fixture','output'):p.add_argument('--'+flag,required=True)
a=p.parse_args();source=Path(a.source_worktree).resolve();capture=Path(a.capture).resolve();out=Path(a.output).resolve()
sys.path.insert(0,str(source/'tools/rig'));from rig import require_lock
# Recipe preparation is filesystem-only; create/run still require owned harness lock.
out.mkdir(parents=True,exist_ok=False);cache=out/'cache';cache.mkdir()
d=json.loads(Path(a.runtime).read_text());rows=json.loads((capture/'runtime-classpath.json').read_text())
hashfile=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
for row in rows:
 path=Path(row['path'])
 if any(path.is_relative_to(source/sub) for sub in ('fabric/build/classes','fabric/build/resources','common/build/libs')):continue
 if row.get('directory'):
  actual={str(f.relative_to(path)):hashfile(f) for f in path.rglob('*') if f.is_file()}
  if actual!={x:y for x,y in row['files'].items()}:raise ValueError('captured directory changed: '+str(path))
 elif hashfile(path)!=row['sha256']:raise ValueError('captured library changed: '+str(path))
seen={}
def stage(path,target):
 path=Path(path)
 if path.is_symlink() or not path.is_file():raise ValueError('nonregular stage input')
 d['stage_files'].append(dict(source=str(path.resolve()),target=target,sha256=hashfile(path)))
def snapshot(path,label):
 path=Path(path)
 if path.is_symlink():raise ValueError('symlink classpath input')
 if str(path) in seen:return seen[str(path)]
 dest=cache/(label+'.jar')
 if path.is_file():shutil.copyfile(path,dest)
 else:
  with zipfile.ZipFile(dest,'w') as z:
   for f in sorted(path.rglob('*')):
    if f.is_symlink():raise ValueError('symlink classpath')
    if f.is_file():z.writestr(zipfile.ZipInfo(str(f.relative_to(path))),f.read_bytes())
 target='target-libs/'+dest.name;stage(dest,target);seen[str(path)]='{run}/'+target;return seen[str(path)]
cp=[]
for index,row in enumerate(rows):
 path=Path(row['path'])
 if any(path.is_relative_to(source/sub) for sub in ('fabric/build/classes','fabric/build/resources','common/build/libs')):continue
 cp.append(snapshot(path,'client-%03d'%index))
remap=[]
for index,path in enumerate((capture/'remap-classpath.txt').read_text().strip().split(':')):
 remap.append(snapshot(path,'remap-%03d'%index))
d['generated_files']['target-remap-classpath.txt']=':'.join(remap)
# Observer render distance exceeds server tracking distance: actual proxy remains inside native fog.
for name,value in list(d['generated_files'].items()):
 if name.endswith('/options.txt') and name.startswith('instances/'):
  d['generated_files'][name]=value.replace('renderDistance:6','renderDistance:16')
if not any(row['sha256']==hashfile(a.candidate) for row in d['candidate_artifacts']):raise ValueError('target candidate must equal exact observer candidate')
stage(a.candidate,'elytra-target/mods/lod-server-support-fabric.jar')
stage(a.fixture,'elytra-target/mods/lss-elytra-fixture.jar')
if not any(row['sha256']==hashfile(a.fixture) for row in d['candidate_artifacts']):
 sys.path.insert(0,str(source/'tools/compat'));from catalog import inspect_jar
 row=inspect_jar(Path(a.fixture));metadata=row['metadata']['fabric'];row.update(id=metadata['id'],version=metadata['version'],source='local-cache:sha256:'+row['sha256'],enabled=True,kind='mod')
 d['candidate_artifacts'].append(row);d['cache'][row['sha256']]=str(Path(a.fixture).resolve())
 stage(a.fixture,'instances/lss-rig-client/minecraft/mods/'+Path(a.fixture).name)
sections={};current=None
for text in (capture/'dli-config.txt').read_text().splitlines():
 if text.startswith('\t'):
  if current is not None:sections[current].append(text.strip())
 else:current=text.strip();sections[current]=[]
args=sections['clientArgs'];assetindex=args[args.index('--assetIndex')+1];assetroot=Path(args[args.index('--assetsDir')+1])
index=assetroot/'indexes'/(assetindex+'.json')
if not index.is_file():raise ValueError('captured launcher cache lacks actual target asset index')
stage(index,'target-assets/indexes/'+index.name)
asset_hashes={'indexes/'+index.name:hashfile(index)}
for value in json.loads(index.read_text())['objects'].values():
 h=value['hash'];relative='objects/'+h[:2]+'/'+h;asset=assetroot/relative
 if relative in asset_hashes:continue
 if not asset.is_file() or hashlib.sha1(asset.read_bytes()).hexdigest()!=h:raise ValueError('missing/changed immutable asset')
 stage(asset,'target-assets/'+relative);asset_hashes[relative]=hashfile(asset)
d.setdefault('immutable_trees',{})['target-assets']=asset_hashes
d['generated_files']['elytra-target/options.txt']='maxFps:30\nrenderDistance:6\nsimulationDistance:5\npauseOnLostFocus:false\nenableVsync:false\nonboardAccessibility:false\ntoggleCrouch:false\n'
d['generated_files']['elytra-target/config/lss-client-config.json']=json.dumps(dict(receiveServerLods=True,enableXaeroMapBridge=False,lodDistanceChunks=32,farPlayersEnabled=True,farPlayersShareSelf=True))+'\n'
java=d['java']
components={c['uid']:c['version'] for c in json.loads((capture/'target-components.json').read_text())};mc=components['net.minecraft']
if mc not in ('1.21.10','1.21.11') or components.get('net.fabricmc.fabric-loader')!='0.19.3' or components.get('java')!='21':raise ValueError('target requires captured supported MC/Fabric0.19.3/Java21 closure')
if not any(Path(row['path']).name=='fabric-loader-0.19.3.jar' for row in rows):raise ValueError('actual captured target loader differs from declared component')
d['launches'].append(dict(id='elytra-target',cwd='elytra-target',argv=[java,'-Xms256M','-Xmx1500M','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=intermediary','-Dfabric.defaultMixinRemapType=mixin','-Dfabric.remapClasspathFile={run}/target-remap-classpath.txt','-Dlss.rig.elytraTarget=true','-Dlss.rig.runId={run_id}','-cp',':'.join(cp),'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','ElytraSubject','--version',mc,'--accessToken','0','--gameDir','{run}/elytra-target','--assetsDir','{run}/target-assets','--assetIndex',assetindex,'--quickPlayMultiplayer','{endpoint}','--width','960','--height','540']))
for name in ('runtime-classpath.json','launch-settings.json','target-components.json','dli-config.txt','jvm-argfile.txt'):stage(capture/name,'evidence/target-'+name)
identity=dict(candidate_sha256=hashfile(a.candidate),fixture_sha256=hashfile(a.fixture),native_classpath=cp,mode='named native runtime; actual intermediary candidate and fixture are loader-remapped from private mods directory',subject='ElytraSubject',test_consumer='explicit actual LSSApi callbacks; no terrain renderer claim')
identityfile=out/'target-identity.json';identityfile.write_text(json.dumps(identity,indent=2)+'\n');stage(identityfile,'evidence/target-identity.json')
# Independently lock the direct target participant; the observer profile cannot identify it.
sys.path.insert(0,str(source/'tools/compat'))
from catalog import inspect_jar,Invalid,digest,validate_profile
artifacts=[];candidates=[]
for target in dict.fromkeys(cp+['{run}/elytra-target/mods/lod-server-support-fabric.jar','{run}/elytra-target/mods/lss-elytra-fixture.jar']):
 relative=target.removeprefix('{run}/');item=next(r for r in d['stage_files'] if r['target']==relative);path=Path(item['source'])
 try:metadata=inspect_jar(path)['metadata']
 except Invalid as error:
  if 'no recognized mod metadata' not in str(error):raise
  metadata={}
 fabric=metadata.get('fabric',{});entry=dict(id=fabric.get('id','library-'+item['sha256'][:16]),version=fabric.get('version','locked'),file=path.name,sha256=item['sha256'],source='local-cache:sha256:'+item['sha256'],metadata=metadata,enabled=True,kind='mod' if fabric else 'library')
 (candidates if '/mods/' in relative else artifacts).append(entry);d['cache'][item['sha256']]=str(path)
profile=dict(schema_version=1,id='mc'+mc.replace('.','')+'-elytra-target',line=mc,platform='fabric',route='native',status='unverified',components=json.loads((capture/'target-components.json').read_text()),artifacts=artifacts,capabilities=['actual-client','test-only-consumer','far-player-subject'],limitations=['Independent native target; no target-side terrain renderer claim.'])
validate_profile(profile,allow_unresolved_ranges=True);profile_path=out/'target-profile.json';profile_path.write_text(json.dumps(profile,indent=2)+'\n')
d.setdefault('client_profiles',[]).append(dict(role='elytra-target',path=str(profile_path),id=profile['id'],profile_hash=digest(profile),candidate_artifacts=candidates))
for name,value in list(d['generated_files'].items()):
 if name.endswith('/instance.cfg'):d['generated_files'][name]=value.replace('JvmArgs=','JvmArgs=-Dlss.rig.initialEndpoint={endpoint} ')
 if name.endswith('/config/lss-client-config.json'):
  cfg=json.loads(value);cfg.update(farPlayersEnabled=True,farPlayersShareSelf=True,receiveServerLods=True);d['generated_files'][name]=json.dumps(cfg)+'\n'
 if name.endswith('/options.txt') and name.startswith('instances/'):
  values=[line for line in value.splitlines() if not line.startswith(('renderDistance:','fov:'))];d['generated_files'][name]='\n'.join(values+['renderDistance:16','fov:0.0'])+'\n'
scenario=json.loads((source/'tools/rig/scenarios/elytra.json').read_text());scenario.update(version=2,checker='elytra',requires_handshake=True)
scenario['phases']=['equipped','crouched','standing_recovered','falling','gliding','landed']
scenario['scope']='Actual LSS far-player proxy flight/pose rendering; Voxy terrain rendering is outside this gate.'
(out/'scenario.json').write_text(json.dumps(scenario,indent=2)+'\n')
d['elytra_contract']=dict(subject='ElytraSubject',observer='Voximus_Maximus',artifacts={r['target']:r['sha256'] for r in d['stage_files'] if r['sha256'] in {hashfile(a.candidate),hashfile(a.fixture)}},target_profile_hash=digest(profile))
# A draft remains unable to drive input until the new fixture and maintained checker exist.
with zipfile.ZipFile(a.fixture) as z:fixture_ready=b'LSS_ELYTRA_NATIVE' in z.read('dev/vox/lssfixture/elytra/Probe.class')
checker_ready=(source/'tools/rig/check_elytra_run.py').is_file() and 'from check_elytra_run import check_report' in (source/'tools/rig/proof.py').read_text()
d['elytra_ready']=fixture_ready and checker_ready
d['launches'].append(dict(id='elytra-controller',cwd='client',argv=[sys.executable,str(source/'tools/rig/drive_elytra.py'),'{run}']))
(out/'readiness.json').write_text(json.dumps(dict(fixture_ready=fixture_ready,checker_ready=checker_ready,launch_ready=d['elytra_ready']),indent=2)+'\n')
(out/'runtime.json').write_text(json.dumps(d,indent=2)+'\n');print(out/'runtime.json')
