import importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('checker',Path(__file__).with_name('check_elytra.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Controls(unittest.TestCase):
 def fixture(self):
  phases=[];rows=[]
  for n,name in enumerate(['equipped','crouched','standing_recovered','falling','gliding','landed']):
   phases.append(dict(id=name,start_ns=n*100,end_ns=n*100+90))
   for i in range(4):rows.append(dict(uuid='subject',nano_time=n*100+i*20,native_absent=True,elytra=True,distance=160.,crouching=name=='crouched',fall_flying=name=='gliding',x=float(i if name=='gliding' else 0),y=float(64-i if name=='falling' else 64),z=0.))
  return rows,phases
 def test_sequence(self):
  rows,phases=self.fixture();self.assertTrue(m.check(rows,phases,'subject')['passed'])
 def test_reused_interval_cannot_prove_recovery(self):
  rows,phases=self.fixture();phases[-1]['start_ns']=0
  with self.assertRaises(ValueError):m.check(rows,phases,'subject')
 def test_native_player_is_not_proxy_evidence(self):
  rows,phases=self.fixture()
  for r in rows:r['native_absent']=False
  self.assertFalse(m.check(rows,phases,'subject')['passed'])
 def test_falling_pose_cannot_stand_in_for_glide(self):
  rows,phases=self.fixture()
  for r in rows:r['fall_flying']=False
  self.assertFalse(m.check(rows,phases,'subject')['passed'])
 def test_landing_must_clear_glide(self):
  rows,phases=self.fixture()
  for r in rows:
   if r['nano_time']>=500:r['fall_flying']=True
  self.assertFalse(m.check(rows,phases,'subject')['passed'])
 def test_static_flying_does_not_prove_movement(self):
  rows,phases=self.fixture()
  for r in rows:r['x']=0.
  self.assertFalse(m.check(rows,phases,'subject')['passed'])
if __name__=='__main__':unittest.main()
