import os,subprocess,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import rig

class PrivateDisplayContextTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
  self.record={'display':':998','host_display':':0','xvfb':rig.identity(os.getpid())}
  rig.write(self.root/'display.json',self.record);rig.write(self.root/'owner.json',rig.identity(os.getpid()))
 def test_controller_inherits_verified_private_display(self):
  with patch.dict(os.environ,{'DISPLAY':':998'}),patch('rig.verify_private_xvfb') as verify:
   self.assertEqual(':998',rig.guard_display(self.root)['DISPLAY']);verify.assert_called_once()
 def test_original_host_display_remains_forbidden_inside_child(self):
  self.record['host_display']=':998';rig.write(self.root/'display.json',self.record)
  with patch.dict(os.environ,{'DISPLAY':':7'}),self.assertRaisesRegex(ValueError,'desktop'):rig.guard_display(self.root)
 def test_absent_original_host_identity_rejected(self):
  self.record.pop('host_display');rig.write(self.root/'display.json',self.record)
  with self.assertRaisesRegex(ValueError,'desktop'):rig.guard_display(self.root)
 def test_actual_private_server_arguments_required(self):
  good=['Xvfb',':998','-screen','0','960x540x24','-nolisten','tcp','-auth',str(self.root/'Xauthority')]
  rig.validate_private_xvfb_args(self.root,self.record,'/usr/bin/Xvfb',good)
  for argv in (good[:-2],good+['-auth','/foreign'],[s.replace('tcp','unix') for s in good],[s.replace(':998',':0') for s in good],[s.replace(str(self.root/'Xauthority'),'/foreign') for s in good]):
   with self.assertRaises(ValueError):rig.validate_private_xvfb_args(self.root,self.record,'/usr/bin/Xvfb',argv)
  with self.assertRaises(ValueError):rig.validate_private_xvfb_args(self.root,self.record,'/usr/bin/Xorg',good)
 def test_current_process_cannot_pose_as_xvfb(self):
  (self.root/'Xauthority').touch(mode=0o600)
  with self.assertRaisesRegex(ValueError,'not native Xvfb'):rig.verify_private_xvfb(self.root,self.record)
 def exited(self,process):
  process.wait();return process
 def test_private_display_rejects_an_exited_owned_xvfb_even_when_the_probe_answers(self):
  # A foreign X server on the chosen number answers xprop while the owned Xvfb already exited.
  probe=patch('rig.subprocess.run',return_value=subprocess.CompletedProcess([],0));real_popen=subprocess.Popen
  with probe,patch('rig.subprocess.Popen',side_effect=lambda *a,**k:self.exited(real_popen(['true']))),patch('launch_journal.before_spawn'),patch('launch_journal.spawn_failed'),patch('launch_journal.spawned',return_value=self.record['xvfb']):
   with self.assertRaisesRegex(ValueError,'exited during startup'):rig.private_display(self.root,{},[])
 def test_private_display_requires_a_verified_native_xvfb_before_adoption(self):
  probe=patch('rig.subprocess.run',return_value=subprocess.CompletedProcess([],0))
  keeper=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']);self.addCleanup(lambda:(keeper.kill(),keeper.wait()))
  with probe,patch('rig.subprocess.Popen',return_value=keeper),patch('launch_journal.before_spawn'),patch('launch_journal.spawn_failed'),patch('launch_journal.spawned',return_value=rig.identity(keeper.pid)):
   with self.assertRaisesRegex(ValueError,'not native Xvfb'):rig.private_display(self.root,{},[])
  self.assertEqual(self.record,rig.read(self.root/'display.json'))

if __name__=='__main__':unittest.main()
