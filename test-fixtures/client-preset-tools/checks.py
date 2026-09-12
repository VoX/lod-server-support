import json,re,hashlib,zipfile,io
FORBIDDEN={'sodium','voxy','connector','connectorextras'}
def jar_mod_ids(data,depth=0):
 if depth>8:raise ValueError('nested mod depth exceeded')
 import tomllib
 ids=set()
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  names=set(z.namelist())
  if 'fabric.mod.json' in names:ids.add(json.loads(z.read('fabric.mod.json'))['id'])
  for n in ['META-INF/neoforge.mods.toml','META-INF/mods.toml']:
   if n in names:ids.update(m['modId'] for m in tomllib.loads(z.read(n).decode()).get('mods',[]))
  for n in names:
   if n.endswith('.jar'):ids.update(jar_mod_ids(z.read(n),depth+1))
 return ids

def allowed_change(before,after,patch):
 expected=dict(before,**patch)
 if after!=expected:raise ValueError('preset changed unrelated or wrong fields')
def fresh_feedback(text,expected):
 return any(re.search(r'\[CHAT\]\s*'+re.escape(expected)+r'\s*$',line) for line in text.splitlines())
def verify_exports(snapshot,receive,writes=None):
 if snapshot.get('receptionEnabled') is not receive:raise ValueError('fresh wrong reception')
 if writes is True and snapshot.get('xaeroAvailability')!='AVAILABLE':raise ValueError('explicit native Xaero enable not effective')
 if writes is False and snapshot.get('xaeroAvailability')!='DISABLED':raise ValueError('native Xaero write preference not disabled')


def verify_tool_identity(root, repo, active_files):
 """Bind staged entrypoints and the selected repository to the immutable run inputs."""
 from pathlib import Path
 root=Path(root).resolve();repo=Path(repo).resolve()
 read=lambda p:json.loads(p.read_text())
 digest=lambda value:hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',', ':')).encode()).hexdigest()
 sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
 manifest=read(root/'manifest.json');runtime=read(root/'runtime.json');bound=manifest['run_manifest']
 if digest(bound)!=manifest['run_hash'] or digest(runtime)!=bound['runtime_hash'] or bound['runtime_hash']!=manifest['runtime_hash']:raise ValueError('run input identity changed')
 staged={row['target']:row['sha256'] for row in bound['staged_inputs']}
 for name in ('drive.py','checks.py','verify.py','finalize.py'):
  target='preset-tools/'+name;expected=staged.get(target)
  if expected is None or sha(root/target)!=expected:raise ValueError('staged preset tool changed: '+name)
  active=Path(active_files.get(name, Path(active_files['entrypoint']).resolve().parent/name)).resolve()
  if sha(active)!=expected:raise ValueError('active preset tool differs: '+name)
 tools=bound['runtime_tools']
 for required in ('rig.py','native_window.py','private_input.py','ui_snapshot_wait.py'):
  if 'tools/rig/'+required not in tools:raise ValueError('required owned rig dependency unbound: '+required)
 for name,expected in tools.items():
  if not name.startswith('tools/'):continue
  active=(repo/name).resolve()
  if not active.is_relative_to(repo) or sha(active)!=expected:raise ValueError('selected repository tool differs: '+name)
 return repo

def verify_active_rig(repo, modules):
 """Reject imports resolved outside the explicitly selected and validated repository."""
 from pathlib import Path
 for name,module in modules.items():
  if Path(module.__file__).resolve()!=(Path(repo)/'tools/rig'/(name+'.py')).resolve():raise ValueError('active rig import differs: '+name)
