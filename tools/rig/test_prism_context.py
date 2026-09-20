import json,os,tempfile,unittest
from pathlib import Path
from launch_prism import prepare_context,uses_context,verify_launcher_inputs
from rig import identity,sha
class PrismContextTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.context=self.root/'auth';self.context.mkdir(mode=0o700);self.run=self.root/'run';self.run.mkdir();(self.context/'.lss-rig-context.json').write_text(json.dumps({'purpose':'dedicated-rig-launcher'}));self.account=self.context/'accounts.json';self.account.write_text('{}');self.account.chmod(0o600)
 def tearDown(self):self.tmp.cleanup()
 def test_context_aliases_refuse_forwarding(self):
  alias=self.root/'alias';alias.symlink_to(self.context,target_is_directory=True)
  for args in (['--dir','auth'],['--dir=auth'],['-dauth'],['-d',str(alias)]):
   self.assertTrue(uses_context(['prismlauncher',*args],self.root,self.context))
  self.assertFalse(uses_context(['prismlauncher','--dir','other'],self.root,self.context))
 def test_launcher_dependency_mutation_and_addition_rejected(self):
  directory=self.context/'libraries';directory.mkdir();p=directory/'pinned.jar';p.write_bytes(b'bytes')
  inputs={'files':{'libraries/pinned.jar':sha(p)}}
  verify_launcher_inputs(self.context,inputs)
  p.write_bytes(b'changed')
  with self.assertRaises(ValueError):verify_launcher_inputs(self.context,inputs)
  p.write_bytes(b'bytes');(directory/'extra.jar').write_bytes(b'extra')
  with self.assertRaises(ValueError):verify_launcher_inputs(self.context,inputs)
 def test_primary_unmarked_context_refused(self):
  (self.context/'.lss-rig-context.json').unlink()
  with self.assertRaises(ValueError):prepare_context(self.context,self.run,'/usr/bin/java')
  self.assertFalse((self.context/'prismlauncher.cfg').exists())
 def test_context_inside_run_refused(self):
  with self.assertRaises(ValueError):prepare_context(self.context,self.root,'/usr/bin/java')
 def test_live_owner_refused(self):
  (self.context/'.lss-active.json').write_text(json.dumps(identity(os.getpid())))
  with self.assertRaises(ValueError):prepare_context(self.context,self.run,'/usr/bin/java')
 def test_account_remains_outside_run(self):
  before=self.account.read_bytes();prepare_context(self.context,self.run,'/usr/bin/java')
  self.assertEqual(before,self.account.read_bytes());self.assertFalse(any(self.run.rglob('accounts.json')))
  self.assertIn(str(self.run/'instances'),(self.context/'prismlauncher.cfg').read_text())
if __name__=='__main__':unittest.main()
