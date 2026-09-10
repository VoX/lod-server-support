import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from export_validation import export
from rig import write,sha
from catalog import digest

class ExportTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        (self.root/'evidence').mkdir();(self.root/'mods').mkdir();(self.root/'mods/candidate.jar').write_bytes(b'controlled candidate')
        (self.root/'evidence/observation.json').write_text('{}')
        profile={'id':'controlled','line':'1.21.1'}
        runtime={'launches':[{'id':'client','argv':['java','-Xmx1G']}],'generated_files':{}}
        artifact_hash=sha(self.root/'mods/candidate.jar')
        runtime.update(candidate_artifacts=[{'sha256':artifact_hash,'metadata':{'fabric':{'id':'lss'}}}],stage_files=[{'source':str(self.root/'mods/candidate.jar'),'target':'mods/candidate.jar','sha256':artifact_hash}])
        tools={}
        for name in ('tools/rig/proof.py','tools/rig/rig.py','tools/rig/review_state.py','tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
            target=self.root/'tool-sources'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('# synthetic retained '+name);tools[name]=sha(target)
        scenario={'id':'controlled','required_test_count':1,'assertions':['observed'],'requires_handshake':True}
        identity={'run_id':'controlled','run_hash':'a'*64,'profile_hash':digest(profile),'scenario_hash':digest(scenario)}
        self.manifest={**identity,'runtime_hash':digest(runtime),'backend':'linux-headless','finished_at':1788987000,
            'run_manifest':{'staged_inputs':[{'target':'mods/candidate.jar','sha256':sha(self.root/'mods/candidate.jar')}],
                'generated_config_hash':'b'*64,'checker_sha256':'c'*64,'runtime_tools':tools}}
        proof={**identity,'ready':True,'handshake':True,'test_count':1,'assertions':{'observed':True},'evidence':{'observation.json':sha(self.root/'evidence/observation.json')}}
        result={**identity,'status':'passed','cleanup':'complete'}
        for name,value in {'manifest.json':self.manifest,'runtime.json':runtime,'profile.json':profile,'scenario.json':scenario,'proof.json':proof,'evidence/result.json':result,'processes.json':[{'pid':99999991,'start':'1','boot':'SYNTHETIC-DEAD-IDENTITY'}],'supervisor.json':{'pid':99999990,'start':'1','boot':'SYNTHETIC-DEAD-SUPERVISOR'}}.items():write(self.root/name,value)
    def test_derives_and_verifies_proof_artifact_index(self):
        result=export(self.root,'mods/candidate.jar',[],'controlled')
        self.assertEqual('pass',result['result']);self.assertIn('evidence/observation.json',result['evidence_index'])
        self.assertNotIn('evidence_index',result['run_manifest'])
    def test_changed_observation_or_candidate_cannot_export_as_pass(self):
        (self.root/'evidence/observation.json').write_text('{"changed":true}')
        with self.assertRaisesRegex(ValueError,'changed evidence: observation.json'):export(self.root,'mods/candidate.jar',[],'controlled')
        (self.root/'evidence/observation.json').write_text('{}')
        (self.root/'mods/candidate.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'exact staged'):export(self.root,'mods/candidate.jar',[],'controlled')
    def test_live_ownership_or_raw_private_logs_rejected(self):
        with patch('export_validation.alive',return_value=True):
            with self.assertRaisesRegex(ValueError,'still active'):export(self.root,'mods/candidate.jar',[],'controlled')
        with self.assertRaisesRegex(ValueError,'private logs'):export(self.root,'mods/candidate.jar',[],'controlled',['evidence/client.private.log'])

    def test_changed_retained_checker_rejected(self):
        (self.root/'tool-sources/tools/rig/proof.py').write_text('# changed')
        with self.assertRaisesRegex(ValueError,'retained scenario checker dependency changed'):export(self.root,'mods/candidate.jar',[],'controlled')

    def test_changed_retained_ownership_wrapper_rejected(self):
        (self.root/'tool-sources/scripts/lib/owned-process.py').write_text('# changed ownership semantics')
        with self.assertRaisesRegex(ValueError,'retained scenario checker dependency changed'):export(self.root,'mods/candidate.jar',[],'controlled')

    def test_missing_or_invalid_ownership_cannot_export_complete_cleanup(self):
        for name,value in [('processes.json',[]),('supervisor.json',None),('supervisor.json',{}),('processes.json',[None])]:
            with self.subTest(name=name,value=value):
                before=(self.root/name).read_bytes();write(self.root/name,value)
                with self.assertRaisesRegex(ValueError,'cannot establish complete owned cleanup'):export(self.root,'mods/candidate.jar',[],'controlled')
                (self.root/name).write_bytes(before)
        (self.root/'processes.json').unlink()
        with self.assertRaisesRegex(ValueError,'cannot establish complete owned cleanup'):export(self.root,'mods/candidate.jar',[],'controlled')
