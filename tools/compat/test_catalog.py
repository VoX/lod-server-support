import copy, json, tempfile, unittest, zipfile
from pathlib import Path
from unittest.mock import patch
from catalog import *

class CatalogTests(unittest.TestCase):
    def profile(self):
        return {'schema_version':1,'id':'test','line':'1.21.1','platform':'fabric','route':'native','artifacts':[{'id':'test','version':'1','file':'test.jar','sha256':'a'*64,'source':'https://example.invalid/test.jar','metadata':{'fabric':{'depends':{'minecraft':'1.21.1'}}},'enabled':True}],'components':[{'uid':'net.minecraft','version':'1.21.1'}],'capabilities':['receive'],'status':'unverified','limitations':[]}
    def test_hash_binds_config_and_fixture(self):
        p=self.profile();q=copy.deepcopy(p);q['artifacts'][0]['sha256']='b'*64
        self.assertNotEqual(digest(p),digest(q))
    def test_wrong_mc_rejected(self):
        p=self.profile();p['artifacts'][0]['metadata']['fabric']['depends']['minecraft']='1.21.11'
        with self.assertRaises(Invalid):validate_profile(p)
    def test_native_neo_rejects_fabric(self):
        p=self.profile();p['platform']='neoforge'
        with self.assertRaises(Invalid):validate_profile(p)
    def test_missing_hash_rejected(self):
        p=self.profile();del p['artifacts'][0]['sha256']
        with self.assertRaises(Invalid):validate_profile(p)
    def test_unknown_schema_rejected(self):
        p=self.profile();p['schema_version']=2
        with self.assertRaises(Invalid):validate_profile(p)
    def test_range_dialects(self):
        self.assertTrue(accepts('1.21.1','>=1.21 <1.21.2'))
        self.assertTrue(accepts('1.21.1','~1.21 <1.21.2'))
        self.assertTrue(accepts('26.2','~26.2-'))
        self.assertTrue(accepts('1.21.1','>=1.21-'))
        self.assertFalse(accepts('1.21','<1.21-'))
        self.assertFalse(accepts('1.21.11',['1.21','1.21.1']))
        self.assertTrue(accepts('1.21.1','[1.21,1.21.2)','maven'))
        self.assertFalse(accepts('1.21.2','[1.21,1.21.2)','maven'))
        with self.assertRaises(Invalid):accepts('1.21.1','^1.21')
    def test_old_pass_does_not_replace_rejection(self):
        base={'profile_hash':'a','run_hash':'b','scenario':'s','feature':'f'}
        newer=base|{'timestamp':'2026-09-09T01:00:00Z','run_id':'new','result':'fail'}
        older=base|{'timestamp':'2026-09-08T01:00:00Z','run_id':'old','result':'pass'}
        self.assertEqual(latest([newer,older]),[newer])
    def test_inspection_hashes_bytes_not_filename(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'same.jar'
            with zipfile.ZipFile(p,'w') as z:z.writestr('fabric.mod.json','{"id":"a"}')
            first=inspect_jar(p)
            with zipfile.ZipFile(p,'w') as z:z.writestr('fabric.mod.json','{"id":"b"}')
            self.assertNotEqual(first['sha256'],inspect_jar(p)['sha256'])
    def test_generation_is_stable_and_detects_readme_drift(self):
        row={'facts':{'line':'1.21.1','targets':{'fabric':'1.21.1','paper':'1.21.1','neoforge':'1.21.1'},'java':21,'neoforge_shipping':True,'neoforge_renderer':'available','paper_loaders':['paper','purpur'],'fabric_client_gametests':False}}
        with tempfile.TemporaryDirectory() as d, patch('catalog.validate',return_value=([('a'*40,row)],{},[])), patch('catalog.snapshot_validation_records',return_value=[]):
            root=Path(d);(root/'docs').mkdir();readme=root/'README.md'
            readme.write_text('Historical note stays.\n\n| Minecraft | Fabric | Paper / Purpur | Folia | NeoForge |\n| --- | --- | --- | --- | --- |\n| old | yes | yes | yes | yes |\n')
            render(root);before=readme.read_bytes();render(root);self.assertEqual(before,readme.read_bytes());render(root,True)
            self.assertIn('Historical note stays.',readme.read_text())
            readme.write_text(readme.read_text().replace('shipped; best-effort','not shipped'))
            with self.assertRaises(Invalid):render(root,True)
    def test_canonical_order(self):self.assertEqual(digest({'a':1,'b':2}),digest({'b':2,'a':1}))

if __name__=='__main__':unittest.main()

class ValidationIdentityTests(unittest.TestCase):
    def test_attempt_evidence_cannot_split_reusable_input_identity(self):
        profile=CatalogTests().profile()
        inputs={key:'a'*64 for key in ('candidate_sha256','fixture_sha256','checker_sha256','world_sha256')}
        inputs.update(effective_config={},jvm_flags=[],backend='private')
        index={'proof.json':'b'*64}
        record={'schema_version':1,'run_id':'run','timestamp':'2026-09-09T20:00:00Z','profile_id':profile['id'],'profile_hash':digest(profile),'run_manifest':inputs,'run_hash':digest(inputs),'scenario':'ui','feature':'status','result':'pass','evidence_index':index,'evidence_sha256':digest(index),'limitations':[]}
        validate_record(record,{profile['id']:profile})
        altered=copy.deepcopy(record);altered['evidence_index']['proof.json']='c'*64
        with self.assertRaises(Invalid):validate_record(altered,{profile['id']:profile})
        altered=copy.deepcopy(record);altered['run_manifest']['evidence_index']=index;altered['run_hash']=digest(altered['run_manifest'])
        with self.assertRaises(Invalid):validate_record(altered,{profile['id']:profile})

class SnapshotEvidenceTests(unittest.TestCase):
    def test_referenced_evidence_uses_exact_commit_not_local_checkout(self):
        profile=CatalogTests().profile();commit='a'*40
        inputs={key:'b'*64 for key in ('candidate_sha256','fixture_sha256','checker_sha256','world_sha256')}
        inputs.update(effective_config={},jvm_flags=[],backend='private')
        base=dict(schema_version=1,profile_id='test',profile_hash=digest(profile),run_manifest=inputs,run_hash=digest(inputs),scenario='ui',feature='status',evidence_sha256='c'*64,limitations=[])
        old=base|dict(timestamp='2026-09-09T01:00:00Z',run_id='old',result='pass')
        new=base|dict(timestamp='2026-09-09T02:00:00Z',run_id='new',result='fail')
        files={'config/compatibility/profiles/test.json':profile,'config/compatibility/validation/old.json':old,'config/compatibility/validation/new.json':new}
        def at_ref(root,ref,path):
            self.assertEqual(commit,ref);return json.dumps(files[path])
        with patch('catalog.git',return_value='\n'.join(files)),patch('catalog.read_ref',side_effect=at_ref):
            result=snapshot_validation_records(Path('/unused'),[(commit,{'facts':{'line':'1.21.1'}})])
        self.assertEqual([('1.21.1',commit,new)],result)
    def test_impossible_timestamp_cannot_supersede_actual_attempt(self):
        profile=CatalogTests().profile();inputs={key:'a'*64 for key in ('candidate_sha256','fixture_sha256','checker_sha256','world_sha256')}
        inputs.update(effective_config={},jvm_flags=[],backend='private')
        record=dict(schema_version=1,run_id='bad',timestamp='2026-99-99T99:99:99Z',profile_id='test',profile_hash=digest(profile),run_manifest=inputs,run_hash=digest(inputs),scenario='ui',feature='status',result='pass',evidence_sha256='b'*64,limitations=[])
        with self.assertRaisesRegex(Invalid,'real UTC'):validate_record(record,{'test':profile})

class AttemptUniquenessTests(unittest.TestCase):
    def test_duplicate_attempt_cannot_choose_result_by_filename_order(self):
        base={'profile_hash':'a','run_hash':'b','scenario':'s','feature':'f','timestamp':'2026-09-09T01:00:00Z','run_id':'same'}
        for records in ([base|{'result':'pass'},base|{'result':'fail'}],[base|{'result':'fail'},base|{'result':'pass'}]):
            with self.assertRaisesRegex(Invalid,'duplicate validation attempt'):latest(records)
