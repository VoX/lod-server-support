"""Exact staged Python and maintained dependency identity contract controls."""
import hashlib,tempfile,unittest
from pathlib import Path
# Import the implementation from the selected test tree.
from scenario_checker_identity import closure
class StagedClosureContract(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
  for name in ('tools/rig/proof.py','tools/rig/rig.py','tools/rig/review_state.py','tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
   p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('# root\n')
  self.data={'preset-tools/drive.py':b'from native_window import find\n'}
  self.put('native_window','from guard import check\n');self.put('guard','value=1\n')
 def put(self,name,text):
  (self.root/'tools/rig'/str(name+'.py')).write_text(text)
 def runtime(self):
  return {'stage_files':[{'target':k,'source':'/external/'+k,'sha256':hashlib.sha256(v).hexdigest()}for k,v in self.data.items()]}
 def get(self,runtime=None,reader=None):
  return closure(self.root,{},runtime or self.runtime(),staged_reader=reader or self.data.__getitem__)
 def test_script_and_transitive_helper_bound(self):
  result=self.get();self.assertIn('staged/preset-tools/drive.py',result);self.assertIn('tools/rig/native_window.py',result);self.assertIn('tools/rig/guard.py',result)
 def test_changed_used_helper_changes_scope(self):
  before=self.get();self.put('guard','value=2\n');self.assertNotEqual(before,self.get())
 def test_unrelated_addition_does_not_change_scope(self):
  before=self.get();self.put('unrelated','value=2\n');self.assertEqual(before,self.get())
 def test_wrong_retained_bytes_rejected(self):
  with self.assertRaises(ValueError):self.get(reader=lambda target:b'# wrong\n')
 def test_reader_required(self):
  with self.assertRaises(ValueError):closure(self.root,{},self.runtime())
 def test_changed_explicit_script_changes_scope(self):
  before=self.get();self.data['preset-tools/drive.py']+=b'# explicit new source\n';self.assertNotEqual(before,self.get())
 def test_external_origin_is_never_opened_by_closure(self):
  # nonexistent /external path is deliberate: caller supplies authoritative retained bytes.
  self.assertIn('staged/preset-tools/drive.py',self.get())
 def test_duplicate_target_rejected(self):
  runtime=self.runtime();runtime['stage_files']*=2
  with self.assertRaises(ValueError):self.get(runtime)
 def test_unsafe_target_rejected(self):
  runtime=self.runtime();runtime['stage_files'][0]['target']='../escape.py'
  with self.assertRaises(ValueError):self.get(runtime)
 def test_explicit_staged_scenario_import_not_excluded(self):
  self.put('check_elytra_run','value=1\n');self.data['preset-tools/drive.py']=b'from check_elytra_run import check_report\n'
  self.assertIn('tools/rig/check_elytra_run.py',self.get())
if __name__=='__main__':unittest.main()
