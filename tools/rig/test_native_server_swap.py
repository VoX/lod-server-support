import copy,hashlib,json,tempfile,unittest,zipfile,os
from pathlib import Path
from swap_native_neo_server import swap,sha
SOURCE=Path(os.environ.get('LSS_SMOKE_SOURCE',str(Path(__file__).resolve().parents[2])))
class NativeSwap(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.closure=self.root/'closure';self.closure.mkdir();(self.closure/'libraries').mkdir();(self.closure/'libraries/engine.jar').write_bytes(b'exact installed library')
  installer=self.root/'installer.jar';installer.write_bytes(b'installer');self.installer=self.root/'installer.json';self.installer.write_text(json.dumps(dict(path=str(installer),sha256=sha(installer),url='https://example.invalid/exact-installer')))
  props=dict(x.split('=',1) for x in (SOURCE/'gradle.properties').read_text().splitlines() if '='in x and not x.startswith('#'))
  self.identity=dict(status='native-installed-closure',minecraft=props['minecraft_version'],neoforge=props['neoforge_version'],installer_sha256=sha(installer),files={'libraries/engine.jar':sha(self.closure/'libraries/engine.jar')},launch=['/owned/java','-Xmx2G','@unix_args.txt','nogui']);(self.closure/'closure.json').write_text(json.dumps(self.identity))
  self.jar=self.root/'candidate.jar'
  with zipfile.ZipFile(self.jar,'w') as z:z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="lss"\nversion="0.14.0"\n')
  self.runtime=dict(cache={},stage_files=[dict(target='server/mods/old-fabric.jar'),dict(target='subject/mods/exact-client.jar')],generated_files={'server/server.properties':'server-port=25576\n','server/config/lss-server-config.json':'{}'},immutable_trees={'server/libraries':{'old.jar':'old'},'subject/assets':{'a':'hash'}},server_profile={'id':'old-fabric-server'},client_profiles=[{'role':'subject','profile_hash':'original'}],launches=[dict(id='server',argv=['/java','-Dlss.wi6.enabled=true','-Dlss.rig.serverRoot={run}/server','-jar','fabric-server-launch.jar'],cwd='server'),dict(id='subject',argv=['native-client'],cwd='subject')])
 def call(self):return swap(self.runtime,SOURCE,self.closure,self.installer,self.jar,[],self.root/'output')
 def test_only_server_changes_and_fixture_properties_survive(self):
  original=copy.deepcopy(self.runtime);d=json.loads(self.call().read_text());self.assertEqual(original,self.runtime);self.assertEqual(d['client_profiles'],original['client_profiles']);self.assertEqual(d['launches'][1],original['launches'][1]);self.assertEqual(d['generated_files'],original['generated_files']);self.assertIn('-Dlss.wi6.enabled=true',d['launches'][0]['argv']);self.assertNotIn('fabric-server-launch.jar',d['launches'][0]['argv']);self.assertFalse(any('old-fabric' in x['target'] for x in d['stage_files']))
 def test_changed_installed_library_rejected(self):
  (self.closure/'libraries/engine.jar').write_bytes(b'changed')
  with self.assertRaisesRegex(ValueError,'closure changed'):self.call()
 def test_wrong_native_version_rejected(self):
  self.identity['neoforge']='0.0.0';(self.closure/'closure.json').write_text(json.dumps(self.identity))
  with self.assertRaisesRegex(ValueError,'exact production'):self.call()
 def test_fabric_fixture_cannot_substitute_native(self):
  with zipfile.ZipFile(self.jar,'w') as z:z.writestr('fabric.mod.json',json.dumps(dict(schemaVersion=1,id='lss',version='0.14.0')))
  with self.assertRaisesRegex(ValueError,'native Neo metadata'):self.call()
if __name__=='__main__':unittest.main()
