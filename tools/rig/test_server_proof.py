"""Server-only evidence cannot substitute a claimed count for native named tests."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
import proof
import rig


class ServerProofTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'evidence').mkdir()
        self.report = self.root / 'evidence' / 'gametests.xml'
        self.scenario = json.loads((Path(__file__).parent / 'scenarios/c2me-save-read.json').read_text())
        self.report.write_text('<testsuite>' + ''.join('<testcase name="' + n + '"/>' for n in self.scenario['required_gametests']) + '</testsuite>')
        self.manifest = dict(run_id='one', profile_hash='exact-profile', scenario_hash='exact-scenario', run_hash='exact-run')
        self.value = dict(self.manifest, ready=True, test_count=3,
                          assertions={n: True for n in self.scenario['assertions']},
                          gametest_report=dict(artifact='gametests.xml', artifact_sha256=rig.sha(self.report)))

    def check(self):
        return proof.check_proof(self.value, self.manifest, self.scenario, self.root)

    def test_actual_named_report_allows_server_only_route(self):
        self.assertEqual([], self.check())

    def test_default_and_explicit_client_routes_still_require_handshake(self):
        for setting in (None, True):
            if setting is None:
                self.scenario.pop('requires_handshake', None)
            else:
                self.scenario['requires_handshake'] = setting
            self.assertIn('actual handshake missing', self.check())

    def test_false_requires_server_route_and_declared_names(self):
        self.scenario['execution_route'] = 'client'
        self.assertTrue(self.check())
        self.scenario['execution_route'] = 'server-gametest'
        self.scenario.pop('required_gametests')
        self.assertTrue(self.check())

    def test_truthy_strings_do_not_disable_handshake(self):
        self.scenario['requires_handshake'] = 'false'
        self.assertTrue(self.check())

    def test_stale_report_and_path_escape_are_rejected(self):
        self.report.write_text('<testsuite/>')
        self.assertTrue(self.check())
        self.value['gametest_report']['artifact'] = '../elsewhere.xml'
        self.assertTrue(self.check())

    def test_empty_wrong_failed_skipped_duplicate_xml_never_passes(self):
        original = self.report.read_text()
        for xml in ('<testsuite/>', original.replace(self.scenario['required_gametests'][0], 'other'),
                    original.replace('/>', '><failure/></testcase>', 1),
                    original.replace('/>', '><skipped/></testcase>', 1),
                    original.replace(self.scenario['required_gametests'][0], self.scenario['required_gametests'][1])):
            self.report.write_text(xml)
            self.value['gametest_report']['artifact_sha256'] = rig.sha(self.report)
            self.assertTrue(self.check(), xml)

    def test_count_and_semantics_remain_required(self):
        for count in (True, 4, 2):
            self.value['test_count'] = count
            self.assertTrue(self.check())
        self.value['test_count'] = 3
        self.value['assertions']['save_completed'] = False
        self.assertTrue(self.check())


if __name__ == '__main__':
    unittest.main()
