import unittest
from drive_elytra import capture_hud_diagnostic
class HudFocus(unittest.TestCase):
 def test_focus_and_settle_before_capture(self):
  events=[]
  class Driver:
   def focus(self):events.append('focus')
   def capture(self,name):events.append(('capture',name))
  capture_hud_diagnostic(Driver(),lambda seconds:events.append(('settle',seconds)))
  self.assertEqual(events,['focus',('settle',.2),('capture','elytra-gliding-hud-diagnostic.png')])
 def test_failed_identity_focus_blocks_capture(self):
  events=[]
  class Driver:
   def focus(self):raise ValueError('observer identity changed')
   def capture(self,name):events.append(name)
  with self.assertRaises(ValueError):capture_hud_diagnostic(Driver(),lambda seconds:events.append(seconds))
  self.assertEqual(events,[])
if __name__=='__main__':unittest.main()
