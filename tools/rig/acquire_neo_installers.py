"""Acquire only exact source-pinned official Neo installers; does not execute Java."""
import argparse,hashlib,json,re,urllib.request,zipfile,io
from pathlib import Path

def acquire(source,output):
 source=Path(source);out=Path(output)
 if out.exists():raise ValueError('fresh acquisition directory required')
 out.mkdir(parents=True)
 props=dict(x.split('=',1) for x in (source/'gradle.properties').read_text().splitlines() if '='in x and not x.startswith('#'))
 version=props['neoforge_version'];mc=props['minecraft_version']
 if not re.fullmatch(r'[0-9.]+',version):raise ValueError('exact numeric Neo version required')
 url='https://maven.neoforged.net/releases/net/neoforged/neoforge/'+version+'/neoforge-'+version+'-installer.jar'
 checksum=urllib.request.urlopen(url+'.sha256',timeout=30).read(4096).decode().strip().split()[0]
 if not re.fullmatch('[0-9a-fA-F]{64}',checksum):raise ValueError('official SHA256 absent')
 data=urllib.request.urlopen(url,timeout=60).read(64*1024*1024+1)
 if len(data)>64*1024*1024 or hashlib.sha256(data).hexdigest()!=checksum.lower():raise ValueError('official installer checksum/size mismatch')
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  profile=json.loads(z.read('install_profile.json'))
 if profile.get('minecraft')!=mc or profile.get('version') not in ('neoforge-'+version,version):raise ValueError('exact embedded installer identity mismatch')
 target=out/('neoforge-'+version+'-installer.jar');target.write_bytes(data)
 row=dict(line=('.'.join(mc.split('.')[:2]) if mc.startswith('26.') else mc),minecraft=mc,neoforge=version,source_properties_sha256=hashlib.sha256((source/'gradle.properties').read_bytes()).hexdigest(),url=url,checksum_url=url+'.sha256',sha256=checksum.lower(),path=str(target),bytes=len(data),embedded_version=profile['version'],executed=False)
 (out/'installer.json').write_text(json.dumps(row,indent=2)+'\n');return row
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--output',required=True);a=p.parse_args();print(json.dumps(acquire(a.source,a.output)))
