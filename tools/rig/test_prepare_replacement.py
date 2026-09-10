import unittest,copy
from prepare_replacement import prepare
class ReplacementRecipeTest(unittest.TestCase):
    def base(self):return {'bind_endpoint':'127.0.0.1:1','client_endpoint':'127.0.0.1:1','stage_files':[{'target':'server/mods/lss-wi6-fixture.jar','source':'one','sha256':'one'},{'target':'instances/client/mods/lss-wi5-fixture.jar','source':'two','sha256':'two'}],
        'server_profile':{'id':'mc1211-fabric-server'},'launches':[{'id':'server','cwd':'server','argv':['java','-jar','fabric-server-launch.jar']},{'id':'client','cwd':'client','argv':['prism']}],
        'generated_files':{'instances/lss-rig-client/instance.cfg':'JvmArgs=-Dlss.rig.runId={run_id}\n','server/server.properties':'server-ip=a\nserver-port=1\nlevel-seed=old\nonline-mode=false\n','server/eula.txt':'eula=true\n'},'immutable_trees':{'server/libraries':{'a':'hash'}}}
    def parse(self,value):
        host,port=value.split(':');return host,int(port)
    def test_owned_distinct_servers_without_mutating_input(self):
        value=self.base();before=copy.deepcopy(value);out=prepare(value,'127.0.0.1:2',self.parse)
        self.assertEqual(before,value);self.assertEqual(['server','server-replacement','client','lifecycle-controller'],[row['id'] for row in out['launches']])
        self.assertIn('server-port=2',out['generated_files']['server-replacement/server.properties'])
        self.assertIn('server-port=1',out['generated_files']['server/server.properties'])
        self.assertEqual({'a':'hash'},out['immutable_trees']['server-replacement/libraries'])
        self.assertNotIn('-Dlss.rig.abruptClose=true',out['launches'][1]['argv'])
    def test_duplicate_listener_or_missing_fixture_fails(self):
        with self.assertRaises(ValueError):prepare(self.base(),'127.0.0.1:1',self.parse)
        value=self.base();value['stage_files']=[]
        with self.assertRaises(ValueError):prepare(value,'127.0.0.1:2',self.parse)
