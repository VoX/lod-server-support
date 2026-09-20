import json,unittest
from ui_snapshot_wait import observe_after_action
class UiSnapshotWaitTests(unittest.TestCase):
 def run_rows(self,rows,expected=False,clock=None):
  self.retained=[];it=iter(rows)
  return observe_after_action(lambda deadline:json.dumps(next(it)).encode(),100,expected,lambda n,raw:self.retained.append((n,raw)),clock=clock or (lambda:0))
 def test_stale_then_fresh_correct_retains_both(self):
  raw,proof=self.run_rows([{'capturedAtMillis':100,'receptionEnabled':True},{'capturedAtMillis':101,'receptionEnabled':False}]);self.assertEqual(2,len(self.retained));self.assertEqual(1,proof['stale_exports']);self.assertFalse(json.loads(raw)['receptionEnabled'])
 def test_fresh_wrong_fails_immediately(self):
  with self.assertRaisesRegex(ValueError,'fresh post-action'):self.run_rows([{'capturedAtMillis':101,'receptionEnabled':True},{'capturedAtMillis':102,'receptionEnabled':False}])
  self.assertEqual(1,len(self.retained))
 def test_stale_timeout_is_failure(self):
  ticks=iter([0,0,0,6])
  with self.assertRaisesRegex(ValueError,'bounded deadline'):self.run_rows([{'capturedAtMillis':99,'receptionEnabled':False}],clock=lambda:next(ticks))
  self.assertEqual(1,len(self.retained))
 def test_missing_capture_time_rejected(self):
  with self.assertRaisesRegex(ValueError,'capture time'):self.run_rows([{'receptionEnabled':False}])
 def test_equal_millisecond_is_not_post_action(self):
  _,p=self.run_rows([{'capturedAtMillis':100,'receptionEnabled':False},{'capturedAtMillis':101,'receptionEnabled':False}]);self.assertEqual(2,p['export_attempts'])
