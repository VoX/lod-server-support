import unittest
from elytra_input import look_down
class Call:
 def __init__(self,events):self.events=events
 def __call__(self,*args):self.events.append(('motion',args));return 1
class Driver:
 def __init__(self):
  self.events=[];self.display=1;self.test=type('Test',(),{})();self.test.XTestFakeRelativeMotionEvent=Call(self.events);self.x=type('X',(),{'XFlush':lambda x,d:None})()
 def focus(self):self.events.append('focus')
 def verify(self):self.events.append('verify')
class InputTests(unittest.TestCase):
 def test_guarded_bounded_real_relative_call(self):
  d=Driver();look_down(d);self.assertEqual(['focus']+['verify',('motion',(1,0,100,0)),'verify']*4,d.events)
 def test_failed_guard_prevents_motion(self):
  d=Driver();d.verify=lambda:(_ for _ in ()).throw(ValueError('foreign window'))
  with self.assertRaises(ValueError):look_down(d)
  self.assertNotIn(('motion',(1,0,100,0)),d.events)
if __name__=='__main__':unittest.main()
