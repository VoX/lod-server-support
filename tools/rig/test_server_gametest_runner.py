"""Controlled child processes pin native-report handling, never live acceptance."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import sys

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import rig
import proof


class ServerGameTestRunnerTests(unittest.TestCase):
    def run_fixture(self, *, report=True, version=True, exit_code=0):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'server').mkdir(); (root / 'evidence').mkdir()
            manifest = dict(run_id='unit-test', profile_hash='profile', scenario_hash='scenario', run_hash='run')
            scenario = json.loads((TOOLS / 'scenarios/c2me-save-read.json').read_text())
            rig.write(root / 'manifest.json', manifest); rig.write(root / 'scenario.json', scenario)
            xml = '<testsuite>' + ''.join('<testcase name="' + name + '"/>' for name in scenario['required_gametests']) + '</testsuite>'
            script = root / 'native-fixture.py'
            script.write_text('from pathlib import Path\nprint("Starting test server")\n' +
                              ('print("c2me unit-pinned-version")\n' if version else '') +
                              ('Path(' + repr(str(root / 'evidence/gametests.xml')) + ').write_text(' + repr(xml) + ')\n' if report else '') +
                              'raise SystemExit(' + str(exit_code) + ')\n')
            rig.write(root / 'gametest-command.json', dict(argv=[sys.executable, str(script)], c2me_version='unit-pinned-version'))
            process = subprocess.Popen([sys.executable, str(TOOLS / 'run_server_gametests.py'), str(root), str(TOOLS)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 5
                while not (root / 'proof.json').exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(.02)
                self.assertTrue((root / 'proof.json').exists())
                value = rig.read(root / 'proof.json')
                errors = proof.check_proof(value, manifest, scenario, root)
                return value, errors
            finally:
                if process.poll() is None: process.terminate()
                process.communicate(timeout=5)

    def test_native_report_and_runtime_version_are_both_required(self):
        value, errors = self.run_fixture()
        self.assertEqual([], errors)
        self.assertIs(value['handshake'], False)
        self.assertEqual(3, value['test_count'])

    def test_zero_exit_without_xml_is_not_success(self):
        self.assertTrue(self.run_fixture(report=False)[1])

    def test_wrong_active_c2me_version_is_not_success(self):
        self.assertTrue(self.run_fixture(version=False)[1])

    def test_successful_xml_does_not_hide_process_failure(self):
        self.assertTrue(self.run_fixture(exit_code=1)[1])


if __name__ == '__main__': unittest.main()
