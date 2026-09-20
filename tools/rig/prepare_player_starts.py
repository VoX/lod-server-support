"""Add exact native saved fixture identities to an unchanged accepted source snapshot."""
import gzip,hashlib,json,pathlib,shutil,sqlite3,struct,uuid
from rig import read,write,digest,sha,regular,alive
from world_snapshot import stages
from mca_fixture import nbt,LIMIT

def saved(path):
 with gzip.open(regular(path),'rb') as stream:
  body=stream.read(LIMIT+1)
 if len(body)>LIMIT:raise ValueError('player NBT bound')
 return nbt(body)

def validate_player(row,name,index,world_uuid):
 expected=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
 if row.get('UUID')!=expected.bytes:raise ValueError('native offline identity mismatch')
 if row.get('Pos')!=[index*4096+.5,-60.,.5] or row.get('Dimension')!='minecraft:overworld':raise ValueError('native starting position mismatch')
 if row.get('DataVersion')!=4903 or row.get('Health',0)<=0 or row.get('DeathTime')!=0:raise ValueError('native alive/version premise absent')
 if row.get('WorldUUIDMost')!=struct.unpack('>q',world_uuid[:8])[0] or row.get('WorldUUIDLeast')!=struct.unpack('>q',world_uuid[8:])[0]:raise ValueError('native saved world identity mismatch')
 return expected

def store_facts(snapshot):
 database=regular(snapshot/'world/lss-lod/store.db')
 with sqlite3.connect('file:'+str(database)+'?mode=ro&immutable=1',uri=True) as connection:
  dims=connection.execute("select id from dims where name='minecraft:overworld'").fetchall()
  if len(dims)!=1 or type(dims[0][0]) is not int:raise ValueError('native store dimension absent')
  table='lods_'+str(dims[0][0]);facts=[]
  for i,name in enumerate('ABCD'):
   for source,x,z in [(1,i*256+8,-8),(2,i*256-20,-20),(3,i*256-8,8)]:
    pos=(x<<32)|(z&0xffffffff);pos=(pos+(1<<63))%(1<<64)-(1<<63)
    present=connection.execute('select 1 from '+table+' where pos=?',(pos,)).fetchone() is not None
    if present!=(source==3):raise ValueError('initial store lane contamination')
    facts.append({'subject':'RigSubject'+name,'source':source,'chunk_x':x,'chunk_z':z,'store_present':present})
 return facts

def prepare(snapshot,run,destination):
 snapshot=pathlib.Path(snapshot).resolve();run=pathlib.Path(run).resolve();destination=pathlib.Path(destination).resolve()
 if destination.exists() or snapshot in destination.parents or run in destination.parents:raise ValueError('new independent destination required')
 old_digest,_=stages(snapshot);base=read(snapshot/'snapshot.json');manifest=read(run/'manifest.json');result=read(run/'evidence/result.json')
 if result.get('run_hash')!=manifest['run_hash'] or result.get('cleanup')!='complete':raise ValueError('native player source cleanup absent')
 if alive(read(run/'supervisor.json')) or any(alive(p) for p in read(run/'processes.json')):raise ValueError('native player source still live')
 if read(run/'runtime.json').get('world_digest')!=old_digest:raise ValueError('native player source used different world')
 metadata='world/dimensions/minecraft/overworld/data/paper/metadata.dat';world_uuid=saved(snapshot/metadata)['data']['uuid']
 if saved(run/'server'/metadata)['data']['uuid']!=world_uuid:raise ValueError('run world identity changed')
 facts=store_facts(snapshot);players=[]
 for i,suffix in enumerate('ABCD'):
  name='RigSubject'+suffix;expected=uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
  relative='world/players/data/'+str(expected)+'.dat';source=regular(run/'server'/relative)
  validate_player(saved(source),name,i,world_uuid)
  if (snapshot/relative).exists():raise ValueError('source snapshot already has fixture player state')
  players.append({'file':relative,'sha256':sha(source),'subject':name,'source':str(source)})
 shutil.copytree(snapshot,destination)
 entries=list(base['files'])
 for player in players:
  target=destination/player['file'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(player['source'],target)
  if sha(target)!=player['sha256']:raise ValueError('native player bytes changed')
  entries.append({k:player[k] for k in ('file','sha256')})
 entries.sort(key=lambda row:row['file'])
 record=dict(base,files=entries,world_digest=digest(entries),construction={'kind':'native-saved-player-starts','parent_world_digest':old_digest,'native_player_run_hash':manifest['run_hash'],'native_player_run_status':manifest['status'],'scope':'starting-position bytes only; no source or delivery acceptance inferred','players':[{k:v for k,v in p.items() if k!='source'} for p in players],'initial_store_facts':facts})
 write(destination/'snapshot.json',record);stages(destination)
 return record

if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('snapshot');p.add_argument('run');p.add_argument('destination');a=p.parse_args();print(json.dumps(prepare(a.snapshot,a.run,a.destination),indent=2))
