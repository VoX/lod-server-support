"""Actual file-byte identity controls only; no native acceptance simulation."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

VERIFIER=Path(__file__).resolve().parents[2]/'test-fixtures/server-preset-tools/verify.py'
spec=importlib.util.spec_from_file_location('preset_fixture_verifier',VERIFIER)
verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)

class ActiveDependencyControls(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.staged={}
        for name in ('server_control_smoke.py','conservative_native.py','check_conservative_native.py'):
            data=('# synthetic identity-only source '+name+'\n').encode()
            (self.root/name).write_bytes(data)
            self.staged['preset-tools/'+name]=hashlib.sha256(data).hexdigest()
    def test_unchanged_active_dependencies_are_accepted(self):
        verifier.verify_active_dependencies(self.root,self.staged)
    def test_changed_active_numeric_helper_is_rejected(self):
        (self.root/'conservative_native.py').write_text('# changed active helper\n')
        with self.assertRaisesRegex(ValueError,'conservative_native.py'):
            verifier.verify_active_dependencies(self.root,self.staged)
    def test_changed_active_numeric_checker_is_rejected(self):
        (self.root/'check_conservative_native.py').write_text('# changed active checker\n')
        with self.assertRaisesRegex(ValueError,'check_conservative_native.py'):
            verifier.verify_active_dependencies(self.root,self.staged)

if __name__=='__main__':unittest.main(verbosity=2)
