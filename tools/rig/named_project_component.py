"""Bind project-built named outputs separately from immutable vendor profile locks."""
import hashlib,json,zipfile
from pathlib import Path

def checksum(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def component(root,reference,staged,role):
 root=Path(root).resolve()
 expected=root/('fabric/build/devlibs/lod-server-support-fabric-dev.jar' if role=='main' else 'common/build/libs/common-'+dict(line.split('=',1) for line in (root/'gradle.properties').read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))['mod_version'].strip()+'.jar')
 target='artifacts/lss-'+('main' if role=='main' else 'common')+'-named.jar'
 if reference.get('origin')!=str(expected) or reference.get('target')!=target:raise ValueError('wrong explicit named project origin/role')
 if expected.is_symlink() or not expected.is_file():raise ValueError('named project origin missing')
 value=checksum(expected);snapshot=Path(staged[target]['source'])
 if snapshot.is_symlink() or not snapshot.is_file() or reference.get('sha256')!=value or staged[target]['sha256']!=value or checksum(snapshot)!=value:raise ValueError('stale origin or changed named snapshot bytes')
 with zipfile.ZipFile(snapshot) as jar:
  if role=='main':
   m=json.loads(jar.read('fabric.mod.json'))
   if m.get('id')!='lss' or m.get('depends',{}).get('minecraft')!='1.21.11':raise ValueError('named main identity differs')
  elif 'dev/vox/lss/common/Brand.class' not in jar.namelist():raise ValueError('common project component missing actual common classes')
 return value

def no_project_vendor_artifacts(profile,project_hashes,cache=None):
 if any(row.get('sha256') in project_hashes or row.get('id') in ('lss-common-named','lss-main-named') for row in profile.get('artifacts',[])):raise ValueError('project component misclassified as vendor dependency')

 if cache is not None:
  for row in profile.get('artifacts',[]):
   path=Path(cache.get(row['sha256'],''))
   if not path.is_file() or checksum(path)!=row['sha256']:raise ValueError('vendor input bytes unavailable for project ownership check')
   with zipfile.ZipFile(path) as jar:
    if 'dev/vox/lss/common/Brand.class' in jar.namelist():raise ValueError('project common classes misclassified as vendor dependency')
