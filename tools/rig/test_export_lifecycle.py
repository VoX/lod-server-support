import unittest
from check_export_lifecycle import check,EVENTS
class ExportChecks(unittest.TestCase):
 def text(self,events=None,run='owned'):return '\n'.join('[EXPORT-FIXTURE] '+e+' run='+run for e in (EVENTS if events is None else events))
 def test_complete_native_sequence(self):self.assertTrue(check(self.text(),'owned')['passed'])
 def test_each_missing_event_fails(self):
  for i in range(len(EVENTS)):
   with self.subTest(i=i):self.assertFalse(check(self.text(EVENTS[:i]+EVENTS[i+1:]),'owned')['passed'])
 def test_duplicate_fails(self):self.assertFalse(check(self.text(EVENTS+['PASS']),'owned')['passed'])
 def test_reorder_fails(self):self.assertFalse(check(self.text(EVENTS[::-1]),'owned')['passed'])
 def test_foreign_run_fails(self):self.assertFalse(check(self.text(run='foreign'),'owned')['passed'])
 def test_failure_fails(self):self.assertFalse(check(self.text(EVENTS+['FAIL_TIMEOUT']),'owned')['passed'])
if __name__=='__main__':unittest.main()
