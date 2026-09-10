#!/usr/bin/env python3
"""Explicit pinned Neo installer acquisition/preparation; never uses game-test closure."""
import argparse,hashlib,json,subprocess,urllib.request,zipfile
from pathlib import Path

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def prepare(installer,expected_sha,version,minecraft,java,output,install=False):
    installer=Path(installer);out=Path(output)
    if out.exists():raise ValueError('refuse existing server preparation root')
    if installer.is_symlink() or sha(installer)!=expected_sha:raise ValueError('installer identity mismatch')
    with zipfile.ZipFile(installer) as jar:
        profile=json.loads(jar.read('install_profile.json'));version_json=json.loads(jar.read('version.json'))
    if profile.get('minecraft')!=minecraft or profile.get('version') not in ('neoforge-'+version,version):raise ValueError('installer MC/Neo version differs from exact profile')
    out.mkdir(parents=True)
    inputs=dict(installer_sha256=expected_sha,minecraft=minecraft,neoforge=version,installer_profile=profile,version_json=version_json,java=str(Path(java).resolve()))
    (out/'preparation-inputs.json').write_text(json.dumps(inputs,indent=2)+'\n')
    if not install:return dict(status='prepared-command-only',command=[java,'-jar',str(installer.resolve()),'--installServer',str(out.resolve())])
    with (out/'installer.log').open('x') as log:
        code=subprocess.run([java,'-jar',str(installer.resolve()),'--installServer',str(out.resolve())],cwd=out,stdout=log,stderr=subprocess.STDOUT).returncode
    if code:raise RuntimeError('native Neo installer failed; preserve '+str(out))
    args=out/'libraries/net/neoforged/neoforge'/version/'unix_args.txt'
    if not args.is_file():raise ValueError('installer produced no exact native server args')
    files={}
    for path in sorted(out.rglob('*')):
        if path.is_symlink():raise ValueError('symlink in prepared server closure')
        if path.is_file() and path.name not in ('installer.log','closure.json'):files[str(path.relative_to(out))]=sha(path)
    # Check every artifact with a vendor-declared download digest in both immutable metadata inputs.
    verified=[]
    for lib in profile.get('libraries',[])+version_json.get('libraries',[]):
        artifact=lib.get('downloads',{}).get('artifact',{});relative=artifact.get('path')
        if not relative:continue
        path=out/'libraries'/relative
        if not path.exists():continue # Client-only inputs need not be installed on a dedicated server.
        if artifact.get('sha1') and hashlib.sha1(path.read_bytes()).hexdigest()!=artifact['sha1']:raise ValueError('vendor artifact digest mismatch: '+relative)
        verified.append(relative)
    result=dict(status='native-installed-closure',minecraft=minecraft,neoforge=version,installer_sha256=expected_sha,files=files,vendor_verified_artifacts=verified,program_args=str(args.relative_to(out)),launch=[java,'-Xms512M','-Xmx2G','@user_jvm_args.txt','@'+str(args.relative_to(out)),'nogui'])
    (out/'closure.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
    p=argparse.ArgumentParser();
    for name in ('installer','sha256','version','minecraft','java','output'):p.add_argument('--'+name,required=True)
    p.add_argument('--install',action='store_true');p.add_argument('--acquire-url')
    a=p.parse_args();path=Path(a.installer)
    if a.acquire_url:
        if path.exists():raise ValueError('refuse overwriting installer')
        expected='https://maven.neoforged.net/releases/net/neoforged/neoforge/'+a.version+'/neoforge-'+a.version+'-installer.jar'
        if a.acquire_url!=expected:raise ValueError('installer URL must match exact official Maven coordinate')
        data=urllib.request.urlopen(expected,timeout=60).read(64*1024*1024+1)
        if len(data)>64*1024*1024 or hashlib.sha256(data).hexdigest()!=a.sha256:raise ValueError('acquired installer hash/size mismatch')
        path.write_bytes(data)
    print(json.dumps(prepare(path,a.sha256,a.version,a.minecraft,a.java,a.output,a.install),indent=2))
