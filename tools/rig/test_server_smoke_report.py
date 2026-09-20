import hashlib,json,tempfile,unittest
from pathlib import Path
import test_server_smoke as examples
from check_server_smoke_report import check_report
class ReportTests(unittest.TestCase):
 def setUp(self):
  base=examples.SmokeTests();base.setUp();self.value=base.e;self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.e=self.root/'evidence';self.e.mkdir()
  self.path=self.e/'native-server-smoke.json';self.path.write_text(json.dumps(self.value))
  self.proof=dict(server_smoke_report=dict(artifact=self.path.name,artifact_sha256=hashlib.sha256(self.path.read_bytes()).hexdigest()),test_count=3,assertions=dict(store_write=True,store_read=True,body_matches_expected=True));self.manifest={'run_id':'owned'};self.scenario={'execution_route':'native-server-smoke','requires_handshake':True,'target':self.value['target']}
  (self.e/'smoke-server.log').write_text('Done (1s)!\n'+'LSS handshake received from RigSubjectA (protocol v20, capabilities=1)\n'*2)
  for phase in ('first','second'):
   body=next(r for r in self.value['events'] if r['event']=='body_'+phase)
   capture=dict(body,event='wire_capture',arrival_ns=body['received_ns'])
   rows=[dict(run_id='owned',event='client_handshake',protocol=20,connection_id=body['connection_id']),capture,body,dict(run_id='owned',event='client_closed',overflow=False)]
   (self.e/('client-'+phase+'.jsonl')).write_text(''.join(json.dumps(r)+'\n' for r in rows))
 def tearDown(self):self.temp.cleanup()
 def test_report_recomputed_from_native_capture_rows(self):self.assertEqual([],check_report(self.proof,self.manifest,self.scenario,self.root))
 def test_different_recipe_target_rejected(self):self.scenario['target']={};self.assertTrue(check_report(self.proof,self.manifest,self.scenario,self.root))
 def test_changed_report_rejected(self):self.path.write_text('{}');self.assertIn('native server smoke report hash mismatch',check_report(self.proof,self.manifest,self.scenario,self.root))
 def test_server_handshake_cannot_be_boolean_only(self):(self.e/'smoke-server.log').write_text('Done (1s)!');self.assertTrue(check_report(self.proof,self.manifest,self.scenario,self.root))
 def test_native_wire_substitution_rejected(self):
  path=self.e/'client-second.jsonl';rows=[json.loads(r) for r in path.read_text().splitlines()];rows[1]['body_hex']='00';path.write_text(''.join(json.dumps(r)+'\n' for r in rows));self.assertTrue(check_report(self.proof,self.manifest,self.scenario,self.root))
 def test_no_handshake_exemption(self):self.scenario['requires_handshake']=False;self.assertTrue(check_report(self.proof,self.manifest,self.scenario,self.root))
if __name__=='__main__':unittest.main()
