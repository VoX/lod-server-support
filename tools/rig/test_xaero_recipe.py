import json,tempfile,unittest,sys
from pathlib import Path
from unittest.mock import patch
import rig
sys.path.insert(0,str(Path(rig.__file__).resolve().parents[1]/'compat'))
from prepare_xaero_recipe import prepare
from rig import write,read,sha,digest
class XaeroRecipeTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.recipe=self.root/'source';self.recipe.mkdir()
        self.profile={'artifacts':[{'file':'old-api.jar','metadata':{'fabric':{'id':'fabric-api'}}},{'file':'loader.jar','metadata':{'fabric':{'id':'fabricloader'}}}],'capabilities':['z','a']}
        self.runtime={'cache':{},'candidate_artifacts':[],'launches':[{'id':'client-'+s,'cwd':'client-'+s,'argv':['java','-cp','{run}/artifacts/old-api.jar:{run}/artifacts/loader.jar','Knot']} for s in 'ABCD'],'generated_files':{'client-A/config/lss-client-config.json':json.dumps({'lodDistanceChunks':48,'customFixtureSetting':'preserved','enableXaeroMapBridge':False})},'client_profiles':[{} for _ in 'ABCD']}
        self.lock={'artifacts':[]};self.cache={}
        for name in ('fabric-api','sodium','xaeroworldmap'):
            path=self.root/(name+'.jar');path.write_bytes(name.encode());checksum=sha(path);self.cache[checksum]=str(path)
            self.lock['artifacts'].append({'id':name,'file':path.name,'sha256':checksum})
        write(self.recipe/'profile.json',self.profile);write(self.recipe/'runtime.json',self.runtime);write(self.recipe/'scenario.json',{})
    def generate(self,name):
        out=self.root/name
        with patch('materialize.resolution',return_value={'ready':True}):prepare(self.recipe,self.lock,self.cache,out)
        return out
    def test_existing_settings_preserved_and_flags_explicit(self):
        out=self.generate('one');runtime=read(out/'runtime.json');config=json.loads(runtime['generated_files']['client-A/config/lss-client-config.json'])
        self.assertEqual(48,config['lodDistanceChunks']);self.assertEqual('preserved',config['customFixtureSetting']);self.assertTrue(config['enableXaeroMapBridge']);self.assertTrue(config['enableXaeroMapBackpressure'])
        cp=runtime['launches'][0]['argv'];self.assertNotIn('old-api.jar',cp[cp.index('-cp')+1]);self.assertIn('loader.jar',cp[cp.index('-cp')+1])
    def test_profile_identity_and_capability_order_deterministic(self):
        a=read(self.generate('one')/'profile.json');b=read(self.generate('two')/'profile.json')
        self.assertEqual(sorted(a['capabilities']),a['capabilities']);self.assertEqual(digest(a),digest(b))
    def test_nonobject_existing_config_rejected(self):
        self.runtime['generated_files']['client-A/config/lss-client-config.json']='[]';write(self.recipe/'runtime.json',self.runtime)
        with self.assertRaisesRegex(ValueError,'configuration'):self.generate('one')
