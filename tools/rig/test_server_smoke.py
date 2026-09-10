import copy,hashlib,unittest
from check_server_smoke import check
from fixture_controller import Controller
class SmokeTests(unittest.TestCase):
 def setUp(self):
  target={'chunk_x':8,'chunk_z':8,'dimension':'minecraft:overworld','expected_blocks':{'128,64,128':'minecraft:diamond_block'}}
  rows=[]
  def add(event,**fields):rows.append(dict(event=event,run_id='owned',time_ns=len(rows)*10+10,**fields))
  add('world_fresh',world_preexisting=False);add('target_seeded',native_expected_blocks=target['expected_blocks']);add('target_unloaded',native_loaded=False);add('store_absent',present=False)
  add('handshake_first',protocol=20,server_observed=True,client_observed=True,connection_id='one')
  body=dict(chunk_x=8,chunk_z=8,dimension=target['dimension'],decoded_blocks=target['expected_blocks'],wire_association='exact',wire_capture_id=1,body_id=1,body_bytes=3,body_hex='616263',body_sha256=hashlib.sha256(b'abc').hexdigest(),column_timestamp=99,lease_active=True)
  add('body_first',connection_id='one',source=1,received_ns=59,**body)
  add('store_persisted',raw_body_sha256=hashlib.sha256(b'abc').hexdigest(),present=True,wire_format=20,uncompressed_bytes=3,column_timestamp=99)
  add('disconnect_first',connection_id='one');add('handshake_second',protocol=20,server_observed=True,client_observed=True,connection_id='two')
  add('body_second',connection_id='two',source=3,received_ns=99,**body);add('cleanup',complete=True,owned_processes_alive=0)
  self.e={'run_id':'owned','target':target,'events':rows}
 def test_two_real_sessions_disk_to_store(self):self.assertEqual('passed',check(self.e)['status'])
 def fail(self):self.assertEqual('failed',check(self.e)['status'])
 def test_mock_handshake_rejected(self):self.e['events'][4]['server_observed']=False;self.fail()
 def test_wrong_store_route_rejected(self):self.e['events'][9]['source']=1;self.fail()
 def test_cached_body_rejected(self):self.e['events'][9]['received_ns']=59;self.fail()
 def test_preexisting_row_rejected(self):self.e['events'][3]['present']=True;self.fail()
 def test_wrong_decoded_content_rejected(self):self.e['events'][9]['decoded_blocks']={};self.fail()
 def test_missing_persistent_write_rejected(self):self.e['events'][6]['present']=False;self.fail()
 def test_stale_session_rejected(self):self.e['events'][9]['connection_id']='one';self.fail()
 def test_controller_requires_native_premises(self):
  c=Controller(0);self.assertEqual('seeded',c.advance('fresh',1,True))
  with self.assertRaises(ValueError):c.advance('unloaded',2,True)
  with self.assertRaises(ValueError):c.advance('seeded',3,False)
if __name__=='__main__':unittest.main()
