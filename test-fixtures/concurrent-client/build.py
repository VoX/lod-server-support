#!/usr/bin/env python3
import tempfile
"""26.2 native-namespace fixture; explicit complete cached Loom classpath required."""
import argparse,subprocess,zipfile,json,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True);p.add_argument('--classpath-file',required=True);p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parent;out=Path(a.output);out.mkdir(parents=True,exist_ok=True);temporary=tempfile.TemporaryDirectory(prefix='classes-',dir=out);classes=Path(temporary.name)
lines=Path(a.classpath_file).read_text().splitlines();cp=lines[lines.index('-classpath')+1] if '-classpath' in lines else lines[0]
subprocess.run([str(Path(a.java_home)/'bin/javac'),'--release','25','-proc:none','-cp',cp,'-d',str(classes),*map(str,(root/'src').rglob('*.java'))],check=True)
jar=out/'lss-rig-concurrent-client.jar'
with zipfile.ZipFile(jar,'w') as z:
 for base in (classes,root/'resources'):
  for f in base.rglob('*'):
   if f.is_file():z.write(f,f.relative_to(base))
(out/'identity.json').write_text(json.dumps({'fixture_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()},indent=2)+'\n')
