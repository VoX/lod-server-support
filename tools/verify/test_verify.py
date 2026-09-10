import unittest
from verify import select,cross_line_required,ROOT
import json
class SelectionTests(unittest.TestCase):
 def test_common_is_full(self):self.assertIn(':paper:build',select(['common/x.java'],'fast','1.21.1'))
 def test_unknown_is_full(self):self.assertIn(':neoforge:build',select(['new/x'],'fast','1.21.1'))
 def test_no_1211_client_task(self):self.assertNotIn(':fabric:runClientGameTest',select([],'integration','1.21.1'))
 def test_sibling_client_task(self):self.assertIn(':fabric:runClientGameTest',select([],'integration','26.2'))
 def test_docs(self):self.assertEqual([],select(['docs/foo.md'],'fast','1.21.1'))
 def test_platform(self):self.assertEqual([':common:test',':paper:test'],select(['paper/x.java'],'fast','1.21.1'))
 def test_full_and_unknown_impact_include_every_available_client_tier(self):
  for line in ('1.21.1','1.21.10','1.21.11','26.1','26.2'):
   for paths,tier in (([],'full'),([],'fast'),(['common/a.java'],'fast'),(['unknown/a'],'fast')):
    self.assertEqual(line!='1.21.1',':fabric:runClientGameTest'in select(paths,tier,line),(line,paths,tier))
 def test_unknown_and_shared_impact_cannot_narrow_the_line_set(self):
  manifest=json.loads((ROOT/'config/test-impact.json').read_text())
  for paths in ([],['common/A.java'],['tools/new.py'],['config/new.json'],['build.gradle'],['new/A.java']):
   self.assertTrue(cross_line_required(paths,manifest),paths)
  for paths in (['docs/example.md'],['paper/src/main/A.java'],['fabric/A.java','README.md']):
   self.assertFalse(cross_line_required(paths,manifest),paths)
if __name__=='__main__':unittest.main()
