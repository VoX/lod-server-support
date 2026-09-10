#!/usr/bin/env python3
"""Advisory impact selection. Required build/release checks remain unchanged."""
import argparse,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def select(paths, tier, line, manifest=None):
    manifest=manifest or json.loads((ROOT/'config/test-impact.json').read_text())
    if manifest.get('schema_version') != 1: raise ValueError('unsupported impact schema')
    platform=[':common:test',':fabric:test',':paper:test',':neoforge:test']
    integration=[':fabric:runGameTest',':neoforge:runGameTestServer']
    if line!='1.21.1':integration.append(':fabric:runClientGameTest')
    full=[':common:test',':fabric:build',':paper:build',':neoforge:build',':neoforge:runGameTestServer']
    if tier=='full':return full
    if tier=='platform':return platform
    if tier=='integration':return integration
    if not paths:return full
    if all(p.endswith('.md') or p.startswith('docs/') for p in paths):return []
    if any(p.startswith(tuple(manifest['shared_prefixes']+manifest['tooling_prefixes'])) or not p.startswith(tuple(manifest['platform_prefixes'])) for p in paths):return full
    return [':common:test']+[':'+mod+':test' for mod in ('fabric','paper','neoforge') if any(p.startswith(mod+'/') for p in paths)]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('tier',choices=['fast','platform','integration','full']);p.add_argument('paths',nargs='*');p.add_argument('--run',action='store_true');p.add_argument('--root',type=Path,default=ROOT);a=p.parse_args()
    line=json.loads((a.root/'config/compatibility/line.json').read_text())['facts']['line'];tasks=select(a.paths,a.tier,line,json.loads((a.root/'config/test-impact.json').read_text()))
    report={'advisory':a.tier=='fast','line':line,'tasks':tasks,'cross_line_required':any(x.startswith(('common/','xplat/')) for x in a.paths),'catalog_checks':['python3 tools/compat/catalog.py validate','python3 tools/compat/catalog.py render --check']};print(json.dumps(report,indent=2))
    if a.run:
        for command in report['catalog_checks']:
            subprocess.run(command.split(),cwd=a.root,check=True)
        if tasks:subprocess.run(['./gradlew','--no-daemon',*tasks],cwd=a.root,check=True)
if __name__=='__main__':main()
