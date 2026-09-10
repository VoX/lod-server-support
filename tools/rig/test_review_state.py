import tempfile,unittest
from pathlib import Path
from rig import sha
from review_state import pending,runtime_status,semantic_digest,check_frozen
class ReviewStateTest(unittest.TestCase):
 def fixture(self,root):
  (root/'evidence').mkdir();image=root/'evidence/view.png';image.write_bytes(b'controlled-image')
  proof={'assertions':{'actual':True},'reviews':{},'review_artifacts':{'visual_render':{'artifact':'view.png','artifact_sha256':sha(image)}}}
  return proof,{'human_reviews':['visual_render']},['visual review pending/rejected: visual_render']
 def test_only_missing_review_defers_with_retained_artifact(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,s,e=self.fixture(root)
   self.assertEqual('awaiting-review',runtime_status(p,s,e,root))
   self.assertEqual('failed',runtime_status(p,s,e+['wrong current body'],root))
 def test_invalid_rejected_or_missing_image_is_failure(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);p,s,e=self.fixture(root)
   for review in ({},{'disposition':'rejected'},{'disposition':'accepted','run_hash':'wrong'}):
    p['reviews']={'visual_render':review};self.assertFalse(pending(p,s,e,root))
   p['reviews']={};(root/'evidence/view.png').write_bytes(b'changed')
   self.assertFalse(pending(p,s,e,root))
 def test_review_may_be_added_but_runtime_proof_cannot_change(self):
  with tempfile.TemporaryDirectory() as d:
   p,s,e=self.fixture(Path(d));m={'status':'awaiting-review','review_pending_proof_hash':semantic_digest(p)}
   p['reviews']={'visual_render':{'disposition':'accepted'}}
   self.assertEqual([],check_frozen(p,m))
   p['assertions']['actual']=False;self.assertTrue(check_frozen(p,m))
 def test_generic_failure_or_no_visual_lane_cannot_defer(self):
  with tempfile.TemporaryDirectory() as d:
   p,s,e=self.fixture(Path(d))
   self.assertFalse(pending(p,{},e,Path(d)))
   self.assertFalse(pending(p,s,['owned processes remain live'],Path(d)))
if __name__=='__main__':unittest.main()
