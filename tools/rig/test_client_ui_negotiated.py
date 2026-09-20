"""Synthetic raw evidence controls, never native UI acceptance."""
import json,unittest
import test_client_ui as original
from check_client_ui import EXPORTS,SCREENS,check_report
from rig import write,sha

class NegotiatedUi(unittest.TestCase):
 bind=original.ClientUiNoConsumerTests.bind
 save=original.ClientUiNoConsumerTests.save
 check=original.ClientUiNoConsumerTests.check
 def setUp(self):
  original.ClientUiNoConsumerTests.setUp(self)
  self.scenario.update(id='ui-apply',checker='client-ui',execution_route='client-ui',requires_handshake=True)
  self.profile.update(platform='fabric',route='native')
  self.profile['components'][1]={'uid':'net.fabricmc.fabric-loader','version':'0.18.4'}
  self.proof['handshake']=True;self.bind()
  for name,enabled in EXPORTS.items():
   d=json.loads((self.e/name).read_text());d.update(negotiated=True,protocol=20,consumerAvailable=True,rendererAvailable=True,serverEnabled=True,discovery='NEGOTIATED');d['versions']['components']['LOADER']='0.18.4';self.save(name,d)
 def test_complete_negotiated_raw_sequence(self):self.assertEqual([],self.check())
 def test_generic_assertions_cannot_replace_missing_raw_evidence(self):
  for p in self.e.iterdir():p.unlink()
  self.proof['evidence']={};self.assertTrue(self.check())
 def test_old_seven_route_is_rechecked_without_allowing_four_assertions(self):
  self.scenario.pop('checker');self.scenario.pop('execution_route');self.bind();self.assertEqual([],self.check())
  self.scenario['assertions']=self.scenario['assertions'][:4];self.scenario['required_test_count']=4;self.bind();self.assertTrue(self.check())
 def test_wrong_connection_or_capability_cannot_pass(self):
  name='final-restored-export.json';old=json.loads((self.e/name).read_text())
  for key,value in [('connected',False),('consumerAvailable',False),('negotiated',False),('protocol',0),('protocol',True),('rendererAvailable',False),('serverEnabled',False),('receptionEnabled',False)]:
   with self.subTest(key=key):self.save(name,dict(old,**{key:value}));self.assertTrue(self.check())
  self.save(name,old);self.assertEqual([],self.check())
 def test_rebound_unrelated_config_change_fails(self):
  name='preserved-draft-applied-config.json';d=json.loads((self.e/name).read_text());self.save(name,dict(d,unrelated='changed'));self.assertTrue(self.check())
 def test_invalid_png_with_rebound_hash_fails(self):
  name=SCREENS[0];(self.e/name).write_bytes(b'\x89PNG\r\n\x1a\nnot an image');self.proof['evidence'][name]=sha(self.e/name);self.assertTrue(self.check())
 def test_new_route_requires_bound_operator_inspection(self):
  self.scenario['version']=2;self.bind();self.assertTrue(self.check())
  self.save('ui-actions.json',dict(run_hash=self.manifest['run_hash'],operator_visual_checked=True,screenshots={name:self.proof['evidence'][name]for name in SCREENS}));self.assertEqual([],self.check())
  self.save('ui-actions.json',dict(run_hash='other',operator_visual_checked=True,screenshots={name:self.proof['evidence'][name]for name in SCREENS}));self.assertTrue(self.check())
 def test_corrupt_or_truncated_png_cannot_be_rebound(self):
  name=SCREENS[0];original=(self.e/name).read_bytes()
  for data in [original+b'extra',original[:-1],original[:-5]+bytes([original[-5]^1])+original[-4:]]:
   with self.subTest(size=len(data)):
    (self.e/name).write_bytes(data);self.proof['evidence'][name]=sha(self.e/name);self.assertTrue(self.check())
 def test_server_or_other_scenario_cannot_claim_client_ui(self):
  self.scenario['id']='send-admission';self.bind();self.assertTrue(self.check())

if __name__=='__main__':unittest.main()
