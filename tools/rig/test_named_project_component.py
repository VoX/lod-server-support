import unittest,tempfile,hashlib,json,zipfile,copy,sys
from pathlib import Path
from named_project_component import component,no_project_vendor_artifacts
class Component(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);(self.root/'gradle.properties').write_text('mod_version=0.14.0\n')
  self.origin=self.root/'common/build/libs/common-0.14.0.jar';self.origin.parent.mkdir(parents=True)
  with zipfile.ZipFile(self.origin,'w') as z:z.writestr('dev/vox/lss/common/Brand.class',b'synthetic class identity only')
  self.snapshot=self.root/'snapshot.jar';self.snapshot.write_bytes(self.origin.read_bytes());self.value=hashlib.sha256(self.origin.read_bytes()).hexdigest();self.target='artifacts/lss-common-named.jar';self.ref=dict(origin=str(self.origin),target=self.target,sha256=self.value);self.rows={self.target:dict(source=str(self.snapshot),sha256=self.value)}
 def check(self):return component(self.root,self.ref,self.rows,'common')
 def test_exact_current_origin_and_snapshot(self):self.assertEqual(self.value,self.check())
 def test_stale_origin_rejected(self):
  self.origin.write_bytes(b'changed')
  with self.assertRaises(ValueError):self.check()
 def test_same_filename_wrong_bytes_rejected(self):
  self.snapshot.write_bytes(b'wrong')
  with self.assertRaises(ValueError):self.check()
 def test_arbitrary_origin_rejected(self):
  p=self.root/'foreign.jar';p.write_bytes(self.origin.read_bytes());self.ref['origin']=str(p)
  with self.assertRaises(ValueError):self.check()
 def test_project_code_cannot_remain_vendor_dependency(self):
  with self.assertRaises(ValueError):no_project_vendor_artifacts({'artifacts':[{'id':'runtime-003','sha256':self.value}]},{self.value})
  no_project_vendor_artifacts({'artifacts':[{'id':'actual-vendor','sha256':'f'*64}]},{self.value})
  with self.assertRaises(ValueError):no_project_vendor_artifacts({'artifacts':[{'id':'runtime-003','sha256':self.value}]},{'a'*64},{self.value:str(self.snapshot)})
 def test_project_common_is_an_independent_candidate_role(self):
  from candidate_identity import candidate_bindings
  main='a'*64;runtime={'candidate_artifacts':[{'sha256':main,'metadata':{'fabric':{'id':'lss'}}}], 'stage_files':[{'target':'artifacts/lss-main-named.jar','sha256':main},{'target':self.target,'sha256':self.value}], 'named_project_components':{'main':{'target':'artifacts/lss-main-named.jar','sha256':main},'common':self.ref}}
  values=candidate_bindings(runtime,lambda target: main if target=='artifacts/lss-main-named.jar' else self.value)
  self.assertEqual({self.target:self.value,'artifacts/lss-main-named.jar':main},values)
  runtime['named_project_components']['common']['target']='arbitrary/library.jar'
  with self.assertRaises(ValueError):candidate_bindings(runtime,lambda target:self.value)
 def test_versioned_complete_named_gate_binds_both_origins(self):
  import test_named_gametest_target as examples
  from named_gametest_target import allowed
  example=examples.NamedTarget();example.setUp();self.addCleanup(example.doCleanups)
  (example.root/'gradle.properties').write_text('mod_version=0.14.0\n')
  common=example.root/'common/build/libs/common-0.14.0.jar';common.parent.mkdir(parents=True);common.write_bytes(self.origin.read_bytes())
  common_copy=example.root/'common-snapshot.jar';common_copy.write_bytes(common.read_bytes())
  example.profile['id']+='-v2';example.profile['artifacts']=[]
  main_hash=hashlib.sha256(example.jar.read_bytes()).hexdigest()
  example.runtime['named_project_components']={'main':{'origin':str(example.jar),'target':example.target,'sha256':main_hash},'common':{'origin':str(common),'target':self.target,'sha256':self.value}}
  example.runtime['stage_files'].append({'source':str(common_copy),'target':self.target,'sha256':self.value})
  self.assertEqual({main_hash,self.value},allowed(example.root,example.profile,example.scenario,example.runtime,example.target,example.fixtures))
if __name__=='__main__':unittest.main()
