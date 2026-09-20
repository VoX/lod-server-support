import tempfile,unittest,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from scenario_checker_identity import closure
class Scope(unittest.TestCase):
 def test_dependency_and_unrelated_changes(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);rig=root/'tools/rig';rig.mkdir(parents=True)
   for name in ('tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
    wrapper=root/name;wrapper.parent.mkdir(parents=True,exist_ok=True);wrapper.write_text('# owned wrapper')
   for name,body in {'proof.py':'# dispatcher','rig.py':'# runner','review_state.py':'# identity','check_server_smoke_report.py':'import used_helper','used_helper.py':'VALUE=1'}.items():(rig/name).write_text(body)
   scenario={'execution_route':'native-server-smoke'};before=closure(root,scenario,{})
   (rig/'unrelated_new.py').write_text('VALUE=99');self.assertEqual(before,closure(root,scenario,{}))
   (rig/'used_helper.py').write_text('VALUE=2');self.assertNotEqual(before,closure(root,scenario,{}))
 def test_unknown_branch_rejected(self):
  with self.assertRaises(ValueError):closure('/tmp',{'checker':'unknown-new-checker'},{})
 def test_both_explicit_native_ui_routes_bind_actual_checker(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);rig=root/'tools/rig';rig.mkdir(parents=True)
   for name in ('tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
    wrapper=root/name;wrapper.parent.mkdir(parents=True,exist_ok=True);wrapper.write_text('# owned wrapper')
   for name in ('proof.py','rig.py','review_state.py','check_client_ui.py','client_ui_steps.py','finalize_client_ui.py'):(rig/name).write_text('# native UI dependency')
   for route in ('client-ui','client-ui-no-consumer'):
    scenario={'execution_route':route,'checker':route}
    before=closure(root,scenario,{})
    self.assertIn('tools/rig/check_client_ui.py',before)
    self.assertIn('tools/rig/client_ui_steps.py',before)
    self.assertIn('tools/rig/finalize_client_ui.py',before)
    (rig/'check_client_ui.py').write_text('# changed used UI checker '+route)
    self.assertNotEqual(before,closure(root,scenario,{}))
if __name__=='__main__':unittest.main()
