import tempfile,unittest,json,hashlib,zipfile,copy
from pathlib import Path
from named_gametest_target import allowed
class NamedTarget(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
  self.scenario=dict(id='c2me-save-read',execution_route='server-gametest',requires_handshake=False,required_test_count=3)
  p=self.root/'tools/rig/scenarios/c2me-save-read.json';p.parent.mkdir(parents=True);p.write_text(json.dumps(self.scenario))
  self.profile=dict(id='mc12111-c2me-a-named-gametest',line='1.21.11',platform='fabric',mapping_namespace='named')
  self.jar=self.root/'fabric/build/devlibs/lod-server-support-fabric-dev.jar';self.jar.parent.mkdir(parents=True)
  with zipfile.ZipFile(self.jar,'w') as z:z.writestr('fabric.mod.json',json.dumps(dict(id='lss',depends={'fabricloader':'>=0.18.4','minecraft':'1.21.11'})))
  fixture=self.root/'tests.jar'
  with zipfile.ZipFile(fixture,'w') as z:z.writestr('fabric.mod.json',json.dumps(dict(id='lss-test',entrypoints={'fabric-gametest':['dev.vox.lss.test.SerializerParityGameTests']})))
  self.fixtures=['artifacts/lss-gametest-named.jar','artifacts/lss-dev-test-support.jar'];self.target='artifacts/lss-main-named.jar';self.runtime={'stage_files':[dict(target=self.target,source=str(self.jar),sha256=hashlib.sha256(self.jar.read_bytes()).hexdigest()),dict(target=self.fixtures[0],source=str(fixture))]}
 def check(self):return allowed(self.root,self.profile,self.scenario,self.runtime,self.target,self.fixtures)
 def test_exact_named_gate(self):self.assertEqual(1,len(self.check()))
 def test_dev_not_allowed_on_other_routes(self):
  for route in ('native-server-smoke','client-ui','release','runtime'):
   self.scenario['execution_route']=route;self.assertEqual(set(),self.check())
 def test_arbitrary_devlibs_name_rejected(self):
  p=self.jar.with_name('arbitrary.jar');p.write_bytes(self.jar.read_bytes());self.runtime['stage_files'][0]['source']=str(p)
  with self.assertRaises(ValueError):self.check()
 def test_changed_candidate_rejected(self):
  self.runtime['stage_files'][0]['sha256']='f'*64
  with self.assertRaises(ValueError):self.check()
 def test_missing_fixture_or_wrong_profile_rejected(self):
  self.fixtures=[]
  with self.assertRaises(ValueError):self.check()
  self.fixtures=['artifacts/lss-gametest-named.jar','artifacts/lss-dev-test-support.jar'];self.profile['platform']='neoforge'
  with self.assertRaises(ValueError):self.check()
 def test_changed_canonical_requirement_rejected(self):
  self.scenario['required_test_count']=1
  with self.assertRaises(ValueError):self.check()
if __name__=='__main__':unittest.main()
