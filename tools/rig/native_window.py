"""Find exactly one real Minecraft window for an owned, explicit native game root."""
import re,subprocess,os,zipfile
from pathlib import Path
from rig import guard_display,guard_window,inside,read,identity,descendant,alive

def prism_process(path, game, args, root):
 # Prism supplies Minecraft arguments over its launch protocol rather than argv.
 # Match its native Java entrypoint only within the exact declared owned instance.
 if root is None or args.count(b'org.prismlauncher.EntryPoint')!=1:return False
 runtime=read(root/'runtime.json');manifest=read(root/'manifest.json')
 if runtime.get('backend')!='isolated-linux-prism':return False
 if args.count(('-Dlss.rig.runId='+manifest['run_id']).encode())!=1:return False
 if (path/'cwd').resolve(strict=True)!=game:return False
 if (path/'exe').resolve(strict=True)!=Path(runtime['java']).resolve(strict=True):return False
 matches=[]
 for launch in runtime.get('launches',[]):
  argv=launch.get('argv',[])
  if '--instance' not in argv or '--java' not in argv:continue
  instance=argv[argv.index('--instance')+1]
  if Path(instance).name!=instance:continue
  if inside(root,'instances/'+instance+'/minecraft').resolve()!=game:continue
  if Path(argv[argv.index('--java')+1]).resolve()!=Path(runtime['java']).resolve():continue
  matches.append(instance)
 if len(matches)!=1:return False
 profile=read(root/'profile.json');pack=read(game.parent/'mmc-pack.json')
 expected=[c for c in profile['components']if c['uid']=='net.minecraft']
 if len(expected)!=1 or not any(c.get('uid')=='net.minecraft' and c.get('version')==expected[0]['version']for c in pack['components']):return False
 keys=[i for i,arg in enumerate(args)if arg in (b'-cp',b'-classpath',b'--class-path')]
 if len(keys)!=1:return False
 jars=args[keys[0]+1].decode().split(os.pathsep)
 # Native Prism's bootstrap class must actually exist in the regular classpath
 # jar, not merely occur as text in an arbitrary process's command line.
 bootstrap=[]
 for name in jars:
  jar=Path(name)
  if not jar.is_absolute():jar=game/jar
  if jar.name!='NewLaunch.jar' or not jar.is_file():continue
  try:
   with zipfile.ZipFile(jar)as archive:
    if 'org/prismlauncher/EntryPoint.class'in archive.namelist():bootstrap.append(jar.resolve())
  except (OSError,zipfile.BadZipFile):return False
 return len(bootstrap)==1


def matching_process(path, game, owner, root=None):
 pid=int(path.name)
 expected=identity(pid)
 if not alive(expected) or not descendant(pid,owner['pid']):return None
 args=(path/'cmdline').read_bytes().split(b'\0')
 if b'--gameDir' in args:
  index=args.index(b'--gameDir')
  argument=Path(args[index+1].decode())
  if not argument.is_absolute():argument=(path/'cwd').resolve(strict=True)/argument
  if argument.resolve()!=game:return None
 elif not prism_process(path,game,args,root):return None
 if identity(pid)!=expected or not alive(expected):return None
 if not descendant(pid,owner['pid']):return None
 return expected


def find(root,game_relative):
 root=Path(root);game=inside(root,game_relative).resolve();env=guard_display(root)
 owner=read(root/'owner.json');candidates=[]
 for path in Path('/proc').iterdir():
  if not path.name.isdigit():continue
  pid=int(path.name)
  try:
   expected=matching_process(path,game,owner,root)
   if expected is not None:candidates.append(expected)
  except (OSError,ValueError,UnicodeError,IndexError):continue
 if len(candidates)!=1:raise ValueError('exactly one owned native game process required')
 expected=candidates[0]
 tree=subprocess.check_output(['xwininfo','-root','-tree'],env=env,text=True,timeout=5)
 windows=[]
 for window in dict.fromkeys(re.findall(r'^\s+(0x[0-9a-fA-F]+)\s',tree,re.M)):
  try:
   properties=subprocess.check_output(['xprop','-id',window,'_NET_WM_PID','WM_CLASS'],env=env,text=True,stderr=subprocess.DEVNULL,timeout=3)
   if not re.search(r'_NET_WM_PID[^\n]*=\s*'+str(expected['pid'])+r'\s*(?:\n|$)',properties):continue
   if not re.search(r'WM_CLASS[^\n]*minecraft',properties,re.I):continue
   guard_window(root,int(window,16),expected);windows.append(window)
  except (subprocess.SubprocessError,ValueError):continue
 if len(windows)!=1:raise ValueError('exactly one owned native Minecraft window required')
 return windows[0],expected
