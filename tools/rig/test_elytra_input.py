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

class RecaptureTests(unittest.TestCase):
 def exercise(self,succeed=2,guard=False,owner_dead=False):
  from elytra_input import recapture_mouse
  self.clock=1_000_000_000;self.clicks=[];self.records=[];self.events=[]
  def now():return self.clock
  def settle(seconds):self.events.append(('settle',seconds));self.clock+=int(seconds*1e9)
  outer=self
  class Driver:
   def focus(self):
    outer.events.append('focus')
    if guard:raise ValueError('foreign window')
   def click(self,x,y):outer.clicks.append((now(),x,y));outer.events.append('click')
  def observed(after):
   self.assertEqual(after,self.clicks[-1][0])
   # A stale positive native sample is insufficient. Only a new click that
   # actually obtains capture supplies the required fresh native observation.
   sample_ns=after+1 if len(self.clicks)>=succeed else after-1
   return sample_ns>=after
  def wait(predicate):
   for _ in range(100):
    if owner_dead:raise ValueError('bounded Elytra phase/owner deadline')
    if predicate():return
    self.clock+=50_000_000
   self.fail('unbounded recapture')
  recapture_mouse(Driver(),observed,lambda start,end:self.records.append((start,end)),wait,now,settle)
 def test_missed_first_click_retries_real_input_and_records_every_attempt(self):
  self.exercise()
  self.assertEqual(len(self.clicks),2);self.assertEqual(len(self.records),2)
  self.assertEqual(self.events,['focus',('settle',.2),'click']*2)
  self.assertTrue(all(end-start==200_000_000 for start,end in self.records))
 def test_successful_first_click_is_not_repeated(self):
  self.exercise(succeed=1);self.assertEqual(len(self.clicks),1)
 def test_six_click_bound_stays_failed_without_fresh_native_capture(self):
  with self.assertRaisesRegex(ValueError,'six verified clicks'):self.exercise(succeed=99)
  self.assertEqual(len(self.clicks),6);self.assertEqual(len(self.records),6)
 def test_identity_guard_failure_prevents_pointer_input(self):
  with self.assertRaisesRegex(ValueError,'foreign window'):self.exercise(guard=True)
  self.assertEqual(self.clicks,[]);self.assertEqual(self.records,[])
 def test_existing_owner_deadline_is_preserved(self):
  with self.assertRaisesRegex(ValueError,'phase/owner deadline'):self.exercise(owner_dead=True)
  self.assertEqual(self.clicks,[])

if __name__=='__main__':unittest.main()
