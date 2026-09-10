import hashlib,json,shutil,tempfile,unittest
from pathlib import Path
from prepare_native_client import prepare,sha
from runtime_trees import verify
class NativeClientAssetsTest(unittest.TestCase):
 def prepared(self,root):
  capture=root/'capture';capture.mkdir();assets=root/'cache';(assets/'indexes').mkdir(parents=True)
  content=b'actual synthetic asset';digest=hashlib.sha1(content).hexdigest();obj=assets/'objects'/digest[:2]/digest;obj.parent.mkdir(parents=True);obj.write_bytes(content)
  (assets/'indexes/fixture.json').write_text(json.dumps({'objects':{'one':{'hash':digest,'size':len(content)}}}))
  for name,value in [('target-components.json',[{'uid':k,'version':v} for k,v in [('net.minecraft','26.2'),('java','25'),('net.fabricmc.fabric-loader','0.19.3')]]),('runtime-classpath.json',[]),('original-mod-inputs.json',[])]:
   (capture/name).write_text(json.dumps(value))
  (capture/'remap-classpath.txt').write_text('');(capture/'dli-config.txt').write_text('clientArgs\n\t--assetIndex\n\tfixture\n\t--assetsDir\n\t'+str(assets)+'\n')
  candidate=root/'candidate.jar';candidate.write_bytes(b'synthetic candidate identity')
  runtime={'java':'/synthetic/java','candidate_artifacts':[{'sha256':sha(candidate)}],'stage_files':[],'generated_files':{},'launches':[]}
  result=json.loads(prepare(runtime,capture,root/'source',candidate,root/'output').read_text());run=root/'run';run.mkdir()
  for stage in result['stage_files']:
   target=run/stage['target'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(stage['source'],target)
  return result,run
 def test_producer_tree_is_accepted_and_matches_native_argument(self):
  with tempfile.TemporaryDirectory() as temp:
   runtime,run=self.prepared(Path(temp));verify(run,runtime['immutable_trees'])
   self.assertEqual({'assets'},set(runtime['immutable_trees']))
   argv=runtime['launches'][0]['argv'];self.assertEqual('{run}/assets',argv[argv.index('--assetsDir')+1])
   self.assertNotIn('smoke-assets',json.dumps(runtime))
 def test_same_exact_tree_still_rejects_mutated_asset(self):
  with tempfile.TemporaryDirectory() as temp:
   runtime,run=self.prepared(Path(temp));next((run/'assets/objects').rglob('*/*')).write_bytes(b'mutated')
   with self.assertRaisesRegex(ValueError,'dependency tree changed'):verify(run,runtime['immutable_trees'])
