import unittest
from verify import select
class SelectionTests(unittest.TestCase):
 def test_common_is_full(self):self.assertIn(':paper:build',select(['common/x.java'],'fast','1.21.1'))
 def test_unknown_is_full(self):self.assertIn(':neoforge:build',select(['new/x'],'fast','1.21.1'))
 def test_no_1211_client_task(self):self.assertNotIn(':fabric:runClientGameTest',select([],'integration','1.21.1'))
 def test_sibling_client_task(self):self.assertIn(':fabric:runClientGameTest',select([],'integration','26.2'))
 def test_docs(self):self.assertEqual([],select(['docs/foo.md'],'fast','1.21.1'))
 def test_platform(self):self.assertEqual([':common:test',':paper:test'],select(['paper/x.java'],'fast','1.21.1'))
if __name__=='__main__':unittest.main()
