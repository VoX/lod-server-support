import tempfile,json,unittest
from pathlib import Path
from unittest.mock import patch
import check_prefill
from rig import digest

class PrefillProofTest(unittest.TestCase):
    def fixture(self,root):
        root=Path(root);(root/'evidence').mkdir();(root/'participants').mkdir();(root/'server/world').mkdir(parents=True)
        def write(path,value):(root/path).write_text(json.dumps(value))
        profile=dict(platform='paper',route='native');write('participants/server.json',profile)
        runtime=dict(launches=[dict(id='server',stop_stdin='stop')],client_profiles=[],server_profile=dict(profile_hash=digest(profile),candidate_artifacts=[dict(kind='plugin',metadata=dict(paper=dict(name='LssRigNativePrefill')))]))
        scenario=dict(execution_route='source-prefill',requires_handshake=False)
        write('runtime.json',runtime);write('scenario.json',scenario);write('evidence/source-prefill-stop.json',dict(run_id='run',server_returncode=0))
        events=[dict(event='prefill_started',expected=1,max_pending=8,time_ns=1),dict(event='prefill_full',chunk_x=0,chunk_z=0,time_ns=2),dict(event='prefill_complete',completed=1,time_ns=3),dict(event='prefill_closed',overflow=False)]
        for event in events:event['run_id']='run'
        (root/'evidence/prefill-events.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in events))
        return dict(run_id='run',run_hash='runhash',profile_hash='profile',runtime_hash=digest(runtime),scenario_hash=digest(scenario))
    def inspect(self,root,manifest):
        with patch.object(check_prefill,'DOMAIN',((0,0),)),patch.object(check_prefill,'verify_full',return_value={'full_chunks':1}):return check_prefill.inspect(root,manifest)
    def test_native_completion_and_saved_record_both_required(self):
        with tempfile.TemporaryDirectory() as root:
            manifest=self.fixture(root);self.assertEqual('passed',self.inspect(root,manifest)['status'])
            with patch.object(check_prefill,'DOMAIN',((0,0),)),patch.object(check_prefill,'verify_full',side_effect=ValueError('missing saved FULL')):
                self.assertTrue(check_prefill.check_report({},manifest,root))
    def test_extension_store_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            manifest=self.fixture(root);(Path(root)/'server/world/store.sqlite').touch();self.assertIn('prefill world contains extension store',self.inspect(root,manifest)['errors'])
    def test_foreign_native_row_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            manifest=self.fixture(root);p=Path(root)/'evidence/prefill-events.jsonl';p.write_text(p.read_text().replace('"run_id": "run"','"run_id": "foreign"'));self.assertIn('foreign prefill evidence',self.inspect(root,manifest)['errors'])
    def test_unclean_native_exit_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            manifest=self.fixture(root);p=Path(root)/'evidence/source-prefill-stop.json';p.write_text(json.dumps(dict(run_id='run',server_returncode=143)));self.assertIn('native prefill server did not stop cleanly',self.inspect(root,manifest)['errors'])
    def test_missing_completion_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            manifest=self.fixture(root);p=Path(root)/'evidence/prefill-events.jsonl';p.write_text('\n'.join(line for line in p.read_text().splitlines() if 'prefill_full' not in line)+'\n');self.assertIn('native FULL completion domain absent/duplicate',self.inspect(root,manifest)['errors'])

if __name__=='__main__':unittest.main()
