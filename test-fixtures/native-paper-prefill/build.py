#!/usr/bin/env python3
import tempfile
"""Compile against explicitly selected cached Paper server and library tree."""
import argparse
from pathlib import Path
import subprocess
import hashlib
import json
p=argparse.ArgumentParser()
p.add_argument('--java-home', required=True)
p.add_argument('--paper-server', required=True)
p.add_argument('--libraries', required=True)
p.add_argument('--output', required=True)
a=p.parse_args()
root=Path(__file__).resolve().parent
out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
temporary=tempfile.TemporaryDirectory(prefix='classes-',dir=out);classes=Path(temporary.name)
jars=[Path(a.paper_server),*sorted(Path(a.libraries).rglob('*.jar'))]
if any(not item.is_file() for item in jars) or len(jars)<=1:
    raise ValueError('explicit patched server and nonempty library closure required')
subprocess.run([str(Path(a.java_home)/'bin/javac'),'--release','25','-cp',':'.join(map(str,jars)),'-d',str(classes),*map(str,root.rglob('*.java'))],check=True)
jar=out/'lss-rig-native-prefill.jar'
subprocess.run([str(Path(a.java_home)/'bin/jar'),'--create','--file',str(jar),'-C',str(classes),'.','-C',str(root),'plugin.yml'],check=True)
(out/'build-identity.json').write_text(json.dumps({'server_sha256':hashlib.sha256(Path(a.paper_server).read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()},indent=2)+'\n')
