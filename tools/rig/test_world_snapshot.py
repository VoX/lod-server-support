import tempfile
import unittest
from pathlib import Path
from rig import digest,sha,write
from world_snapshot import stages

class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        (self.root/'world').mkdir();(self.root/'world/level.dat').write_bytes(b'world')
        files=[{'file':'world/level.dat','sha256':sha(self.root/'world/level.dat')}]
        write(self.root/'snapshot.json',{'files':files,'world_digest':digest(files)})
    def test_same_snapshot_stages_regular_independent_files(self):
        identity,files=stages(self.root)
        self.assertEqual('server/world/level.dat',files[0]['target']);self.assertEqual(64,len(identity))
    def test_changed_bytes_or_extra_files_refuse_reset(self):
        (self.root/'world/level.dat').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'world bytes changed'):stages(self.root)
        (self.root/'extra').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError,'file set changed'):stages(self.root)
