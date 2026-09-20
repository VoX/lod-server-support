import unittest
from pathlib import Path
from catalog import client_gametests,Invalid,facts
class GametestCapability(unittest.TestCase):
 def test_loom_flag_registers_task_without_literal_task_name(self):
  self.assertTrue(client_gametests('fabricApi {\n configureTests {\n enableClientGameTests = true\n }\n}'))
 def test_comments_do_not_reverse_actual_flag(self):
  self.assertTrue(client_gametests('// enableClientGameTests = false\n/* enableClientGameTests = false */\nenableClientGameTests = true // enabled\n'))
  self.assertFalse(client_gametests('// runClientGameTest exists on other lines\nenableClientGameTests = false\n'))
 def test_absent_dynamic_or_duplicate_requires_explicit_review(self):
  for text in ('runClientGameTest','enableClientGameTests = someVariable','enableClientGameTests = true\nenableClientGameTests = false'):
   with self.assertRaises(Invalid):client_gametests(text)
 def test_top_level_boolean_alias_is_resolved_but_not_reassignment(self):
  self.assertTrue(client_gametests('def tier3 = true\nenableClientGameTests = tier3\n'))
  self.assertFalse(client_gametests('def tier3 = false\nenableClientGameTests = tier3\n'))
  for text in ('def tier3 = true\ntier3 = false\nenableClientGameTests = tier3\n','def tier3 = dynamic()\nenableClientGameTests = tier3\n','def tier3 = false\ntier3 |= true\nenableClientGameTests = tier3\n'):
   with self.assertRaises(Invalid):client_gametests(text)
if __name__=='__main__':unittest.main()
