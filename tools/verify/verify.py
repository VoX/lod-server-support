#!/usr/bin/env python3
"""Advisory impact selection. Required build/release checks remain unchanged."""
import argparse,json,subprocess,sys,os,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def select(paths, tier, line, manifest=None):
    manifest=manifest or json.loads((ROOT/'config/test-impact.json').read_text())
    if manifest.get('schema_version') != 1: raise ValueError('unsupported impact schema')
    platform=[':common:test',':fabric:test',':paper:test',':neoforge:test']
    integration=[':fabric:runGameTest',':neoforge:runGameTestServer']
    if line!='1.21.1':integration.append(':fabric:runClientGameTest')
    full=[':common:test',':fabric:build',':paper:build',':neoforge:build',':neoforge:runGameTestServer']
    if line!='1.21.1':full.append(':fabric:runClientGameTest')
    if tier=='full':return full
    if tier=='platform':return platform
    if tier=='integration':return integration
    if not paths:return full
    if all(p.endswith('.md') or p.startswith('docs/') for p in paths):return []
    if any(p.startswith(tuple(manifest['shared_prefixes']+manifest['tooling_prefixes'])) or not p.startswith(tuple(manifest['platform_prefixes'])) for p in paths):return full
    return [':common:test']+[':'+mod+':test' for mod in ('fabric','paper','neoforge') if any(p.startswith(mod+'/') for p in paths)]

def cross_line_required(paths, manifest):
    if not paths:return True # No impact information cannot narrow the line set.
    source_paths=[p for p in paths if not (p.endswith('.md') or p.startswith('docs/'))]
    return any(p.startswith(tuple(manifest['shared_prefixes']+manifest['tooling_prefixes']))
               or not p.startswith(tuple(manifest['platform_prefixes'])) for p in source_paths)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('tier',choices=['fast','platform','integration','full']);p.add_argument('paths',nargs='*');p.add_argument('--run',action='store_true');p.add_argument('--root',type=lambda value:Path(value).resolve(),default=ROOT);a=p.parse_args()
    line=json.loads((a.root/'config/compatibility/line.json').read_text())['facts']['line'];manifest=json.loads((a.root/'config/test-impact.json').read_text());tasks=select(a.paths,a.tier,line,manifest)
    report={'advisory':a.tier=='fast','line':line,'tasks':tasks,'cross_line_required':cross_line_required(a.paths,manifest),'catalog_checks':['python3 tools/compat/catalog.py validate','python3 tools/compat/catalog.py render --check']};print(json.dumps(report,indent=2))
    if a.run:
        for command in report['catalog_checks']:
            subprocess.run(command.split(),cwd=a.root,check=True)
        if tasks:
            command=['bash',str(a.root/'tools/verify/run-gradle.sh'),*tasks];environment=dict(os.environ)
            if sys.platform.startswith('linux') and ':fabric:runClientGameTest' in tasks:
                if not shutil.which('xvfb-run'):raise ValueError('Linux client gametests require xvfb-run for a private display')
                command+=['--init-script',str(a.root/'tools/verify/private-client-tests.init.gradle')]
                environment['DISPLAY']='';environment['XDG_SESSION_TYPE']='x11'
                environment['ALSOFT_DRIVERS']='null' # Silent native tests avoid WSL audio-device startup stalls.
                environment.pop('WAYLAND_DISPLAY',None);environment.pop('XAUTHORITY',None)
            subprocess.run(command,cwd=a.root,env=environment,check=True)
if __name__=='__main__':main()
