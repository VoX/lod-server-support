#!/usr/bin/env python3
import tempfile
import argparse,subprocess,zipfile,hashlib,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True);p.add_argument('--asm',required=True);p.add_argument('--server',required=True);p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parent;out=Path(a.output);out.mkdir(parents=True,exist_ok=True);temporary=tempfile.TemporaryDirectory(prefix='classes-',dir=out);classes=Path(temporary.name)
subprocess.run([str(Path(a.java_home)/'bin/javac'),'--release','21','-cp',a.asm,'-d',str(classes),*map(str,root.rglob('*.java'))],check=True)
jar=out/'lss-rig-folia-timing.jar'
with zipfile.ZipFile(jar,'w') as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: dev.vox.lssfixture.timing.TimingAgent\n\n')
 for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes))
 with zipfile.ZipFile(a.asm) as dep:
  for name in dep.namelist():
   if name.endswith('.class') and name!='module-info.class':z.writestr(name,dep.read(name))
with zipfile.ZipFile(out/'lss-rig-observer.jar','w') as observer:
 for f in classes.rglob('TimingRecorder*.class'):observer.write(f,f.relative_to(classes))
with zipfile.ZipFile(a.server) as server:checksum=hashlib.sha256(server.read('io/papermc/paper/threadedregions/TickRegions$ConcreteRegionTickHandle.class')).hexdigest()
(out/'identity.json').write_text(json.dumps({'target_class_sha256':checksum,'fixture_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()},indent=2)+'\n')
print(checksum)
