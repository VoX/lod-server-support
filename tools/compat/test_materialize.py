import copy,hashlib,io,json,tempfile,unittest,zipfile
from pathlib import Path
from materialize import resolution,nested_metadata,fetch
from catalog import inspect_jar,Invalid
class MaterializeTests(unittest.TestCase):
 def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
 def tearDown(self):self.tmp.cleanup()
 def jar(self,name,meta):
  p=self.root/name
  with zipfile.ZipFile(p,'w') as z:z.writestr('fabric.mod.json',json.dumps(meta))
  a=inspect_jar(p)|{'id':meta['id'],'version':meta['version'],'source':'https://example.invalid/'+name,'enabled':True}
  return p,a
 def profile(self,a):return {'schema_version':1,'id':'test','line':'1.21.1','platform':'fabric','route':'native','artifacts':[a],'components':[{'uid':'net.minecraft','version':'1.21.1'}],'capabilities':['receive'],'status':'unverified','limitations':[]}
 def nested(self,name,versions,constraints):
  p=self.root/name
  meta={'id':name.split('.')[0],'version':'1','jars':[{'file':str(i)+'.jar'} for i in range(len(versions))]}
  with zipfile.ZipFile(p,'w') as z:
   z.writestr('fabric.mod.json',json.dumps(meta))
   for i,version in enumerate(versions):
    body=io.BytesIO()
    with zipfile.ZipFile(body,'w') as n:n.writestr('fabric.mod.json',json.dumps({'id':'shared','version':version}))
    z.writestr(str(i)+'.jar',body.getvalue())
  a=inspect_jar(p)|{'id':meta['id'],'version':'1','source':'https://example.invalid/'+name,'enabled':True}
  profile=self.profile(a);cache={a['sha256']:str(p)}
  for i,constraint in enumerate(constraints):
   path,row=self.jar('dep'+str(i)+'.jar',{'id':'dep'+str(i),'version':'1','depends':{'shared':constraint}})
   profile['artifacts'].append(row);cache[row['sha256']]=str(path)
  return profile,cache
 def test_nested_duplicate_versions_choose_joint_candidate(self):
  profile,cache=self.nested('outer.jar',['1','2'],['>=1','>=2'])
  self.assertTrue(resolution(profile,cache)['ready'])
 def test_nested_versions_cannot_satisfy_incompatible_edges_separately(self):
  profile,cache=self.nested('outer.jar',['1','2'],['1','2'])
  self.assertFalse(resolution(profile,cache)['ready'])
 def test_duplicate_top_level_identity_rejected(self):
  p,a=self.jar('a.jar',{'id':'same','version':'1'});q,b=self.jar('b.jar',{'id':'same','version':'2'})
  b['id']='other-artifact';profile=self.profile(a);profile['artifacts'].append(b)
  self.assertIn('duplicate top-level',str(resolution(profile,{a['sha256']:str(p),b['sha256']:str(q)})['errors']))
 def test_fabric_breaks_is_not_ignored(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1','breaks':{'sodium':'<0.8'}});q,b=self.jar('b.jar',{'id':'sodium','version':'0.6'})
  profile=self.profile(a);profile['artifacts'].append(b)
  self.assertFalse(resolution(profile,{a['sha256']:str(p),b['sha256']:str(q)})['ready'])
 def test_native_multi_loader_jar_uses_native_descriptor(self):
  p=self.root/'dual.jar'
  with zipfile.ZipFile(p,'w') as z:
   z.writestr('fabric.mod.json',json.dumps({'id':'dual','version':'1','depends':{'fabricloader':'>=0.18'}}))
   z.writestr('META-INF/neoforge.mods.toml','[[mods]]\nmodId="dual"\nversion="1"\n')
  a=inspect_jar(p)|{'id':'dual','version':'1','source':'https://example.invalid/dual.jar','enabled':True}
  profile=self.profile(a);profile['platform']='neoforge'
  self.assertTrue(resolution(profile,{a['sha256']:str(p)})['ready'])
 def plugin(self,name,extra=''):
  path=self.root/(name+'.jar')
  with zipfile.ZipFile(path,'w') as z:z.writestr('plugin.yml','name: '+name+'\nversion: "1"\napi-version: "1.21.1"\n'+extra)
  row=inspect_jar(path)|{'id':name,'version':'1','source':'https://example.invalid/'+name,'enabled':True,'kind':'plugin'}
  return path,row
 def test_plugin_required_dependency_and_folia_opt_in(self):
  p,a=self.plugin('Fixture','depend: [LodServerSupport]\nfolia-supported: true\n');q,b=self.plugin('LodServerSupport','folia-supported: true\n')
  profile=self.profile(a);profile['platform']='folia'
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
  profile['artifacts'].append(b)
  self.assertTrue(resolution(profile,{a['sha256']:str(p),b['sha256']:str(q)})['ready'])
 def test_plugin_without_folia_opt_in_and_newer_api_rejected(self):
  p,a=self.plugin('Lss');profile=self.profile(a);profile['platform']='folia'
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
  profile['platform']='paper';profile['components'][0]['version']='1.20.1'
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_mod_mislabeled_library_rejected_even_if_metadata_omitted(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1'});a.update(kind='library',metadata={})
  self.assertFalse(resolution(self.profile(a),{a['sha256']:str(p)})['ready'])
 def test_unknown_kind_rejected(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1'});a['kind']='librarry'
  with self.assertRaises(Invalid):resolution(self.profile(a),{a['sha256']:str(p)})
 def test_missing_dependency(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1','depends':{'sodium':'>=0.8'}})
  r=resolution(self.profile(a),{a['sha256']:str(p)})
  self.assertFalse(r['ready']);self.assertIn('missing dependency sodium',str(r['errors']))
 def test_wrong_dependency_version(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1','depends':{'sodium':'>=0.8'}});q,b=self.jar('b.jar',{'id':'sodium','version':'0.6'})
  profile=self.profile(a);profile['artifacts'].append(b)
  self.assertFalse(resolution(profile,{a['sha256']:str(p),b['sha256']:str(q)})['ready'])
 def test_same_name_wrong_bytes(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1'});p.write_bytes(b'changed')
  self.assertIn('hash mismatch',str(resolution(self.profile(a),{a['sha256']:str(p)})['errors']))
 def test_missing_cache_no_network(self):
  _,a=self.jar('a.jar',{'id':'a','version':'1'})
  self.assertEqual(len(resolution(self.profile(a),{})['missing']),1)
 def test_unverified_resolved_is_ready_not_pass(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1','depends':{'minecraft':'1.21.1'}})
  self.assertTrue(resolution(self.profile(a),{a['sha256']:str(p)})['ready'])
 def test_blocked_never_ready(self):
  p,a=self.jar('a.jar',{'id':'a','version':'1'});profile=self.profile(a);profile.update(status='blocked',limitations=['awaiting source'])
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_fetch_rejects_unverified_existing_cache(self):
  _,a=self.jar('a.jar',{'id':'a','version':'1'});cache=self.root/'cache';cache.mkdir();(cache/a['sha256']).write_text('foreign')
  with self.assertRaises(Invalid):fetch(self.profile(a),cache)
if __name__=='__main__':unittest.main()
