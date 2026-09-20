import tempfile,unittest
from pathlib import Path
from check_xaero_map_run import load_rows
class MapStreamTest(unittest.TestCase):
 def test_open_writer_partial_tail_is_only_provisional(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);(root/'evidence').mkdir();p=root/'evidence/xaero-map.jsonl'
   p.write_bytes(b'{"event":"observer_closed"}\n{"event":"observer_failure"')
   self.assertEqual([{"event":"observer_closed"}],load_rows(root))
   with self.assertRaisesRegex(ValueError,'incomplete trailing'):load_rows(root,require_closed=True)
 def test_closed_stream_requires_complete_json_records(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);(root/'evidence').mkdir();p=root/'evidence/xaero-map.jsonl'
   p.write_bytes(b'{"event":"observer_closed"}\n')
   self.assertEqual([{"event":"observer_closed"}],load_rows(root,require_closed=True))
   p.write_bytes(b'{"event":\n')
   with self.assertRaises(ValueError):load_rows(root,require_closed=True)
if __name__=='__main__':unittest.main()
