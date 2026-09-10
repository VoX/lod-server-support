import tempfile,unittest
from pathlib import Path
from unittest.mock import patch,MagicMock
from private_input import XInput,validate_text
class PrivateInputTests(unittest.TestCase):
 def test_no_library_open_before_guard(self):
  with patch('private_input.guard_window',side_effect=ValueError('foreign')),patch('private_input.ctypes.CDLL') as library:
   with self.assertRaises(ValueError):XInput(Path('/tmp/unused'),'0x10',{'pid':1})
   library.assert_not_called()
 def test_no_control_or_clipboard_sequences(self):
  for text in ('hello\n','\x1b', 'x'*4097,'☃'):
   with self.assertRaises(ValueError):validate_text(text)
  validate_text('/lss diagnostics export')
 def test_bounded_hold_before_keypress(self):
  driver=object.__new__(XInput)
  for duration in (-1,11):
   with self.assertRaises(ValueError):driver.key('w',duration)
 def test_capture_checks_lifecycle_twice(self):
  driver=object.__new__(XInput);driver.root=Path('/tmp');driver.env={'DISPLAY':':999'}
  with patch.object(driver,'verify',side_effect=ValueError('stale')):
   with self.assertRaises(ValueError):driver.capture('x.png')
 def test_destroyed_window_still_releases_on_same_owned_display(self):
  driver=object.__new__(XInput);driver.root=Path('/tmp/owned');driver.display=1
  driver.env={'DISPLAY':':999','XAUTHORITY':'/tmp/owned/Xauthority'};driver.display_identity={'display':':999','xvfb':{'pid':12,'start':'pin'}}
  driver.x=MagicMock();driver.test=MagicMock()
  with patch.object(driver,'code',return_value=36),patch.object(driver,'focus'),patch('private_input.guard_display',return_value=driver.env),patch('private_input.read',return_value=driver.display_identity),patch.object(driver,'verify',side_effect=ValueError('window destroyed')):
   driver.key('Return',0)
  self.assertEqual([(1,36,1,0),(1,36,0,0)],[call.args for call in driver.test.XTestFakeKeyEvent.call_args_list])
 def test_release_refuses_replacement_private_display(self):
  driver=object.__new__(XInput);driver.root=Path('/tmp/owned');driver.env={'DISPLAY':':999','XAUTHORITY':'owned'};driver.display_identity={'xvfb':'old'}
  with patch('private_input.guard_display',return_value=driver.env),patch('private_input.read',return_value={'xvfb':'replacement'}):
   with self.assertRaisesRegex(ValueError,'identity changed'):driver.verify_release()
if __name__=='__main__':unittest.main()
