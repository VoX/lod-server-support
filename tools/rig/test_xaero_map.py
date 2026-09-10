"""Synthetic algorithm controls only; never native map or user-review evidence."""
import unittest,copy,base64,hashlib
from check_xaero_map import inspect

def fixture():
 rows=[];oracle={'run_id':'SYNTHETIC','targets':[]};data=bytes(range(256))*64
 def event(kind,time,**values):rows.append(dict(event=kind,time_ns=time,run_id='SYNTHETIC',overflow=False,**values))
 for x in (31,32):
  oracle['targets'].append(dict(chunk=[x,16],initial_floor_y=[64]*256,final_floor_y=[64]*256,initial_block='gold_block',final_block='diamond_block'if x==31 else'gold_block',seed_started_ns=10,seed_ready_ns=16,native_visit_started_ns=40,edited_during_save=x==31))
  event('wire_body',15,chunk_x=x,chunk_z=16,body_bytes=1024)
  event('bridge_result',20,chunk_x=x,chunk_z=16,outcome='COMMITTED',native_chunk_loaded=False,client_chunk_x=16,client_chunk_z=16,floor_y=[64]*256,floor_state=['gold_block']*256)
  for native,time in [(False,30),(True,50)]:
   event('native_texture',time,chunk_x=x,chunk_z=16,native_writer=native,buffer_base64=base64.b64encode(data).decode(),buffer_bytes=len(data),buffer_sha256=hashlib.sha256(data).hexdigest(),pixels=[[64,64,0,0,15]]*256,region_paused=False,region_load_state=2,last_visited=1)
 event('save_pause_begin',60,region_x=0,region_z=0,native_paused=True,holds_pause_monitor=False)
 event('bridge_result',70,chunk_x=31,chunk_z=16,outcome='DEFERRED',native_save_active=True)
 event('save_pause_release',90,native_paused=True)
 event('native_save_return',100,success=True)
 event('bridge_result',110,chunk_x=31,chunk_z=16,outcome='COMMITTED',floor_y=[64]*256,floor_state=['diamond_block']*256)
 event('observer_closed',120,pending=0)
 return sorted(rows,key=lambda r:r['time_ns']),oracle

class XaeroMapTest(unittest.TestCase):
 def test_all_raw_requirements_in_synthetic_control(self):
  rows,oracle=fixture();flags,errors=inspect(rows,oracle,'SYNTHETIC');self.assertEqual([],errors);self.assertTrue(all(flags.values()))
 def test_missing_each_required_event_fails(self):
  rows,oracle=fixture()
  for kind in ('wire_body','bridge_result','native_texture','save_pause_begin','save_pause_release','native_save_return','observer_closed'):
   with self.subTest(kind=kind):self.assertTrue(inspect([r for r in rows if r['event']!=kind],oracle,'SYNTHETIC')[1])
 def test_actual_native_reference_and_buffer_integrity_required(self):
  original,oracle=fixture()
  for field,value in [('native_writer',False),('buffer_sha256','changed'),('pixels',[[65,65,0,0,15]]*256),('region_paused',True)]:
   rows=copy.deepcopy(original)
   for r in rows:
    if r['event']=='native_texture'and r['native_writer']:r[field]=value
   self.assertTrue(inspect(rows,oracle,'SYNTHETIC')[1],field)
 def test_only_saved_region_must_defer(self):
  rows,oracle=fixture();other=dict(rows[0],event='bridge_result',time_ns=80,chunk_x=32,chunk_z=16,outcome='COMMITTED');rows.insert(-1,other)
  self.assertEqual([],inspect(rows,oracle,'SYNTHETIC')[1]);other['chunk_x']=31
  self.assertFalse(inspect(rows,oracle,'SYNTHETIC')[0]['save_race_safe'])
 def test_open_stream_is_provisional_only(self):
  rows,oracle=fixture();rows.pop()
  self.assertTrue(inspect(rows,oracle,'SYNTHETIC')[1]);self.assertEqual([],inspect(rows,oracle,'SYNTHETIC',allow_open=True)[1])
 def test_same_column_alone_without_fresh_far_body_fails(self):
  for field,value in [('native_chunk_loaded',True),('client_chunk_x',31),('floor_state',['air']*256)]:
   rows,oracle=fixture()
   for r in rows:
    if r['event']=='bridge_result'and r['time_ns']==20:r[field]=value
   self.assertFalse(inspect(rows,oracle,'SYNTHETIC')[0]['bridge_write'],field)
 def test_bound_identity_and_writer_failures_fail(self):
  rows,oracle=fixture()
  self.assertTrue(inspect(rows,oracle,'other')[1]);rows[0]['overflow']=True
  self.assertTrue(inspect(rows,oracle,'SYNTHETIC')[1])
if __name__=='__main__':unittest.main()
