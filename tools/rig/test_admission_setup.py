import unittest
from drive_send_admission import fresh_baseline,baseline_epochs
class AdmissionSetup(unittest.TestCase):
 def test_old_baseline_cannot_arm_post_toggle(self):
  old='[WI6-FIXTURE] BASELINE_ROSTER tick=1 epoch=1 subject_index=1\n[WI6-FIXTURE] BASELINE_UPDATE tick=2 epoch=1\n'
  self.assertEqual([1],baseline_epochs(old));self.assertFalse(fresh_baseline(old,1))
  new=old+'[WI6-FIXTURE] BASELINE_ROSTER tick=3 epoch=3 subject_index=1\n'
  self.assertFalse(fresh_baseline(new,1))
  self.assertTrue(fresh_baseline(new+'[WI6-FIXTURE] BASELINE_UPDATE tick=4 epoch=3\n',1))
 def test_newer_roster_requires_its_own_update(self):
  text='[WI6-FIXTURE] BASELINE_ROSTER tick=1 epoch=3 subject_index=1\n[WI6-FIXTURE] BASELINE_UPDATE tick=2 epoch=3\n[WI6-FIXTURE] BASELINE_ROSTER tick=3 epoch=5 subject_index=1\n'
  self.assertFalse(fresh_baseline(text,1))
if __name__=='__main__':unittest.main()
