"""Future-run ownership journal. In-flight spawn gaps never prove cleanup."""
from pathlib import Path

def valid_identity(value):
 return (isinstance(value,dict) and set(value)=={'pid','start','boot'} and type(value['pid']) is int and value['pid']>0
         and isinstance(value['start'],str) and value['start'].isdigit() and isinstance(value['boot'],str) and bool(value['boot']))

def roles(runtime):
 result=['display'] if runtime.get('backend')=='isolated-linux-prism' or (runtime.get('backend')=='linux-headless' and runtime.get('private_display')) else []
 result += ['launch:'+row['id'] for row in runtime['launches']]
 if len(set(result))!=len(result):raise ValueError('duplicate planned launch role')
 return result

def initialize(root,manifest,runtime,owner,supervisor):
 from rig import write
 if not valid_identity(owner) or not valid_identity(supervisor):raise ValueError('missing initial owner identity')
 if (root/'launch-journal.json').exists():raise ValueError('launch journal already exists')
 value={'schema_version':1,'run_id':manifest['run_id'],'run_hash':manifest['run_hash'],'runtime_hash':manifest['runtime_hash'],
        'owner':owner,'supervisor':supervisor,'terminal':False,'entries':[{'role':role,'state':'not-attempted'} for role in roles(runtime)]}
 write(root/'processes.json',[]);write(root/'launch-journal.json',value)
 return value

def before_spawn(root,role):
 from rig import read,write
 j=read(root/'launch-journal.json')
 if j['terminal']:raise ValueError('terminal launch journal')
 index=next(i for i,e in enumerate(j['entries']) if e['state']=='not-attempted')
 if j['entries'][index]['role']!=role:raise ValueError('out of order launch')
 j['entries'][index]['state']='spawning';write(root/'launch-journal.json',j)

def spawned(root,role,process):
 from rig import identity,read,write
 owner=identity(process.pid)
 if not valid_identity(owner):raise ValueError('spawned process identity unavailable')
 j=read(root/'launch-journal.json');entry=next(e for e in j['entries'] if e['role']==role)
 if entry['state']!='spawning':raise ValueError('spawn not declared')
 entry.update(state='spawned',identity=owner)
 # Any interruption between these atomic writes is intentionally unverifiable.
 write(root/'processes.json',[e['identity'] for e in j['entries'] if e['state']=='spawned'])
 write(root/'launch-journal.json',j)
 return owner

def spawn_failed(root,role):
 from rig import read,write
 j=read(root/'launch-journal.json');entry=next(e for e in j['entries'] if e['role']==role)
 if entry['state']!='spawning':raise ValueError('spawn failure without intent')
 entry['state']='spawn-failed';write(root/'launch-journal.json',j)

def terminal(root,status):
 from rig import read,write
 j=read(root/'launch-journal.json');j.update(terminal=True,status=status)
 write(root/'launch-journal.json',j)

def validate(root,runtime,require_receipt=True):
 from rig import read,regular,digest,sha,alive
 root=Path(root);m=read(regular(root/'manifest.json'));j=read(regular(root/'launch-journal.json'))
 if m.get('launch_journal_version')!=1:raise ValueError('future-run launch protocol marker missing')
 if j.get('schema_version')!=1 or j.get('terminal') is not True:raise ValueError('nonterminal launch journal')
 if m.get('run_hash')!=digest(m.get('run_manifest',{})) or digest(runtime)!=m.get('runtime_hash'):raise ValueError('launch input binding changed')
 for key in ('run_id','run_hash','runtime_hash'):
  if j.get(key)!=m.get(key):raise ValueError('foreign launch journal')
 for name in ('owner','supervisor'):
  if not valid_identity(j.get(name)) or read(regular(root/(name+'.json')))!=j[name]:raise ValueError('launch owner binding changed')
 entries=j.get('entries')
 if not isinstance(entries,list) or [e.get('role') for e in entries if isinstance(e,dict)]!=roles(runtime):raise ValueError('launch plan shortened/changed')
 suffix=False;failed=False;identities=[]
 for e in entries:
  state=e.get('state')
  if state=='spawned':
   if suffix or failed or set(e)!={'role','state','identity'} or not valid_identity(e.get('identity')):raise ValueError('invalid launch identity/order')
   identities.append(e['identity'])
  elif state in ('not-attempted','spawn-failed'):
   if set(e)!={'role','state'} or (state=='spawn-failed' and (suffix or failed)):raise ValueError('invalid unlaunched suffix')
   suffix=True;failed=failed or state=='spawn-failed'
  else:raise ValueError('unresolved spawn intent')
 if suffix and (j.get('status')!='failed' or m.get('status')!='failed'):raise ValueError('partial launch cannot pass')
 if len({(x['pid'],x['start'],x['boot']) for x in identities+[j['owner'],j['supervisor']]})!=len(identities)+2:raise ValueError('duplicate launch identity')
 if read(regular(root/'processes.json'))!=identities:raise ValueError('owned process list differs from spawn snapshots')
 if any(alive(x) for x in identities+[j['owner']]):raise ValueError('owned process remains live')
 if require_receipt:
  receipt=read(regular(root/'supervisor-cleanup.json'))
  expected={'schema_version':1,'run_id':j['run_id'],'run_hash':j['run_hash'],'runtime_hash':j['runtime_hash'],
            'supervisor':j['supervisor'],'owner':j['owner'],'journal_sha256':sha(root/'launch-journal.json'),
            'processes_sha256':sha(root/'processes.json'),'complete':True,'remaining_children':0}
  if receipt!=expected or alive(j['supervisor']):raise ValueError('supervisor cleanup receipt invalid/live')
 return j

def supervisor_complete(root,controller):
 """Called only by the outer subreaper after its child set becomes empty."""
 import os
 from rig import read,identity,write,sha
 root=Path(root);j=validate(root,read(root/'runtime.json'),require_receipt=False)
 if j['supervisor']!=identity(os.getpid()) or j['owner']!=controller:raise ValueError('foreign completing supervisor/controller')
 if Path(f'/proc/{os.getpid()}/task/{os.getpid()}/children').read_text().strip():raise ValueError('supervisor still owns children')
 write(root/'supervisor-cleanup.json',{'schema_version':1,'run_id':j['run_id'],'run_hash':j['run_hash'],'runtime_hash':j['runtime_hash'],
       'supervisor':j['supervisor'],'owner':j['owner'],'journal_sha256':sha(root/'launch-journal.json'),
       'processes_sha256':sha(root/'processes.json'),'complete':True,'remaining_children':0})
