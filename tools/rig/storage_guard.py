"""Bounded staging/start storage checks; cheap owned-runtime low-water sampling."""
import base64,json,os,re,shutil,subprocess,time
from pathlib import Path
GIB=1024**3
RESERVE=50*GIB
CADENCE_SECONDS=60

def existing(path):
 path=Path(path).absolute()
 while not path.exists():path=path.parent
 return path

def wsl():return bool(os.environ.get('WSL_DISTRO_NAME')) or 'microsoft' in Path('/proc/sys/kernel/osrelease').read_text().lower()
def capacity(path):
 st=os.statvfs(path)
 return {'free_bytes':st.f_bavail*st.f_frsize,'size_bytes':st.f_blocks*st.f_frsize}

def estimate(profile,runtime):
 # Every actual copy is explicitly enumerated by rig.create; count duplicate
 # source bytes separately when copied to distinct targets. No tree/content scan.
 paths=[runtime['cache'][a['sha256']] for a in profile['artifacts'] if a.get('enabled',True)]
 paths += [r['source'] for r in runtime.get('stage_files',[])]
 copied=64*1024**2 # retained tooling, manifests, directory metadata and path expansion
 for name in paths:
  path=Path(name)
  if path.is_symlink() or not path.is_file():raise ValueError('storage estimate requires regular explicit staging inputs')
  copied+=((path.stat().st_size+4095)//4096)*4096
 copied+=sum(len(v.encode())+4096 for v in runtime.get('generated_files',{}).values())
 return {'schema_version':1,'copy_bytes':copied,'growth_bytes':max(copied,8*GIB),'copied_files':len(paths)}

def requirement(value,phase):
 if (not isinstance(value,dict) or set(value)!={'schema_version','copy_bytes','growth_bytes','copied_files'} or value['schema_version']!=1
     or any(type(value[k]) is not int or value[k]<0 for k in ('copy_bytes','growth_bytes','copied_files'))
     or value['growth_bytes']<max(value['copy_bytes'],8*GIB)):raise ValueError('invalid stored staging estimate')
 if phase not in ('create','run'):raise ValueError('unknown storage preflight phase')
 return RESERVE+value['growth_bytes']+(value['copy_bytes'] if phase=='create' else 0)

def windows_host():
 # Values are JSON/base64 data, never interpolated as PowerShell expressions.
 cfg=base64.b64encode(json.dumps({'distro':os.environ.get('WSL_DISTRO_NAME'),'fallback':os.environ.get('LSS_RIG_WSL_VHD_PATH')}).encode()).decode()
 script=r"""$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';
$cfg=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('PAYLOAD')) | ConvertFrom-Json;
$rows=@(); try{$rows=@(Get-ChildItem -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss' | ForEach-Object {Get-ItemProperty -LiteralPath $_.PSPath} | Where-Object {$_.DistributionName -eq $cfg.distro})}catch{$rows=@()};
$origin='registry'; $vhd=$null;
if($rows.Count -eq 1){$name=$rows[0].VhdFileName; if(-not $name){$name='ext4.vhdx'}; $vhd=Join-Path $rows[0].BasePath $name}
elseif($cfg.fallback){$vhd=$cfg.fallback; $origin='explicit-fallback'}
else{throw 'Current WSL VHD host location unknown; set LSS_RIG_WSL_VHD_PATH explicitly'};
$file=Get-Item -LiteralPath $vhd; if($file.PSIsContainer -or $file.Extension -ne '.vhdx'){throw 'Expected actual VHDX file'};
$vol=Get-Volume -FilePath $file.FullName;
if(-not $vol.UniqueId -or -not $vol.DriveLetter -or $vol.Size -le 0){throw 'VHD host volume/drive unavailable'};
[pscustomobject]@{origin=$origin;vhd_path=$file.FullName;vhd_bytes=[int64]$file.Length;volume_id=$vol.UniqueId;drive_letter=[string]$vol.DriveLetter;free_bytes=[int64]$vol.SizeRemaining;size_bytes=[int64]$vol.Size} | ConvertTo-Json -Compress
""".replace('PAYLOAD',cfg)
 executable=shutil.which('powershell.exe') or '/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
 try:
  result=subprocess.run([executable,'-NoProfile','-NonInteractive','-EncodedCommand',base64.b64encode(script.encode('utf-16le')).decode()],capture_output=True,text=True,timeout=20,check=True)
  host=json.loads(result.stdout.lstrip('\ufeff'))
 except (OSError,subprocess.SubprocessError,ValueError) as error:raise ValueError('Cannot establish actual WSL VHD host capacity; no Linux-df fallback') from error
 if (not re.fullmatch('[A-Za-z]',host.get('drive_letter','')) or not host.get('volume_id') or
     any(type(host.get(k)) is not int or host[k]<0 for k in ('free_bytes','size_bytes','vhd_bytes'))):raise ValueError('Invalid host volume capacity response')
 return host

def unescape(value):return re.sub(r'\\([0-7]{3})',lambda m:chr(int(m[1],8)),value)
def drvfs(host):
 matches=[]
 for line in Path('/proc/self/mountinfo').read_text().splitlines():
  left,separator,right=line.partition(' - ')
  if not separator:continue
  fields=right.split();source=unescape(fields[1]) if len(fields)>1 else ''
  if fields and fields[0] in ('9p','drvfs') and source.upper()==host['drive_letter'].upper()+':\\' and ('aname=drvfs' in right or fields[0]=='drvfs'):
   path=Path(unescape(left.split()[4]));actual=capacity(path)
   if abs(actual['size_bytes']-host['size_bytes'])<=max(64*1024**2,host['size_bytes']//1000):matches.append(str(path))
 if len(matches)!=1:raise ValueError('No unique verified DrvFS monitor for actual VHD host volume')
 return matches[0]

def preflight(profile,runtime,state,value=None,phase='create'):
 if phase=='run' and value is None:raise ValueError('Created run lacks immutable storage estimate')
 value=estimate(profile,runtime) if value is None else value;required=requirement(value,phase)
 linux_path=existing(state);linux=capacity(linux_path)
 if linux['free_bytes']<required:raise ValueError('Linux storage below 50 GiB reserve plus run budget')
 host=None;mount=None
 if wsl():
  host=windows_host();mount=drvfs(host)
  if min(host['free_bytes'],capacity(mount)['free_bytes'])<required:raise ValueError('Physical VHD host storage below 50 GiB reserve plus run budget')
 return {'schema_version':1,'phase':phase,'estimate':value,'required_free_bytes':required,'reserve_bytes':RESERVE,'linux_path':str(linux_path),'linux':linux,'host':host,'host_monitor':mount}

class Monitor:
 def __init__(self,root,preflight):
  self.root=Path(root);self.linux=preflight['linux_path'];self.mount=preflight['host_monitor'];self.host=preflight['host'];self.last=None
 def check(self):
  now=time.monotonic()
  if self.last is not None and now-self.last<CADENCE_SECONDS:return
  self.last=now;linux=capacity(self.linux);host=None
  if self.mount:
   if drvfs(self.host)!=self.mount:raise ValueError('Physical host monitor mount changed')
   host=capacity(self.mount)
  if linux['free_bytes']<RESERVE or (host is not None and host['free_bytes']<RESERVE):raise ValueError('Runtime storage low-water floor reached; stopping owned run')
  from rig import write
  write(self.root/'evidence/storage-health.json',{'sample_monotonic':now,'reserve_bytes':RESERVE,'linux':linux,'host':host})
