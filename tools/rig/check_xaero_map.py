"""Strict raw native map gate; visual review remains an independent user action."""
import base64,hashlib,json
from pathlib import Path
TARGETS={(31,16),(32,16)}

def target_rgba(row):
 # Xaero 1.45 MapTileChunk.putColour: ((z * 64 + x) * 4).
 # Each group contains 4x4 chunks; preserve the full raw buffer as evidence.
 raw=base64.b64decode(row['buffer_base64'],validate=True)
 if len(raw)!=64*64*4:raise ValueError('unexpected native tile-group buffer size')
 cx,cz=row['chunk_x'],row['chunk_z']
 if row['tile_chunk_x']!=cx//4 or row['tile_chunk_z']!=cz//4:
  raise ValueError('native group does not contain target')
 x0,z0=(cx&3)*16,(cz&3)*16
 return b''.join(raw[((z0+z)*64+x0)*4:((z0+z)*64+x0+16)*4] for z in range(16))

def inspect(rows,oracle,run_id,allow_open=False):
 errors=[];passed={key:False for key in ('fresh_body_received','bridge_write','boundary_continuity','shading_valid','save_race_safe')}
 if not rows or any(r.get('run_id')!=run_id or r.get('overflow') is not False for r in rows):return passed,['missing/mismatched run identity or overflow']
 if not allow_open and (rows[-1].get('event')!='observer_closed' or rows[-1].get('pending')!=0):return passed,['native evidence writer did not close cleanly']
 if any(r.get('event')=='observer_failure' for r in rows):return passed,['native observer failed']
 if any(type(r.get('time_ns'))is not int for r in rows):return passed,['missing native monotonic event timestamp']
 if oracle.get('run_id')!=run_id or set(tuple(t['chunk'])for t in oracle.get('targets',[]))!=TARGETS:return passed,['independent exact boundary oracle missing']
 targets={tuple(t['chunk']):t for t in oracle['targets']}
 def at(r,p):return (r.get('chunk_x'),r.get('chunk_z'))==p
 def event(name):return [r for r in rows if r.get('event')==name]
 wire=event('wire_body');commits=[r for r in event('bridge_result')if r.get('outcome')=='COMMITTED']
 fresh={};bridge={};equal={}
 for p,t in targets.items():
  if len(t.get('initial_floor_y',[]))!=256 or len(t.get('final_floor_y',[]))!=256:return passed,['full independently seeded pixel heights missing']
  w=[r for r in wire if at(r,p) and r.get('body_bytes',0)>0 and r['time_ns']>t['seed_started_ns']]
  c=[r for r in commits if at(r,p) and r.get('native_chunk_loaded') is False and (r.get('client_chunk_x'),r.get('client_chunk_z'))==(16,16) and r.get('floor_y')==t['initial_floor_y'] and len(r.get('floor_state',[]))==256 and all(t['initial_block'] in state for state in r['floor_state']) and any(a['time_ns']<=r['time_ns']for a in w)]
  fresh[p]=bool(w);bridge[p]=bool(c)
  textures=[r for r in event('native_texture')if at(r,p)]
  valid=[]
  for r in textures:
   try:
    b=base64.b64decode(r['buffer_base64'],validate=True);target_rgba(r)
   except (ValueError,KeyError):continue
   if len(b)!=r.get('buffer_bytes') or hashlib.sha256(b).hexdigest()!=r.get('buffer_sha256') or len(set(b))<3:continue
   pixels=r.get('pixels',[])
   if len(pixels)!=256 or any(len(v)!=5 for v in pixels):continue
   if [v[0]for v in pixels]!=t['initial_floor_y']:continue
   if r.get('region_paused') is not False or r.get('region_load_state')!=2 or not r.get('last_visited',0)>0:continue
   valid.append(r)
  # A later native vanilla visit must independently reproduce the complete pixel
  # content/slopes AND native color buffer previously built from the bridge tile.
  equal[p]=any(a.get('native_writer') is False and a.get('observation')=='buffer_rebuild'
      and b.get('native_writer') is True
      and b.get('observation') in ('buffer_rebuild','unchanged_native_group')
      and any(at(scan,p) and scan.get('scan_ns')==b.get('native_scan_ns')
          and t['native_visit_started_ns']<=scan['time_ns']<=b['time_ns']
          and scan.get('pixels')==b['pixels']
          for scan in event('native_scan_completed'))
      and a['time_ns']<t['native_visit_started_ns']<=b['time_ns']
      and a['pixels']==b['pixels'] and target_rgba(a)==target_rgba(b)
      for a in valid for b in valid)
 passed['fresh_body_received']=all(fresh.values());passed['bridge_write']=all(bridge.values())
 passed['boundary_continuity']=all(bridge.values()) and all(equal.values())
 passed['shading_valid']=all(equal.values())
 starts=event('save_pause_begin');ends=event('native_save_return');releases=event('save_pause_release')
 if len(starts)==len(ends)==len(releases)==1:
  start,end,release=starts[0],ends[0],releases[0]
  pause=start.get('native_paused') is True and start.get('holds_pause_monitor') is False and end.get('success') is True and start['time_ns']<release['time_ns']<=end['time_ns']
  region=(start.get('region_x'),start.get('region_z'))
  matching=lambda r:(r.get('chunk_x',-999)//32,r.get('chunk_z',-999)//32)==region
  no_write=not any(matching(r) and start['time_ns']<=r['time_ns']<=end['time_ns']for r in commits)
  deferred=any(matching(r) and r.get('outcome')=='DEFERRED' and start['time_ns']<r['time_ns']<release['time_ns'] and r.get('native_save_active') is True for r in event('bridge_result'))
  recovery=any(matching(r) and t.get('edited_during_save') is True and at(r,p) and r['time_ns']>end['time_ns'] and r.get('floor_y')==t['final_floor_y'] and len(r.get('floor_state',[]))==256 and all(t['final_block']in v for v in r['floor_state']) for p,t in targets.items()for r in commits)
  passed['save_race_safe']=pause and no_write and deferred and recovery
 errors.extend('missing native evidence: '+key for key,value in passed.items()if not value)
 return passed,errors
