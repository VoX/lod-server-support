import unittest
from unittest.mock import Mock,patch
from xaero_map_input import zoom_out

class MapInputTest(unittest.TestCase):
 def driver(self):
  d=Mock();d.display=123;d.test.XTestFakeButtonEvent.return_value=1;return d
 def test_observed_native_change_releases(self):
  d=self.driver();seen=Mock(return_value=True);guard=Mock()
  zoom_out(d,940,240,seen,guard)
  self.assertEqual([(123,1,1,0),(123,1,0,0)],[c.args for c in d.test.XTestFakeButtonEvent.call_args_list]);d.verify_release.assert_called_once();seen.assert_called_once();guard.assert_called_once()
 def test_timeout_releases(self):
  d=self.driver()
  with patch('xaero_map_input.time.monotonic',side_effect=[0,0,2]),patch('xaero_map_input.time.sleep'):
   with self.assertRaisesRegex(ValueError,'did not change'):zoom_out(d,940,240,lambda:False,lambda:None)
  self.assertEqual((123,1,0,0),d.test.XTestFakeButtonEvent.call_args.args);d.verify_release.assert_called_once()
 def test_early_identity_failure_does_not_press(self):
  d=self.driver();d.focus.side_effect=ValueError('identity')
  with self.assertRaisesRegex(ValueError,'identity'):zoom_out(d,940,240,lambda:True,lambda:None)
  d.test.XTestFakeButtonEvent.assert_not_called()
 def test_identity_failure_during_hold_safely_releases(self):
  d=self.driver();d.verify.side_effect=ValueError('identity')
  with self.assertRaisesRegex(ValueError,'identity'):zoom_out(d,940,240,lambda:True,lambda:None)
  d.verify_release.assert_called_once();self.assertEqual((123,1,0,0),d.test.XTestFakeButtonEvent.call_args.args)
 def test_invalid_release_identity_never_targets_other_display(self):
  d=self.driver();d.verify_release.side_effect=ValueError('display changed')
  with self.assertRaisesRegex(ValueError,'display changed'):zoom_out(d,940,240,lambda:True,lambda:None)
  self.assertEqual(1,d.test.XTestFakeButtonEvent.call_count)
