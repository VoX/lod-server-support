"""Actual filesystem identity controls; synthetic manifests, no native execution."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import types
import unittest
import checks
import verify


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PortabilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'owned run'
        self.repo = self.base / 'relocated repository'
        self.active = self.base / 'portable tools'
        (self.root / 'preset-tools').mkdir(parents=True)
        (self.repo / 'tools/rig').mkdir(parents=True)
        self.active.mkdir()
        staged = []
        for name in ('drive.py', 'checks.py', 'verify.py', 'finalize.py'):
            for target in (self.root / 'preset-tools' / name, self.active / name):
                shutil.copy2(Path(__file__).parent / name, target)
            staged.append({'target': 'preset-tools/' + name, 'sha256': sha(self.active / name)})
        tools = {}
        for name in ('rig.py', 'native_window.py', 'private_input.py', 'ui_snapshot_wait.py'):
            target = self.repo / 'tools/rig' / name
            target.write_text('# synthetic dependency ' + name + '\n')
            tools['tools/rig/' + name] = sha(target)
        runtime = {'stage_files': staged}
        bound = {'runtime_hash': digest(runtime), 'staged_inputs': staged, 'runtime_tools': tools}
        self.manifest = {'run_manifest': bound, 'run_hash': digest(bound), 'runtime_hash': digest(runtime)}
        (self.root / 'runtime.json').write_text(json.dumps(runtime))
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        self.files = {'entrypoint': self.active / 'drive.py'}

    def test_relocated_repository_and_portable_entrypoints_pass(self):
        self.assertEqual(self.repo.resolve(), checks.verify_tool_identity(self.root, self.repo, self.files))

    def test_changed_active_helpers_rejected_before_acceptance(self):
        for name in ('checks.py', 'verify.py', 'drive.py', 'finalize.py'):
            with self.subTest(name=name):
                p = self.active / name
                original = p.read_bytes()
                p.write_bytes(original + b'\n# changed active dependency\n')
                with self.assertRaisesRegex(ValueError, 'active preset tool differs'):
                    checks.verify_tool_identity(self.root, self.repo, self.files)
                p.write_bytes(original)

    def test_actual_verifier_rejects_changed_import_before_receipt_read(self):
        changed = self.active / 'checks.py'
        changed.write_text('# changed imported helper\n')
        original = checks.__file__
        try:
            checks.__file__ = str(changed)
            with self.assertRaisesRegex(ValueError, 'active preset tool differs: checks.py'):
                verify.verify(self.root, self.repo)
        finally:
            checks.__file__ = original
        self.assertFalse((self.root / 'evidence/standalone-presets/receipt.json').exists())

    def test_staged_tool_and_selected_repository_changes_rejected(self):
        for path, error in ((self.root/'preset-tools/verify.py', 'staged preset tool changed'),
                            (self.repo/'tools/rig/private_input.py', 'selected repository tool differs')):
            with self.subTest(path=path):
                raw = path.read_bytes();path.write_bytes(raw+b'\n# changed\n')
                with self.assertRaisesRegex(ValueError, error):
                    checks.verify_tool_identity(self.root, self.repo, self.files)
                path.write_bytes(raw)

    def test_runtime_and_manifest_rebinding_rejected(self):
        (self.root/'runtime.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'run input identity changed'):
            checks.verify_tool_identity(self.root, self.repo, self.files)

    def test_active_import_location_must_use_selected_repo(self):
        checks.verify_active_rig(self.repo, {'rig': types.SimpleNamespace(__file__=self.repo/'tools/rig/rig.py')})
        with self.assertRaisesRegex(ValueError, 'active rig import differs'):
            checks.verify_active_rig(self.repo, {'rig': types.SimpleNamespace(__file__=self.active/'rig.py')})


if __name__ == '__main__':
    unittest.main()
