"""Exercise full rig proof dispatch against actual owned synthetic raw files."""
import importlib.util,sys,unittest
from pathlib import Path
import test_server_smoke_report as examples
class Dispatch(unittest.TestCase):
 def setUp(self):
  examples.ReportTests.setUp(self);self.addCleanup(self.temp.cleanup)
  self.manifest.update(run_hash='r'*64,scenario_hash='s'*64,profile_hash='p'*64)
  self.proof.update(self.manifest,ready=True,handshake=True,failures=[],evidence={self.path.name:self.proof['server_smoke_report']['artifact_sha256']})
  self.scenario.update(required_test_count=3,assertions=list(self.proof['assertions']))
  # Maintained test resolves sibling proof.py; staged control explicitly sets overlay.
  import os
  path=Path(os.environ.get('LSS_SMOKE_PROOF',str(Path(__file__).with_name('proof.py'))))
  spec=importlib.util.spec_from_file_location('smoke_proof_control',path);self.module=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.module)
 def test_full_native_dispatch(self):self.assertEqual([],self.module.check_proof(self.proof,self.manifest,self.scenario,self.root))
 def test_missing_raw_server_fails_full_dispatch(self):
  (self.e/'smoke-server.log').unlink();self.assertTrue(self.module.check_proof(self.proof,self.manifest,self.scenario,self.root))
 def test_wrong_target_fails_full_dispatch(self):
  self.scenario['target']={};self.assertTrue(self.module.check_proof(self.proof,self.manifest,self.scenario,self.root))
 def test_rootless_proof_rejected(self):self.assertTrue(self.module.check_proof(self.proof,self.manifest,self.scenario,None))
if __name__=='__main__':unittest.main()
