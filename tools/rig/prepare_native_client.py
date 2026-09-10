#!/usr/bin/env python3
"""Snapshot an actual Loom client closure into a reviewable direct-client runtime."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def prepare(runtime,capture,source,candidate,output):
    out=Path(output);capture=Path(capture);source=Path(source).resolve();candidate=Path(candidate)
    if out.exists():raise ValueError('refuse existing client closure')
    out.mkdir(parents=True);d=runtime
    components={r['uid']:r['version'] for r in json.loads((capture/'target-components.json').read_text())};mc=components['net.minecraft'];java=components['java']
    if components['net.fabricmc.fabric-loader']!='0.19.3' or java!=('25' if mc.startswith('26.') else '21'):raise ValueError('captured native toolchain mismatch')
    if not any(r['sha256']==sha(candidate) for r in d['candidate_artifacts']):raise ValueError('exact client candidate absent from runtime manifest')
    seen={}
    def stage(path,target):d['stage_files'].append(dict(source=str(Path(path).resolve()),sha256=sha(path),target=target))
    def snapshot(path):
        path=Path(path)
        if path.is_symlink():raise ValueError('symlink native closure')
        if str(path) in seen:return seen[str(path)]
        if path.is_dir():
            result=out/('lookup-'+str(len(seen))+'.jar')
            with zipfile.ZipFile(result,'w') as jar:
                for p in sorted(path.rglob('*')):
                    if p.is_symlink():raise ValueError('symlink native closure')
                    if p.is_file():jar.writestr(zipfile.ZipInfo(str(p.relative_to(path))),p.read_bytes())
        else:
            result=out/(sha(path)+'-'+path.name)
            if not result.exists():shutil.copyfile(path,result)
        target='smoke-client-libs/'+result.name;stage(result,target);seen[str(path)]='{run}/'+target;return seen[str(path)]
    cp=[]
    for row in json.loads((capture/'runtime-classpath.json').read_text()):
        path=Path(row['path'])
        if any(path.is_relative_to(source/part) for part in ('fabric/build/classes','fabric/build/resources','common/build/libs')):continue
        actual={str(p.relative_to(path)):sha(p) for p in path.rglob('*') if p.is_file()} if row['directory'] else sha(path)
        if actual!=(row['files'] if row['directory'] else row['sha256']):raise ValueError('captured dependency changed: '+str(path))
        cp.append(snapshot(path))
    remap=[snapshot(p) for p in (capture/'remap-classpath.txt').read_text().strip().split(':') if p]
    d['generated_files']['smoke-remap-classpath.txt']=':'.join(remap)
    lines=(capture/'dli-config.txt').read_text().splitlines();args=[];active=False
    for line in lines:
        if not line.startswith((' ','\t')):active=line=='clientArgs';continue
        if active:args.append(line.strip())
    asset_index=args[args.index('--assetIndex')+1];assetroot=Path(args[args.index('--assetsDir')+1]);index=assetroot/'indexes'/(asset_index+'.json')
    stage(index,'assets/indexes/'+index.name);assetfiles={'indexes/'+index.name:sha(index)}
    for entry in json.loads(index.read_text())['objects'].values():
        digest=entry['hash'];relative='objects/'+digest[:2]+'/'+digest;asset=assetroot/relative
        if relative in assetfiles:continue
        if hashlib.sha1(asset.read_bytes()).hexdigest()!=digest:raise ValueError('asset digest mismatch')
        stage(asset,'assets/'+relative);assetfiles[relative]=sha(asset)
    d.setdefault('immutable_trees',{})['assets']=assetfiles
    stage(candidate,'smoke-template/mods/lod-server-support-fabric.jar')
    native=mc.startswith('26.');jvm=[d['java'],'-Xms256M','-Xmx1500M','-Dfabric.development=true','-Dfabric.defaultModDistributionNamespace='+('official' if native else 'intermediary'),'-Dfabric.defaultMixinRemapType='+('static' if native else 'mixin'),'-Dlss.rig.runId={run_id}']
    if native:jvm+=['--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow']
    else:jvm+=['-Dfabric.remapClasspathFile={run}/smoke-remap-classpath.txt']
    launch=dict(id='smoke-template',cwd='smoke-template',argv=jvm+['-cp',':'.join(cp),'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','RigSubjectA','--version',mc,'--accessToken','0','--gameDir','{run}/smoke-template','--assetsDir','{run}/assets','--assetIndex',asset_index,'--quickPlayMultiplayer','{endpoint}','--width','960','--height','540'])
    d['launches'].append(launch)
    for name in ('runtime-classpath.json','target-components.json','dli-config.txt','original-mod-inputs.json'):stage(capture/name,'evidence/smoke-'+name)
    (out/'runtime.json').write_text(json.dumps(d,indent=2)+'\n');return out/'runtime.json'
if __name__=='__main__':
    p=argparse.ArgumentParser()
    for n in ('runtime','capture','source','candidate','output'):p.add_argument('--'+n,required=True)
    a=p.parse_args();print(prepare(json.loads(Path(a.runtime).read_text()),a.capture,a.source,a.candidate,a.output))
