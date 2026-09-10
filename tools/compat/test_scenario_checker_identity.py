"""Acceptance fingerprints include common runtime dependencies and selected routes."""
import sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from scenario_checker_identity import closure

class ScenarioClosureTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  for name in ('tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
   p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('# retained wrapper\n')
  self.put('proof','from review_state import check_frozen\nfrom check_elytra_run import check_report\n')
  self.put('rig','from runtime_trees import verify\nfrom toolchain import retain\nfrom bind_endpoints import bindings\nfrom check_elytra_run import check_report\n')
  for module in ('review_state','runtime_trees','toolchain','bind_endpoints','check_elytra_run'):self.put(module,'value=1\n')
 def put(self,module,source):
  p=self.root/'tools/rig'/(module+'.py');p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
 def get(self,scenario=None):return closure(self.root,scenario or {'execution_route':'server-gametest'}, {})
 def test_common_verifier_changes_invalidate_identity(self):
  for module in ('runtime_trees','toolchain','bind_endpoints'):
   before=self.get();self.put(module,'value=2\n');after=self.get()
   self.assertIn('tools/rig/'+module+'.py',after);self.assertNotEqual(before,after)
 def test_new_common_import_is_followed_transitively(self):
  self.put('runtime_trees','from nested_guard import inspect\n');self.put('nested_guard','value=1\n')
  before=self.get();self.put('nested_guard','value=2\n');self.assertNotEqual(before,self.get())
 def test_unused_known_scenario_is_excluded(self):
  before=self.get();self.put('check_elytra_run','value=2\n');self.assertEqual(before,self.get())
 def test_selected_scenario_is_included(self):
  scenario={'checker':'elytra'};before=self.get(scenario);self.put('check_elytra_run','value=2\n');self.assertNotEqual(before,self.get(scenario))
 def test_unknown_common_import_cannot_be_treated_as_unused_branch(self):
  self.put('rig','from newly_added_guard import inspect\n');self.put('newly_added_guard','value=1\n')
  self.assertIn('tools/rig/newly_added_guard.py',self.get())
 def test_ownership_wrapper_changes_invalidate_identity(self):
  for name in ('tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
   before=self.get();(self.root/name).write_text('# changed wrapper\n');self.assertNotEqual(before,self.get())
 def test_import_cycles_terminate(self):
  self.put('runtime_trees','from rig import inside\n');self.assertIn('tools/rig/runtime_trees.py',self.get())
 def test_missing_root_rejected(self):
  (self.root/'tools/rig/proof.py').unlink()
  with self.assertRaisesRegex(ValueError,'missing scenario dependency'):self.get()
 def test_unknown_route_rejected(self):
  with self.assertRaisesRegex(ValueError,'undeclared'):self.get({'execution_route':'new-route'})

if __name__=='__main__':unittest.main()
