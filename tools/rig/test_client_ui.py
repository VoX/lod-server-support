import json,tempfile,unittest
from pathlib import Path
from rig import write,digest,sha
from proof import check_proof
from check_client_ui import ASSERTIONS,EXPORTS,SCREENS

class ClientUiNoConsumerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.e=self.root/'evidence';self.e.mkdir()
        self.scenario={'id':'client-ui-no-consumer','execution_route':'client-ui-no-consumer','checker':'client-ui-no-consumer','requires_handshake':False,'required_test_count':7,'assertions':list(ASSERTIONS)}
        self.profile={'platform':'neoforge','route':'native','line':'1.21.10','components':[{'uid':'net.minecraft','version':'1.21.10'},{'uid':'net.neoforged','version':'21.10.64'}]}
        self.runtime={'launches':[{'id':'server'},{'id':'client'}]}
        self.manifest={'run_id':'owned','run_hash':'run','backend':'isolated-linux-prism','started_at':100,'finished_at':110}
        self.proof={'run_id':'owned','run_hash':'run','ready':True,'handshake':False,'test_count':7,'assertions':dict.fromkeys(ASSERTIONS,True),'evidence':{}}
        self.bind()
        for name,enabled in EXPORTS.items():self.save(name,{'schemaVersion':1,'connected':True,'negotiated':False,'protocol':0,'consumerAvailable':False,'rendererAvailable':False,'serverEnabled':False,'discovery':'DORMANT','receptionEnabled':enabled,'receivedColumns':0,'receivedBytes':0,'capturedAtMillis':105000,'versions':{'components':{'MINECRAFT':'1.21.10','LOADER':'21.10.64'}}})
        baseline={'receiveServerLods':True,'enableJoinSlowStart':True,'unrelated':'preserved'}
        for name in ['canonical-restored-baseline.json','save-failure-before.json','save-failure-after.json','final-restored-config.json']:self.save(name,baseline)
        self.save('parent-return-before-apply-config.json',dict(baseline,receiveServerLods=False))
        self.save('preserved-draft-applied-config.json',dict(baseline,receiveServerLods=False,enableJoinSlowStart=False))
        for name in SCREENS:
            import struct,zlib
            def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
            header=struct.pack('>IIBBBBB',640,360,8,2,0,0,0)
            pixels=(b'\x00'+b'\x20'*(640*3))*360
            (self.e/name).write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b''));self.proof['evidence'][name]=sha(self.e/name)
    def bind(self):
        for name,value in [('scenario',self.scenario),('profile',self.profile),('runtime',self.runtime)]:
            write(self.root/(name+'.json'),value);self.manifest[name+'_hash']=digest(value)
        self.proof.update({key:self.manifest[key] for key in ['profile_hash','scenario_hash']})
    def save(self,name,data):write(self.e/name,data);self.proof['evidence'][name]=sha(self.e/name)
    def check(self):return check_proof(self.proof,self.manifest,self.scenario,self.root)
    def test_exact_connected_native_ui_evidence_passes_without_claiming_handshake(self):self.assertEqual([],self.check())
    def test_no_server_lifecycle_or_delivery_scenario_can_reuse_exemption(self):
        for name in ['c2me-save-read','receive-lifecycle','concurrent-sources','source-seed','ui-apply']:
            with self.subTest(name=name):self.scenario['id']=name;self.bind();self.assertTrue(self.check())
    def test_server_only_runtime_wrong_loader_and_stale_bindings_fail(self):
        self.runtime['launches'].pop();self.bind();self.assertTrue(self.check())
        self.runtime['launches'].append({'id':'client'});self.profile['platform']='fabric';self.bind();self.assertTrue(self.check())
        self.profile['platform']='neoforge';self.bind();self.manifest['runtime_hash']='other';self.assertTrue(self.check())
    def test_consumer_negotiation_disconnection_renderer_and_wrong_versions_fail(self):
        name='final-restored-export.json';original=json.loads((self.e/name).read_text())
        for key,value in [('consumerAvailable',True),('negotiated',True),('connected',False),('rendererAvailable',True),('protocol',20),('protocol',False),('discovery','AWAITING_NEGOTIATION'),('receptionEnabled',False),('versions',{'components':{'MINECRAFT':'other','LOADER':'21.10.64'}})]:
            with self.subTest(key=key,value=value):self.save(name,dict(original,**{key:value}));self.assertTrue(self.check())
        self.save(name,original);self.assertEqual([],self.check())
    def test_changed_missing_and_outside_run_exports_fail(self):
        name='final-restored-export.json';original=json.loads((self.e/name).read_text())
        (self.e/name).write_text('{}');self.assertTrue(self.check())
        for stamp in [99999,110001]:self.save(name,dict(original,capturedAtMillis=stamp));self.assertTrue(self.check())
        (self.e/name).unlink();self.assertTrue(self.check())
    def test_lost_draft_failed_save_bytes_and_incomplete_ui_cannot_pass(self):
        name='preserved-draft-applied-config.json';data=json.loads((self.e/name).read_text());self.save(name,dict(data,enableJoinSlowStart=True));self.assertTrue(self.check())
        self.save(name,data);self.save('save-failure-after.json',{'receiveServerLods':False});self.assertTrue(self.check())
        self.save('save-failure-after.json',json.loads((self.e/'save-failure-before.json').read_text()));self.proof['assertions']['escape_parent_preserved']=False;self.assertTrue(self.check())
    def test_handshake_claim_or_reduced_count_and_assertions_are_rejected(self):
        self.proof['handshake']=True;self.assertTrue(self.check());self.proof['handshake']=False
        self.proof['test_count']=6;self.assertTrue(self.check());self.proof['test_count']=7
        self.scenario['assertions'].pop();self.bind();self.assertTrue(self.check())
