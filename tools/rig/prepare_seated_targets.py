#!/usr/bin/env python3
"""Extend an owned Prism observer recipe with an two independent, directly launched seated targets."""
import argparse,json,hashlib,zipfile,shutil,sys
from pathlib import Path
p=argparse.ArgumentParser()
for flag in ('source-worktree','capture','runtime','candidate','fixture','output'):p.add_argument('--'+flag,required=True)
a=p.parse_args();source=Path(a.source_worktree).resolve();capture=Path(a.capture).resolve();out=Path(a.output).resolve()
sys.path.insert(0,str(source/'tools/rig'));from rig import require_lock,digest
require_lock()
out.mkdir(parents=True,exist_ok=False);cache=out/'cache';cache.mkdir()
d=json.loads(Path(a.runtime).read_text());rows=json.loads((capture/'runtime-classpath.json').read_text())
hashfile=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
for row in rows:
 path=Path(row['path'])
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
  d['generated_files'][name]=value.replace('renderDistance:6','renderDistance:12')
if not any(row['sha256']==hashfile(a.candidate) for row in d.get('server_profile',{}).get('candidate_artifacts',[])):
 raise ValueError('Fabric target candidate must equal the explicit owned Fabric server candidate')
stage(a.candidate,'seated-target-a/mods/lod-server-support-fabric.jar')
stage(a.fixture,'seated-target-a/mods/lss-wi9-fixture.jar')
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
d['generated_files']['seated-target-a/options.txt']='maxFps:30\nrenderDistance:6\nsimulationDistance:5\npauseOnLostFocus:false\nenableVsync:false\nonboardAccessibility:false\ntoggleCrouch:false\n'
d['generated_files']['seated-target-a/config/lss-client-config.json']=json.dumps(dict(receiveServerLods=True,enableXaeroMapBridge=False,lodDistanceChunks=32,farPlayersEnabled=True,farPlayersShareSelf=True))+'\n'
java=d['java']
components={c['uid']:c['version'] for c in json.loads((capture/'target-components.json').read_text())};mc=components['net.minecraft']
if mc != '1.21.1' or components.get('net.fabricmc.fabric-loader')!='0.19.3' or components.get('java')!='21':raise ValueError('target requires captured supported MC/Fabric0.19.3/Java21 closure')
if not any(Path(row['path']).name=='fabric-loader-0.19.3.jar' for row in rows):raise ValueError('actual captured target loader differs from declared component')
d['launches'].append(dict(id='seated-target-a',cwd='seated-target-a',argv=[java,'-Xms256M','-Xmx1500M','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace=intermediary','-Dfabric.defaultMixinRemapType=mixin','-Dfabric.remapClasspathFile={run}/target-remap-classpath.txt','-Dlss.rig.seatedTarget=true','-Dlss.rig.runId={run_id}','-cp',':'.join(cp),'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','SeatedSubjectA','--version',mc,'--accessToken','0','--gameDir','{run}/seated-target-a','--assetsDir','{run}/target-assets','--assetIndex',assetindex,'--quickPlayMultiplayer','{endpoint}','--width','960','--height','540']))
# Separate offline native subject; same immutable closure, distinct game/cache root.
import copy
first=d['launches'][-1];second=copy.deepcopy(first)
second['id']='seated-target-b';second['cwd']='seated-target-b'
second['argv']=[value.replace('seated-target-a','seated-target-b').replace('SeatedSubjectA','SeatedSubjectB') for value in second['argv']]
d['launches'].append(second)
for row in list(d['stage_files']):
 if row['target'].startswith('seated-target-a/'):
  twin=dict(row);twin['target']=row['target'].replace('seated-target-a/','seated-target-b/',1);d['stage_files'].append(twin)
for name,value in list(d['generated_files'].items()):
 if name.startswith('seated-target-a/'):d['generated_files'][name.replace('seated-target-a/','seated-target-b/',1)]=value
# Bind every actual named dependency and both candidate/fixture artifacts as a
# separate test-only Fabric subject profile, independently of the observer loader.
sys.path.insert(0,str(source/'tools/compat'))
from catalog import inspect_jar,Invalid
subject_artifacts=[];subject_candidates=[]
for target in dict.fromkeys(cp+['{run}/seated-target-a/mods/lod-server-support-fabric.jar','{run}/seated-target-a/mods/lss-wi9-fixture.jar']):
 relative=target.removeprefix('{run}/')
 item=next(row for row in d['stage_files'] if row['target']==relative)
 artifact_path=Path(item['source']);checksum=item['sha256']
 try:metadata=inspect_jar(artifact_path)['metadata']
 except Invalid as error:
  if 'no recognized mod metadata' not in str(error):raise
  metadata={}
 fabric=metadata.get('fabric',{})
 entry=dict(id=fabric.get('id','library-'+checksum[:16]),version=fabric.get('version','locked'),file=artifact_path.name,
            sha256=checksum,source='local-cache:sha256:'+checksum,metadata=metadata,enabled=True,
            kind='mod' if fabric else 'library')
 (subject_candidates if '/mods/' in relative else subject_artifacts).append(entry)
 d['cache'][checksum]=str(artifact_path)
subject_profile=dict(schema_version=1,id='mc1211-fabric-seated-subject',line='1.21.1',platform='fabric',route='native',
 components=json.loads((capture/'target-components.json').read_text()),artifacts=subject_artifacts,status='unverified',
 capabilities=['actual-client','test-only-consumer','far-player-subject'],limitations=['Named native client closure; no subject-side terrain renderer.'])
subject_path=out/'subject-profile.json';subject_path.write_text(json.dumps(subject_profile,indent=2)+'\n')
for role in ('seated-target-a','seated-target-b'):
 d.setdefault('client_profiles',[]).append(dict(role=role,path=str(subject_path),id=subject_profile['id'],profile_hash=digest(subject_profile),candidate_artifacts=subject_candidates))
for name,value in list(d['generated_files'].items()):
 if name.startswith('instances/') and name.endswith('/instance.cfg'):
  d['generated_files'][name]=value.replace('JvmArgs=', 'JvmArgs=-Dlss.wi9.enabled=true -Dlss.wi9.subjectA=SeatedSubjectA -Dlss.wi9.subjectB=SeatedSubjectB ')
 if name.startswith('instances/') and name.endswith('/options.txt'):
  d['generated_files'][name]=value+'fov:-1.0\n'
 if name.startswith('instances/') and name.endswith('/config/lss-client-config.json'):
  cfg=json.loads(value);cfg.update(farPlayersEnabled=True,farPlayersShareSelf=True);d['generated_files'][name]=json.dumps(cfg)+'\n'
for name in ('runtime-classpath.json','launch-settings.json','target-components.json','dli-config.txt','jvm-argfile.txt'):stage(capture/name,'evidence/target-'+name)
identity=dict(candidate_sha256=hashfile(a.candidate),fixture_sha256=hashfile(a.fixture),native_classpath=cp,mode='named native runtime; actual intermediary candidate and fixture are loader-remapped from private mods directory',subjects=['SeatedSubjectA','SeatedSubjectB'],test_consumer='explicit actual LSSApi callbacks; no terrain renderer claim')
identityfile=out/'target-identity.json';identityfile.write_text(json.dumps(identity,indent=2)+'\n');stage(identityfile,'evidence/target-identity.json')
d['launches'].append(dict(id='seated-controller',cwd='client',argv=[sys.executable,str(source/'tools/rig/drive_seated_draw.py'),'{run}']))
(out/'runtime.json').write_text(json.dumps(d,indent=2)+'\n');print(out/'runtime.json')
