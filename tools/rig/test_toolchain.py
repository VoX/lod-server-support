import tempfile
import unittest
from pathlib import Path
from toolchain import snapshot,verify,retain

class ToolchainTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        for name in ('tools/rig/rig','tools/rig/rig.py','tools/rig/private_input.py','tools/compat/catalog.py','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py'):
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(name)
        self.identity=snapshot(self.root)
    def test_helper_and_supervisor_bytes_are_bound(self):
        verify(self.root,self.identity)
        (self.root/'tools/rig/private_input.py').write_text('changed')
        with self.assertRaisesRegex(ValueError,'sources changed'):verify(self.root,self.identity)
    def test_new_runtime_helper_is_not_implicitly_accepted(self):
        (self.root/'tools/rig/new.py').write_text('new')
        with self.assertRaises(ValueError):verify(self.root,self.identity)
    def test_test_changes_do_not_change_runtime_identity(self):
        (self.root/'tools/rig/test_added.py').write_text('test')
        verify(self.root,self.identity)
    def test_retained_sources_remain_inspectable_after_worktree_edit(self):
        destination=self.root/'retained';retain(self.root,destination,self.identity)
        (self.root/'tools/rig/rig.py').write_text('later change')
        verify(destination,self.identity)
        (destination/'tools/rig/rig.py').write_text('tampered evidence')
        with self.assertRaises(ValueError):verify(destination,self.identity)
