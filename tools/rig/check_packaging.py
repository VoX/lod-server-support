#!/usr/bin/env python3
"""All fixture packages/metadata must be absent from every maintained LSS/VSS jar."""
import argparse
from pathlib import Path
import zipfile
import io

def check(path):
    failures=[]
    def visit(data,label):
        with zipfile.ZipFile(data) as jar:
            for name in jar.namelist():
                lower=name.lower()
                if ('lssfixture' in lower or 'lss-wi5-fixture' in lower or 'lss-wi6-fixture' in lower or 'lss-wi9-fixture' in lower or 'lss-rig-regions' in lower
                        or lower.startswith('dev/vox/lss/paper/papersourceprobe')):
                    failures.append(label+'!'+name)
                if lower.endswith('.jar'): visit(io.BytesIO(jar.read(name)),label+'!'+name)
    visit(path,str(path));return failures
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('jars',nargs='+');a=p.parse_args()
    failures=[item for jar in a.jars for item in check(Path(jar))]
    print('\n'.join(failures) if failures else 'Fixture exclusion passed')
    raise SystemExit(bool(failures))
