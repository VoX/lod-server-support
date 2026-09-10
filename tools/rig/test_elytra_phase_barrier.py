import unittest
from elytra_phase_barrier import elevated
class BarrierTest(unittest.TestCase):
 def rows(self,role,ys,start=10):
  return [dict(run_id='r',role=role,connection_id='r-'+role,nano_time=start+i,y=y,elytra=True,fall_flying=False,survival=True,on_ground=False,native_absent=True) for i,y in enumerate(ys)]
 def test_stale_low_prefix_requires_real_new_elevation(self):
  n=self.rows('target',[81,178,176,173]);d=self.rows('observer',[81,170,166,162]);self.assertTrue(elevated(n,d,10,'r'))
 def test_no_elevated_proxy_never_arms_despite_native_fall(self):self.assertFalse(elevated(self.rows('target',[178,176,173]),self.rows('observer',[81,81,81]),10,'r'))
 def test_precommand_elevated_samples_cannot_arm(self):self.assertFalse(elevated(self.rows('target',[178,176,173]),self.rows('observer',[170,166,162]),20,'r'))
 def test_landed_latest_samples_cannot_resurrect_earlier_elevation(self):self.assertFalse(elevated(self.rows('target',[178,176,173,81]),self.rows('observer',[170,166,162,81]),10,'r'))
 def test_stale_session_cannot_arm(self):
  n=self.rows('target',[178,176,173]);n[-1]['connection_id']='old';self.assertFalse(elevated(n,self.rows('observer',[170,166,162]),10,'r'))
 def test_descending_interval_after_barrier_preserves_movement_law(self):
  n=self.rows('target',[178,176,173]);d=self.rows('observer',[170,166,162]);self.assertTrue(elevated(n,d,10,'r'));after=13
  for stream in (self.rows('target',[168,165,161],after),self.rows('observer',[160,157,153],after)):
   self.assertGreaterEqual(len(stream),3);self.assertLess(stream[-1]['y'],stream[0]['y']-.1)
if __name__=='__main__':unittest.main()
