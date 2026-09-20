import json,unittest
import test_client_ui_negotiated as fixtures
from finalize_client_ui import finalize
from rig import write,sha
class FinalizeUi(unittest.TestCase):
 def setUp(self):
  self.fixture=fixtures.NegotiatedUi('test_complete_negotiated_raw_sequence');self.fixture.setUp();self.addCleanup(self.fixture.doCleanups);f=self.fixture
  f.scenario['version']=2;f.manifest['status']='running'
  self.game=f.root/'instances/lss-rig-client/minecraft';(self.game/'config').mkdir(parents=True);(self.game/'mods').mkdir()
  (self.game/'config/lss-client-config.json').write_bytes((f.e/'canonical-restored-baseline.json').read_bytes())
  candidate=self.game/'mods/lod-server-support-fabric.jar';candidate.write_bytes(b'SYNTHETIC CANDIDATE')
  f.runtime['stage_files']=[dict(target=str(candidate.relative_to(f.root)),sha256=sha(candidate))];f.bind();write(f.root/'manifest.json',f.manifest)
 def test_checked_complete_raw_sequence_writes_one_bound_proof(self):
  result=finalize(self.fixture.root,True);self.assertEqual('owned',result['run_id']);self.assertTrue((self.fixture.root/'proof.json').exists())
  with self.assertRaisesRegex(ValueError,'replace'):finalize(self.fixture.root,True)
 def test_operator_inspection_is_explicit(self):
  with self.assertRaisesRegex(ValueError,'inspection'):finalize(self.fixture.root,False)
 def test_leftover_actual_save_blocker_refuses_proof(self):
  (self.game/'config/lss-client-config.json.tmp').mkdir()
  with self.assertRaisesRegex(ValueError,'restored'):finalize(self.fixture.root,True)
 def test_changed_actual_candidate_refuses_proof(self):
  (self.game/'mods/lod-server-support-fabric.jar').write_bytes(b'OTHER')
  with self.assertRaisesRegex(ValueError,'candidate'):finalize(self.fixture.root,True)
if __name__=='__main__':unittest.main()
