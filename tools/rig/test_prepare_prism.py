import json,tempfile,unittest,zipfile
from pathlib import Path
from prepare_prism import build,verify_bootstrap
from rig import sha
class PreparePrismTests(unittest.TestCase):
 def test_client_fixture_and_separate_server_candidate_are_bound(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);server=root/'server';server.mkdir();
   with zipfile.ZipFile(server/'fabric-server-launch.jar','w') as z:z.writestr('install.properties','game-version=1.21.1\nfabric-loader-version=0.19.3')
   context=root/'context';(context/'libraries').mkdir(parents=True);(context/'libraries/lib.jar').write_bytes(b'library')
   (context/'meta/net.minecraft').mkdir(parents=True);(context/'meta/net.minecraft/1.21.1.json').write_text('{}')
   def jar(name,mid):
    path=root/name
    with zipfile.ZipFile(path,'w') as z:z.writestr('fabric.mod.json',json.dumps({'id':mid,'version':'1'}))
    return path
   candidate=jar('candidate.jar','lss');fixture=jar('fixture.jar','fixture')
   profile={'schema_version':1,'id':'client','line':'1.21.1','platform':'fabric','route':'native','artifacts':[],'components':[{'uid':'net.minecraft','version':'1.21.1'},{'uid':'java','version':'21'}],'capabilities':[],'status':'unverified','limitations':[]}
   server_profile=dict(profile,id='server',components=profile['components']+[{'uid':'net.fabricmc.fabric-loader','version':'0.19.3'}]);profile_path=root/'server-profile.json';profile_path.write_text(json.dumps(server_profile))
   result=build(profile,{},None,server,candidate,context,'/usr/bin/java','prism','[::1]:25572',[fixture],server_profile=server_profile,server_profile_path=profile_path)
   self.assertEqual([a['id'] for a in result['candidate_artifacts']],['lss','fixture'])
   self.assertEqual(result['server_profile']['candidate_artifacts'][0]['sha256'],sha(candidate))
   self.assertIn('libraries/lib.jar',result['launcher_inputs']['files'])
   self.assertTrue(any(row['target'].endswith('/mods/fixture.jar') for row in result['stage_files']))
 def test_paper_server_uses_plugin_metadata_cache_and_data_folder(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);server=root/'server';server.mkdir();
   with zipfile.ZipFile(server/'paper.jar','w') as z:z.writestr('version.json',json.dumps({'id':'1.21.1','java_version':21}))
   (server/'cache').mkdir();(server/'cache/mojang.jar').write_bytes(b'mojang-cache')
   context=root/'context';(context/'libraries').mkdir(parents=True);(context/'libraries/lib.jar').write_bytes(b'library')
   (context/'meta/net.minecraft').mkdir(parents=True);(context/'meta/net.minecraft/1.21.1.json').write_text('{}')
   candidate=root/'client.jar';plugin=root/'plugin.jar'
   with zipfile.ZipFile(candidate,'w') as z:z.writestr('fabric.mod.json',json.dumps({'id':'lss','version':'1'}))
   with zipfile.ZipFile(plugin,'w') as z:z.writestr('plugin.yml','name: LodServerSupport\nversion: "1"\napi-version: "1.21"\n')
   profile={'schema_version':1,'id':'client','line':'1.21.1','platform':'fabric','route':'native','artifacts':[],'components':[{'uid':'net.minecraft','version':'1.21.1'},{'uid':'java','version':'21'}],'capabilities':[],'status':'unverified','limitations':[]}
   server_profile=dict(profile,id='paper-server',platform='paper');profile_path=root/'server-profile.json';profile_path.write_text(json.dumps(server_profile))
   result=build(profile,{},None,server,candidate,context,'/usr/bin/java','prism','[::1]:25572',server_candidate=plugin,server_profile=server_profile,server_profile_path=profile_path)
   self.assertEqual(result['server_profile']['candidate_artifacts'][0]['kind'],'plugin')
   targets={row['target'] for row in result['stage_files']}
   self.assertIn('server/plugins/lod-server-support-paper.jar',targets)
   self.assertIn('server/cache/mojang.jar',targets)
   self.assertEqual(result['immutable_trees']['server/cache'],{'mojang.jar':sha(server/'cache/mojang.jar')})
   self.assertEqual(result['immutable_trees']['server/libraries'],{})
   self.assertIn('server/plugins/LodServerSupport/lss-server-config.json',result['generated_files'])
   self.assertIn('paper.jar',result['launches'][0]['argv'])
   with self.assertRaisesRegex(ValueError,'does not match'):
    build(profile,{},None,server,candidate,context,'/usr/bin/java','prism','[::1]:25572',server_candidate=candidate,server_profile=server_profile,server_profile_path=profile_path)
 def test_bootstrap_cannot_silently_replace_locked_loader(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'launcher.jar'
   with zipfile.ZipFile(path,'w') as z:z.writestr('install.properties','game-version=1.21.1\nfabric-loader-version=0.19.3')
   profile={'platform':'fabric','components':[{'uid':'net.minecraft','version':'1.21.1'},{'uid':'net.fabricmc.fabric-loader','version':'0.18.4'}]}
   with self.assertRaisesRegex(ValueError,'locked Minecraft/loader'):
    verify_bootstrap(path,profile)
if __name__=='__main__':unittest.main()
