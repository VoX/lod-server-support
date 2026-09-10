import json,tempfile,unittest
from pathlib import Path
from prepare_neo_captured import prepare
class NeoCapture(unittest.TestCase):
 def test_module_access_is_not_a_filesystem_path(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);capture=root/'capture';capture.mkdir();lib=root/'devlaunch.jar';lib.write_bytes(b'locked test library');candidate=root/'candidate.jar';candidate.write_bytes(b'candidate');vm=root/'vm.txt';vm.write_text('--add-opens\njava.base/java.util.jar=cpw.mods.securejarhandler\n')
   data=dict(route='actual-runServer-preparation-no-launch',task=':neoforge:runServer',mainClass='net.neoforged.devlaunch.Main',jvmArgs=['@'+str(vm),'-cp',str(lib)],args=[],classpath=[],minecraft='1.21.1',neoforge='21.1.248')
   (capture/'capture.json').write_text(json.dumps(data));result=prepare(capture,root/'source',candidate,'/owned/java',root/'out')
   self.assertIn('-cp',result['launch']);self.assertIn('java.base/java.util.jar=cpw.mods.securejarhandler',(root/'out/captured/0-vm.txt').read_text())
   self.assertEqual(result['launch'].count('-cp'),1)
 def test_missing_lazy_launch_classpath_fails(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);capture=root/'capture';capture.mkdir();candidate=root/'candidate.jar';candidate.write_bytes(b'candidate')
   (capture/'capture.json').write_text(json.dumps(dict(route='actual-runServer-preparation-no-launch',task=':neoforge:runServer',mainClass='net.neoforged.devlaunch.Main',jvmArgs=[],args=[],classpath=[],minecraft='1.21.1',neoforge='21.1.248')))
   with self.assertRaisesRegex(ValueError,'classpath provider missing'):prepare(capture,root/'source',candidate,'/owned/java',root/'out')
if __name__=='__main__':unittest.main()
