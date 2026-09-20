"""Recipe-only unit controls; no native or visual acceptance claims."""
import tempfile,unittest,json
from pathlib import Path
from prepare_xaero_map import build,PROFILES

class XaeroMapRecipeTest(unittest.TestCase):
 def test_all_four_profiles_pin_real_config_keys_and_owned_routes(self):
  with tempfile.TemporaryDirectory()as d:
   jar=Path(d)/'synthetic.jar';jar.write_bytes(b'SYNTHETIC TEST FILE')
   for name in PROFILES:
    # Recipe unit inputs are self-contained; sibling catalogs must not own
    # primary-line profiles. Actual dependency closures are native-rig gates.
    platform='fabric' if '-fabric-' in name else 'neoforge'
    xaero='ea84c45cb1b994a7738515670b281bcd373a9d9cc3d8d3a24b84a4baf302b9d6' if platform=='fabric' else 'd60899112e84616ba52a993c1722fd891872acc4779bfac34d9b170001451c6f'
    profile=dict(id=name,line='1.21.1',platform=platform,route='connector' if name=='mc1211-neo-modern' else 'native',artifacts=[dict(sha256=xaero)])
    runtime=dict(backend='isolated-linux-prism',client_endpoint='[::1]:25572',launches=[dict(id='server'),dict(id='client')],stage_files=[],generated_files={'instances/lss-rig-client/instance.cfg':'JvmArgs=-Dlss.rig.runId={run_id}\n','server/server.properties':'server-port=25572\n'})
    result=build(runtime,profile,jar,jar)
    wrong=dict(profile,route='native' if profile['route']=='connector' else 'connector')
    with self.assertRaises(ValueError):build(runtime,wrong,jar,jar)
    with self.assertRaises(ValueError):build(runtime,dict(profile,artifacts=[dict(sha256='0'*64)]),jar,jar)
    config=json.loads(result['generated_files']['server/config/lss-server-config.json'])
    self.assertIs(False,config['enableChunkGeneration']);self.assertNotIn('generationEnabled',config)
    self.assertEqual(32,config['lodDistanceChunks']);self.assertEqual({'server','client','map-controller'},{x['id']for x in result['launches']})
    self.assertIn('-Dlss.xaeromap.stop={run}/evidence/xaero-map-stop-client',result['generated_files']['instances/lss-rig-client/instance.cfg'])
    self.assertIn('tutorialStep:none',result['generated_files']['instances/lss-rig-client/minecraft/options.txt'])
    self.assertIn('simulationDistance:5',result['generated_files']['instances/lss-rig-client/minecraft/options.txt'])
    self.assertIn('-Dlss.xaeromap.pauseMaxMillis=15000',result['generated_files']['instances/lss-rig-client/instance.cfg'])
    self.assertEqual(2,len(result['stage_files']));self.assertEqual([],runtime['stage_files'])
    self.assertIn('-Dlss.xaeromap.arm={run}/evidence/xaero-map-arm-save',result['generated_files']['instances/lss-rig-client/instance.cfg'])
 def test_uninspected_profile_rejected(self):
  with self.assertRaises(ValueError):build({},dict(id='other'),Path('absent'),Path('absent'))
if __name__=='__main__':unittest.main()
