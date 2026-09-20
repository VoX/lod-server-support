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

 def locked_zip(self,name,files,identity='outer'):
  path=self.root/name
  with zipfile.ZipFile(path,'w') as z:
   for entry_name,data in files.items():z.writestr(entry_name,data)
  row=inspect_jar(path)|{'id':identity,'version':'1','source':'https://example.invalid/'+name,'enabled':True}
  return path,row
 def test_native_neo_mod_cannot_make_fabric_or_plugin_profile_ready(self):
  p,a=self.locked_zip('neo.jar',{'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="outer"\nversion="1"\n'})
  for platform in ('fabric','paper','folia'):
   with self.subTest(platform=platform):
    profile=self.profile(a);profile['platform']=platform
    self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_fabric_dual_metadata_ignores_foreign_native_dependencies(self):
  p,a=self.locked_zip('dual.jar',{'fabric.mod.json':json.dumps({'id':'outer','version':'1'}),'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="outer"\nversion="1"\n[[dependencies.outer]]\nmodId="neo_only_dependency"\ntype="required"\nversionRange="[1,)"\n'})
  self.assertTrue(resolution(self.profile(a),{a['sha256']:str(p)})['ready'])
  profile=self.profile(a);profile['platform']='neoforge'
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_only_fabric_declared_nested_jars_can_provide_fabric_dependencies(self):
  body=io.BytesIO()
  with zipfile.ZipFile(body,'w') as z:z.writestr('fabric.mod.json',json.dumps({'id':'nested','version':'1'}))
  for fabric_declared in (False,True):
   with self.subTest(fabric_declared=fabric_declared):
    meta={'id':'outer','version':'1','depends':{'nested':'1'}}
    if fabric_declared:meta['jars']=[{'file':'META-INF/jarjar/nested.jar'}]
    p,a=self.locked_zip('nested-'+str(fabric_declared)+'.jar',{'fabric.mod.json':json.dumps(meta),'META-INF/jarjar/metadata.json':json.dumps({'jars':[{'path':'META-INF/jarjar/nested.jar'}]}),'META-INF/jarjar/nested.jar':body.getvalue()})
    self.assertEqual(fabric_declared,resolution(self.profile(a),{a['sha256']:str(p)})['ready'])
 def test_fabric_selects_its_descriptor_when_plugin_metadata_also_exists(self):
  p,a=self.locked_zip('dual-plugin.jar',{'fabric.mod.json':json.dumps({'id':'outer','version':'1'}),'plugin.yml':'name: outer\nversion: "1"\n'})
  self.assertTrue(resolution(self.profile(a),{a['sha256']:str(p)})['ready'])
 def test_native_neo_still_discovers_jarjar_dependencies(self):
  body=io.BytesIO()
  with zipfile.ZipFile(body,'w') as z:z.writestr('META-INF/neoforge.mods.toml','[[mods]]\nmodId="nested"\nversion="1"\n')
  p,a=self.locked_zip('neo-nested.jar',{'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="outer"\nversion="1"\n[[dependencies.outer]]\nmodId="nested"\ntype="required"\nversionRange="[1,)"\n','META-INF/jarjar/metadata.json':json.dumps({'jars':[{'path':'META-INF/jarjar/nested.jar'}]}),'META-INF/jarjar/nested.jar':body.getvalue()})
  profile=self.profile(a);profile['platform']='neoforge'
  self.assertTrue(resolution(profile,{a['sha256']:str(p)})['ready'])


 def native_container(self,nested_files,discovery='manifest',declared=True,outer_fabric=False):
  body=io.BytesIO()
  with zipfile.ZipFile(body,'w') as nested:
   for name,data in nested_files.items():nested.writestr(name,data)
  files={'META-INF/jarjar/metadata.json':json.dumps({'jars':[{'path':'META-INF/jarjar/mod.jar'}] if declared else []}),'META-INF/jarjar/mod.jar':body.getvalue()}
  if discovery=='manifest':files['META-INF/MANIFEST.MF']='Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\n'
  if discovery in ('service','missing-provider'):
   files['META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator']='example.NativeLocator # declared provider\n'
   if discovery=='service':files['example/NativeLocator.class']=b'fixture-class'
  if outer_fabric:files['fabric.mod.json']=json.dumps({'id':'outer','version':'1','jars':[{'file':'META-INF/jarjar/mod.jar'}]})
  return self.locked_zip('native-container.jar',files)
 def test_native_declared_container_provides_nested_mod(self):
  for discovery in ('manifest','service'):
   with self.subTest(discovery=discovery):
    p,a=self.native_container({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},discovery)
    profile=self.profile(a);profile['platform']='neoforge'
    self.assertTrue(resolution(profile,{a['sha256']:str(p)})['ready'])
    for wrong in ('fabric','paper','folia'):
     profile['platform']=wrong
     self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_native_container_requires_native_mod_and_discovery(self):
  cases=[({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},'none',True,False),
         ({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},'missing-provider',True,False),
         ({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},'manifest',False,False),
         ({'META-INF/neoforge.mods.toml':'modLoader="javafml"\n'},'manifest',True,False),
         ({'fabric.mod.json':json.dumps({'id':'nested','version':'1'})},'manifest',True,False),
         ({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},'manifest',True,True)]
  for files,discovery,declared,fabric in cases:
   with self.subTest(files=list(files),discovery=discovery,declared=declared,fabric=fabric):
    p,a=self.native_container(files,discovery,declared,fabric)
    profile=self.profile(a);profile['platform']='neoforge'
    if fabric:
     with self.assertRaises(Invalid):resolution(profile,{a['sha256']:str(p)})
    else:self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_fabric_declared_grandchild_cannot_establish_native_container(self):
  child=io.BytesIO()
  with zipfile.ZipFile(child,'w') as z:z.writestr('META-INF/neoforge.mods.toml','[[mods]]\nmodId="nested"\nversion="1"\n')
  p,a=self.native_container({'fabric.mod.json':json.dumps({'id':'fabric_wrapper','version':'1','jars':[{'file':'child.jar'}]}),'child.jar':child.getvalue()})
  profile=self.profile(a);profile['platform']='neoforge'
  self.assertFalse(resolution(profile,{a['sha256']:str(p)})['ready'])
 def test_service_container_retains_connector_and_bundled_fabric_loader_identity(self):
  p,a=self.native_container({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="connector"\nversion="1"\n'},'service')
  with zipfile.ZipFile(p,'a') as z:
   z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nFabric-Loader-Version: 0.18.4\n')
   z.writestr('net/fabricmc/loader/impl/FabricLoaderImpl.class',b'fixture-class')
  a=inspect_jar(p)|{k:a[k] for k in ('id','version','source','enabled')}
  q,b=self.jar('fabric-dependent.jar',{'id':'dependent','version':'1','depends':{'connector':'1','fabricloader':'>=0.18'}})
  profile=self.profile(a);profile.update(platform='neoforge',route='connector');profile['artifacts'].append(b)
  self.assertTrue(resolution(profile,{a['sha256']:str(p),b['sha256']:str(q)})['ready'])


 def test_native_container_discovery_uses_only_main_manifest_attributes(self):
  for ending in ('\n','\r\n','\r'):
   for kind in ('LIBRARY','GAMELIBRARY'):
    for main in (True,False):
     with self.subTest(ending=repr(ending),kind=kind,main=main):
      p,a=self.native_container({'META-INF/neoforge.mods.toml':'[[mods]]\nmodId="nested"\nversion="1"\n'},'none')
      lines=['Manifest-Version: 1.0']
      if main:lines.append('FMLModType: '+kind)
      lines+=['','Name: unrelated-resource.bin']
      if not main:lines.append('FMLModType: '+kind)
      with zipfile.ZipFile(p,'a') as z:z.writestr('META-INF/MANIFEST.MF',ending.join(lines+['','']))
      a=inspect_jar(p)|{k:a[k] for k in ('id','version','source','enabled')}
      profile=self.profile(a);profile['platform']='neoforge'
      self.assertEqual(main,resolution(profile,{a['sha256']:str(p)})['ready'])

if __name__=='__main__':unittest.main()
