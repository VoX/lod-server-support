import json,tempfile,unittest,zipfile
from pathlib import Path
from prepare_neo_bootstrap import prepare,sha
from prepare_server_smoke import prepare as smoke
class PreparationTests(unittest.TestCase):
 def test_native_installer_identity_and_no_implicit_java(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);jar=root/'installer.jar'
   with zipfile.ZipFile(jar,'w') as z:
    z.writestr('install_profile.json',json.dumps(dict(minecraft='1.21.1',version='neoforge-21.1.248')));z.writestr('version.json','{}')
   result=prepare(jar,sha(jar),'21.1.248','1.21.1','/nonexistent/java',root/'out')
   self.assertEqual('prepared-command-only',result['status'])
   with self.assertRaises(ValueError):prepare(jar,sha(jar),'21.1.248','1.21.11','/java',root/'wrong')
   with self.assertRaises(ValueError):prepare(jar,'0'*64,'21.1.248','1.21.1','/java',root/'hash')
 def test_non_owned_store_and_missing_native_profile_rejected(self):
  with self.assertRaises(ValueError):smoke({},'x','/missing.jar','/tmp/uncreated-smoke-output','paper','../other/store.db')
  with self.assertRaises(ValueError):smoke({},'x','/missing.jar','/tmp/uncreated-smoke-output','paper','server/world/lss-lod/store.db')
if __name__=='__main__':unittest.main()
