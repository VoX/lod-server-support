import tempfile
import unittest
from pathlib import Path
from rig import sha
from runtime_trees import verify

class DependencyTreesTest(unittest.TestCase):
    def test_added_runtime_download_refuses_closure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);base=root/'server/libraries';base.mkdir(parents=True);jar=base/'one.jar';jar.write_bytes(b'one')
            trees={'server/libraries':{'one.jar':sha(jar)}};verify(root,trees)
            (base/'downloaded.jar').write_bytes(b'two')
            with self.assertRaisesRegex(ValueError,'tree changed'):verify(root,trees)

    def test_owned_replacement_server_keeps_exact_dependency_closure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);base=root/'server-replacement/libraries';base.mkdir(parents=True)
            jar=base/'one.jar';jar.write_bytes(b'one')
            expected={'server-replacement/libraries':{'one.jar':sha(jar)}}
            verify(root,expected)
            (base/'unexpected.jar').write_bytes(b'two')
            with self.assertRaisesRegex(ValueError,'tree changed'):verify(root,expected)
            with self.assertRaisesRegex(ValueError,'unsupported'):verify(root,{'server-replacement/world':{}})

    def test_independent_native_target_assets_are_hash_bound(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);base=root/'target-assets/indexes';base.mkdir(parents=True)
            index=base/'test.json';index.write_bytes(b'original')
            expected={'target-assets':{'indexes/test.json':sha(index)}}
            verify(root,expected)
            index.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'tree changed'):verify(root,expected)
            with self.assertRaisesRegex(ValueError,'unsupported'):verify(root,{'seated-target-a/world':{}})
