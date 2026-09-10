#!/usr/bin/env python3
"""Freeze an actual runServer development closure with only the packaged LSS mod.

This is an explicitly named development-bootstrap lane, never an installer/server
production-bootstrap claim. Fail on game-test launch, dangling references, or
unresolved dev-source folders. All transformations are retained and hashed.
"""
import argparse,hashlib,json,re,shutil
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def prepare(capture,source,candidate,java,output):
    capture=Path(capture);source=Path(source).resolve();out=Path(output);candidate=Path(candidate)
    if out.exists():raise ValueError('refuse existing captured server closure')
    row=json.loads((capture/'capture.json').read_text())
    if row.get('route')!='actual-runServer-preparation-no-launch' or row.get('task')!=':neoforge:runServer':raise ValueError('actual native runServer capture required')
    if any('gametest' in str(value).lower() for value in [row['mainClass'],*row['jvmArgs'],*row['args']]):raise ValueError('game-test capture cannot serve native server gate')
    out.mkdir(parents=True);mapping={};transforms=[]
    excluded=[source/p for p in ('neoforge/build/classes','neoforge/build/resources','common/build/classes','common/build/resources','common/build/libs')]
    def omit(path):return any(Path(path).is_relative_to(p) for p in excluded)
    absolute=re.compile(r'(?<![A-Za-z0-9_.])/[^\s:\"\'<>]+')
    def text(value):
        def replace(match):
            path=Path(match.group(0))
            if omit(path):return ''
            if not path.exists():raise ValueError('unresolved absolute launch reference: '+str(path))
            return '{run}/server/'+snapshot(path)
        changed=absolute.sub(replace,value)
        # A deleted source entry must not imply a current-working-directory classpath entry.
        if ':' in changed:changed=':'.join(p for p in changed.split(':') if p)
        return changed
    def snapshot(path):
        path=Path(path)
        if path.is_symlink():raise ValueError('symlink native runtime dependency')
        key=str(path)
        if key in mapping:return mapping[key]
        if path.is_dir():raise ValueError('unexpected runtime directory; capture needs file-only native dependencies: '+key)
        name='captured/'+str(len(mapping))+'-'+path.name;mapping[key]=name;dest=out/name;dest.parent.mkdir(exist_ok=True)
        if path.suffix in ('.txt','.xml','.properties','.json'):
            original=path.read_text();rewritten=text(original);dest.write_text(rewritten);transforms.append(dict(source=key,source_sha256=sha(path),target=name,target_sha256=sha(dest)))
        else:shutil.copyfile(path,dest)
        return name
    args=[]
    for arg in row['jvmArgs']:
        if arg.startswith('-Dfml.modFolders=') or arg.startswith('-Dfabric.classPathGroups='):continue
        if arg.startswith('@'):args.append('@{run}/server/'+snapshot(arg[1:]))
        else:args.append(text(arg))
    cp=[snapshot(path) for path in row['classpath'] if not omit(path)]
    # allJvmArgs already contains -classpath on JavaExec; replace it with filtered exact closure.
    if cp:
        for option in ('-classpath','-cp'):
            if option in args:
                i=args.index(option);del args[i:i+2]
        args+=['-cp',':'.join('{run}/server/'+p for p in cp)]
    elif not any(option in args for option in ('-classpath','-cp','--class-path')):
        raise ValueError('native launch classpath provider missing from capture')
    args += [row['mainClass'],*[text(arg) for arg in row['args']]]
    mods=out/'mods';mods.mkdir();shutil.copyfile(candidate,mods/'lod-server-support-neoforge.jar')
    files={str(path.relative_to(out)):sha(path) for path in out.rglob('*') if path.is_file()}
    result=dict(status='native-captured-server-closure',route='actual-runServer-development-bootstrap-packaged-candidate',minecraft=row['minecraft'],neoforge=row['neoforge'],capture_sha256=sha(capture/'capture.json'),candidate_sha256=sha(candidate),files=files,transformations=transforms,launch=[java,*args],environment={'MOD_CLASSES':'','MOD_RESOURCES':'','FML_MOD_FOLDERS':''})
    (out/'closure.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
    p=argparse.ArgumentParser()
    for n in ('capture','source','candidate','java','output'):p.add_argument('--'+n,required=True)
    a=p.parse_args();print(json.dumps(prepare(a.capture,a.source,a.candidate,a.java,a.output),indent=2))
