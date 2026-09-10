"""Exact official Fabric installer preparation; no server launch or existing world reuse."""
import argparse,hashlib,json,re,subprocess,urllib.request,zipfile
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def acquire(version,output):
 out=Path(output)
 if out.exists() or not re.fullmatch(r'[0-9.]+',version):raise ValueError('fresh exact installer acquisition required')
 out.mkdir(parents=True);url='https://maven.fabricmc.net/net/fabricmc/fabric-installer/'+version+'/fabric-installer-'+version+'.jar'
 digest=urllib.request.urlopen(url+'.sha256',timeout=30).read(4096).decode().strip().split()[0].lower()
 if not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('official installer digest missing')
 data=urllib.request.urlopen(url,timeout=30).read(16*1024*1024+1)
 if len(data)>16*1024*1024 or hashlib.sha256(data).hexdigest()!=digest:raise ValueError('installer hash mismatch')
 p=out/('fabric-installer-'+version+'.jar');p.write_bytes(data);row=dict(path=str(p),sha256=digest,version=version,url=url,checksum_url=url+'.sha256',executed=False)
 (out/'installer.json').write_text(json.dumps(row,indent=2)+'\n');return row

def prepare(identity,minecraft,loader,java,output,install=False):
 identity=json.loads(Path(identity).read_text());out=Path(output);installer=Path(identity['path'])
 if out.exists() or installer.is_symlink() or sha(installer)!=identity['sha256']:raise ValueError('fresh directory/exact installer required')
 if not re.fullmatch('[0-9.]+',minecraft) or not re.fullmatch('[0-9.]+',loader):raise ValueError('exact MC/loader required')
 out.mkdir(parents=True);command=[java,'-jar',str(installer),'server','-mcversion',minecraft,'-loader',loader,'-downloadMinecraft','-dir',str(out)]
 (out/'inputs.json').write_text(json.dumps(dict(installer=identity,minecraft=minecraft,loader=loader,command=command,server_launched=False),indent=2)+'\n')
 if not install:return dict(status='prepared-command-only',command=command)
 with (out/'installer.log').open('x') as log:code=subprocess.run(command,cwd=out,stdout=log,stderr=subprocess.STDOUT).returncode
 if code:raise ValueError('native Fabric installer failed; preserve attempt')
 launcher=out/'fabric-server-launch.jar';server=out/'server.jar';loaderjar=out/'libraries/net/fabricmc/fabric-loader'/loader/('fabric-loader-'+loader+'.jar')
 if not launcher.is_file() or not server.is_file() or not loaderjar.is_file():raise ValueError('incomplete installed launcher/server/loader closure')
 with zipfile.ZipFile(server) as z:server_version=json.loads(z.read('version.json'))
 with zipfile.ZipFile(loaderjar) as z:loader_version=json.loads(z.read('fabric.mod.json'))
 if server_version.get('id')!=minecraft or loader_version.get('id')!='fabricloader' or loader_version.get('version') not in (loader,'${version}'):raise ValueError('installed actual MC/loader identity differs')
 loader_url='https://maven.fabricmc.net/net/fabricmc/fabric-loader/'+loader+'/fabric-loader-'+loader+'.jar.sha256'
 loader_hash=urllib.request.urlopen(loader_url,timeout=30).read(4096).decode().strip().split()[0].lower()
 if sha(loaderjar)!=loader_hash:raise ValueError('official loader artifact digest mismatch')
 # Bind official Mojang server digest independently, not only the installer's exit code.
 manifest=json.loads(urllib.request.urlopen('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',timeout=30).read())
 version=next(row for row in manifest['versions'] if row['id']==minecraft)
 raw=urllib.request.urlopen(version['url'],timeout=30).read()
 if hashlib.sha1(raw).hexdigest()!=version['sha1']:raise ValueError('official version metadata digest mismatch')
 metadata=json.loads(raw);download=metadata['downloads']['server']
 if hashlib.sha1(server.read_bytes()).hexdigest()!=download['sha1'] or server.stat().st_size!=download['size']:raise ValueError('official Minecraft server digest mismatch')
 (out/'minecraft-metadata.json').write_bytes(raw)
 files={}
 for path in sorted(out.rglob('*')):
  if path.is_symlink():raise ValueError('symlink in installed closure')
  if path.is_file() and path.name not in ('installer.log','closure.json'):files[str(path.relative_to(out))]=sha(path)
 result=dict(status='native-installed-fabric-closure',minecraft=minecraft,loader=loader,installer_sha256=identity['sha256'],server_sha1=download['sha1'],files=files,launch=[java,'-Xms512M','-Xmx2G','-Dlss.rig.runId={run_id}','-jar','fabric-server-launch.jar','nogui'])
 (out/'closure.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--acquire-version');p.add_argument('--installer-identity');p.add_argument('--minecraft');p.add_argument('--loader');p.add_argument('--java');p.add_argument('--output',required=True);p.add_argument('--install',action='store_true');a=p.parse_args()
 print(json.dumps(acquire(a.acquire_version,a.output) if a.acquire_version else prepare(a.installer_identity,a.minecraft,a.loader,a.java,a.output,a.install)))
