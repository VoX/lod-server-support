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
    def test_canonical_order(self):self.assertEqual(digest({'a':1,'b':2}),digest({'b':2,'a':1}))
    def test_inspect_reads_sponge_plugin_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'sponge.jar'
            with zipfile.ZipFile(p,'w') as z:z.writestr('META-INF/sponge_plugins.json','{"plugins":[{"id":"lodserversupport"}]}')
            self.assertEqual('lodserversupport',inspect_jar(p)['metadata']['sponge']['plugins'][0]['id'])

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

class TimestampTests(unittest.TestCase):
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


class RoleTests(unittest.TestCase):
    """A line's role is written in line.json and must agree with the files it carries, so a lost
    sync.json can never turn a follower into an unchecked line."""

    LINES = {'schema_version': 2, 'reference': '26.3', 'must_match': ['tools/compat/'],
             'lines': {'26.3': {'tier': 'full', 'branch': 'main'}, '1.21.1': {'tier': 'full', 'branch': 'support/mc1.21.1'}}}

    def tree(self, role, tier, files):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        root = Path(self.scratch.name)
        (root / 'config/lines').mkdir(parents=True)
        for name, value in files.items():
            (root / 'config/lines' / name).write_text(json.dumps(value))
        return root, {'role': role, 'support_tier': tier, 'facts': {'line': '26.3'}}

    def test_each_role_with_its_own_file_is_valid(self):
        self.assertEqual(validate_role(*self.tree('reference', 'full', {'lines.json': self.LINES})), 'reference')
        self.assertEqual(validate_role(*self.tree('follower', 'maintained', {'sync.json': {}})), 'follower')
        self.assertEqual(validate_role(*self.tree('frozen', 'frozen', {})), 'frozen')

    def test_a_follower_that_lost_its_sync_file_is_not_quietly_frozen(self):
        with self.assertRaisesRegex(Invalid, 'every follower needs it'):
            validate_role(*self.tree('follower', 'maintained', {}))

    def test_the_line_table_cannot_land_on_a_follower_and_main_carries_no_sync_file(self):
        with self.assertRaisesRegex(Invalid, 'reference line only'):
            validate_role(*self.tree('follower', 'full', {'sync.json': {}, 'lines.json': self.LINES}))
        with self.assertRaisesRegex(Invalid, 'follower lines'):
            validate_role(*self.tree('reference', 'full', {'lines.json': self.LINES, 'sync.json': {}}))

    def test_a_role_is_required_and_frozen_is_said_twice_or_not_at_all(self):
        with self.assertRaisesRegex(Invalid, 'needs "role"'):
            validate_role(*self.tree(None, 'full', {}))
        with self.assertRaisesRegex(Invalid, 'disagree'):
            validate_role(*self.tree('frozen', 'maintained', {}))

    def test_the_line_table_is_checked(self):
        for broken in (dict(self.LINES, reference='26.2'), dict(self.LINES, must_match=['tools/compat']),
                       dict(self.LINES, lines={'26.3': {'tier': 'full', 'branch': 'support/x'}}),
                       dict(self.LINES, lines={'26.3': {'tier': 'full', 'branch': 'main'}, '26.2': {'tier': 'best', 'branch': 'b'}})):
            with self.assertRaises(Invalid):
                validate_role(*self.tree('reference', 'full', {'lines.json': broken}))
