import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import unittest
from unittest.mock import patch
import rig
import proof
import performance

class RigTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        artifact = self.root / 'a.jar'
        with zipfile.ZipFile(artifact,'w') as archive:archive.writestr('fixture-resource','exact candidate')
        self.profile = {'schema_version': 1, 'id': 'test', 'line':'26.2','platform':'fabric','route':'native','components':[], 'capabilities':[], 'status':'unverified', 'limitations':[], 'artifacts': [{'id': 'a', 'kind':'library','version':'test','metadata':{},'enabled':True,'file': 'a.jar', 'sha256': rig.sha(artifact), 'source': 'offline:test'}]}
        self.runtime = {'backend': 'linux-headless', 'bind_endpoint': '127.0.0.1:25574', 'client_endpoint': '[::1]:25574', 'cache': {rig.sha(artifact): str(artifact)}}
        self.scenario = {'id': 'test', 'required_test_count': 1, 'assertions': ['body_matches'], 'timeout_seconds': 1}
    def test_plan_is_read_only_and_preserves_ipv6(self):
        before = set(self.root.rglob('*'))
        result = rig.plan(self.profile, self.runtime, self.scenario)
        self.assertEqual(result['client_endpoint'], '[::1]:25574')
        self.assertEqual(before, set(self.root.rglob('*')))
    def test_same_name_wrong_bytes_rejected(self):
        (self.root / 'a.jar').write_bytes(b'wrong')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            rig.plan(self.profile, self.runtime, self.scenario)
    def test_missing_artifact_is_resolution_plan(self):
        self.runtime['cache'] = {}
        self.assertEqual('blocked', rig.plan(self.profile, self.runtime, self.scenario)['status'])
    def test_symlink_artifact_rejected(self):
        original = self.root / 'a.jar'
        link = self.root / 'link.jar'
        link.symlink_to(original)
        self.runtime['cache'][rig.sha(original)] = str(link)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            rig.plan(self.profile, self.runtime, self.scenario)
    def test_missing_candidate_is_not_ready(self):
        candidate = dict(self.profile['artifacts'][0], id='candidate', sha256='f'*64, file='candidate.jar')
        self.runtime['candidate_artifacts'] = [candidate]
        self.assertEqual('blocked', rig.plan(self.profile, self.runtime, self.scenario)['status'])
    def test_changed_participant_profile_rejected(self):
        path = self.root / 'participant.json'
        rig.write(path, self.profile)
        self.runtime['client_profiles'] = [{'role':'client-A', 'path':str(path), 'id':self.profile['id'], 'profile_hash':'a'*64}]
        with self.assertRaisesRegex(ValueError, 'participant profile identity'):
            rig.plan(self.profile, self.runtime, self.scenario)
    def test_participant_candidate_requires_exact_bytes(self):
        path = self.root / 'participant.json'
        rig.write(path, self.profile)
        candidate = dict(self.profile['artifacts'][0], id='candidate', sha256='f'*64, file='candidate.jar')
        self.runtime['server_profile'] = {'path':str(path), 'id':self.profile['id'], 'profile_hash':rig.digest(self.profile), 'candidate_artifacts':[candidate]}
        with self.assertRaisesRegex(ValueError, 'participant profile unresolved'):
            rig.plan(self.profile, self.runtime, self.scenario)
        self.runtime['server_profile']['candidate_artifacts'] = []
        self.assertEqual('ready', rig.plan(self.profile, self.runtime, self.scenario)['status'])
    def test_stale_identity(self):
        value = rig.identity(os.getpid())
        self.assertTrue(rig.alive(value))
        value['start'] = '-1'
        self.assertFalse(rig.alive(value))
    def test_display_lives_through_application_shutdown(self):
        events=[]
        class Process:
            def __init__(self,pid):self.pid=pid
            def poll(self):return None
            def terminate(self):events.append(('terminate',self.pid))
            def wait(self,timeout=None):events.append(('wait',self.pid))
        rig.terminate_owned([Process(1),Process(2),Process(3)],display_pid=1)
        self.assertGreater(events.index(('terminate',1)),events.index(('wait',2)))
        self.assertGreater(events.index(('terminate',1)),events.index(('wait',3)))
    def test_path_escape(self):
        with self.assertRaises(ValueError): rig.inside(self.root, '../foreign')
        (self.root / 'escape').symlink_to('/tmp')
        with self.assertRaises(ValueError): rig.inside(self.root, 'escape/value')
    def test_occupied_port(self):
        import socket
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            with self.assertRaises(OSError): rig.free_endpoint('127.0.0.1:' + str(sock.getsockname()[1]))
    def test_no_desktop_action(self):
        rig.write(self.root / 'display.json', {'display': ':0', 'xvfb': rig.identity(os.getpid())})
        rig.write(self.root / 'owner.json', rig.identity(os.getpid()))
        with self.assertRaisesRegex(ValueError, 'desktop'):
            rig.guard_window(self.root, '1', rig.identity(os.getpid()))
    def test_foreign_window(self):
        rig.write(self.root / 'display.json', {'display': ':998', 'host_display': ':0', 'xvfb': rig.identity(os.getpid())})
        rig.write(self.root / 'owner.json', rig.identity(os.getpid()))
        with patch('rig.verify_private_xvfb'), patch('subprocess.check_output', return_value='_NET_WM_PID = 1'):
            with self.assertRaisesRegex(ValueError, 'window'):
                rig.guard_window(self.root, '1', rig.identity(os.getpid()))
    def test_absent_and_stale_proof(self):
        manifest = {'run_id': 'one', 'profile_hash': 'abc', 'scenario_hash': 'def', 'run_hash':'full-identity'}
        valid = dict(manifest, ready=True, handshake=True, test_count=1, assertions={'body_matches': True})
        self.assertEqual([], proof.check_proof(valid, manifest, self.scenario))
        self.assertTrue(proof.check_proof({}, manifest, self.scenario))
        valid['run_id'] = 'older'
        self.assertTrue(proof.check_proof(valid, manifest, self.scenario))
    def test_human_review_never_inferred(self):
        self.scenario['human_reviews'] = ['image']
        manifest = {'run_id': 'one', 'profile_hash': 'abc', 'scenario_hash': 'def', 'run_hash':'full-identity'}
        valid = dict(manifest, ready=True, handshake=True, test_count=1, assertions={'body_matches': True})
        self.assertIn('visual review pending/rejected: image', proof.check_proof(valid, manifest, self.scenario))
    def test_review_binds_user_provenance_and_actual_run_artifact(self):
        self.scenario['human_reviews'] = ['image']
        manifest = {'run_id':'one','profile_hash':'abc','scenario_hash':'def','run_hash':'candidate-one'}
        evidence = self.root/'evidence'; evidence.mkdir()
        capture = evidence/'capture.png'; capture.write_bytes(b'unit fixture image')
        review = {'disposition':'accepted','run_id':'one','profile_hash':'abc','run_hash':'candidate-one',
                  'reviewer_kind':'user','reviewer_id':'unit-test-user',
                  'review_source':{'kind':'user-message','reference':'unit-test-message'},
                  'artifact':'capture.png','artifact_sha256':rig.sha(capture)}
        valid = dict(manifest,ready=True,handshake=True,test_count=1,
                     assertions={'body_matches':True},reviews={'image':review})
        self.assertEqual([],proof.check_proof(valid,manifest,self.scenario,self.root))
        for field,value in [('run_id','older-run-same-candidate'),('run_hash','other-candidate'),('reviewer_kind','assistant'),
                            ('review_source',{}),('artifact','../a.jar')]:
            changed=copy.deepcopy(valid); changed['reviews']['image'][field]=value
            self.assertTrue(proof.check_proof(changed,manifest,self.scenario,self.root),field)
        capture.write_bytes(b'replaced image')
        self.assertTrue(proof.check_proof(valid,manifest,self.scenario,self.root))
    def test_boolean_is_not_a_test_count(self):
        manifest={'run_id':'one','profile_hash':'abc','scenario_hash':'def','run_hash':'full'}
        valid=dict(manifest,ready=True,handshake=True,test_count=True,assertions={'body_matches':True})
        self.assertIn('required test count not reached',proof.check_proof(valid,manifest,self.scenario))
    def test_direct_create_requires_lock(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                rig.create(self.profile, self.runtime, self.scenario, self.root / 'runs')
    def test_actual_supervisor_reaps_escaped_child(self):
        marker = self.root / 'child.json'
        script = self.root / 'nested.py'
        script.write_text('import subprocess,sys,json,time\nfrom pathlib import Path\np=subprocess.Popen([sys.executable,"-c","import time; time.sleep(90)"],start_new_session=True)\nPath(sys.argv[1]).write_text(json.dumps({"pid":p.pid}))\n')
        result = subprocess.run(['python3', str(rig.REPO/'scripts/lib/owned-process.py'), '--', 'python3', str(script), str(marker)], timeout=15)
        pid = json.loads(marker.read_text())['pid']
        self.assertFalse(Path(f'/proc/{pid}').exists())
        self.assertNotEqual(0, result.returncode)

class PerformanceTests(unittest.TestCase):
    def test_one_region_negative_control(self):
        samples = [{'subject':'a','owns_region':True,'context':'owning-region','region_identity':'same','start_ns':1,'end_ns':5}, {'subject':'b','owns_region':True,'context':'owning-region','region_identity':'same','start_ns':2,'end_ns':6}]
        self.assertFalse(performance.overlap(samples))
        samples[1]['region_identity'] = 'other'
        self.assertTrue(performance.overlap(samples))
        samples[1]['context'] = 'global'
        self.assertFalse(performance.overlap(samples))
    def test_missing_measurements_inconclusive(self):
        self.assertEqual('inconclusive', performance.evaluate({})['status'])
    def test_lost_edit_stale_delivery_starvation_separate(self):
        run = {'sessions': [], 'oracle': [{'id':'edit','actual':'old','expected':'body','expected_session':'current','delivery_session':'old','fault_removed_ns':0}], 'progress_windows':[{'subject':'a','eligible':True,'duration_seconds':30,'useful_outcomes':0}]}
        errors = performance.correctness(run)
        for prefix in ('lost/current update:', 'stale-session delivery:', 'starvation:'):
            self.assertTrue(any(s.startswith(prefix) for s in errors))

if __name__ == '__main__': unittest.main()
