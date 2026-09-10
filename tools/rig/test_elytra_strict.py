import copy,unittest
from check_elytra_strict import check
from elytra_contract import PHASES
class Strict(unittest.TestCase):
 def setUp(self):
  self.phases=[];self.native=[];self.draw=[]
  for i,name in enumerate(PHASES):
   begin=1000+i*100;self.phases.append(dict(id=name,start_ns=begin,end_ns=begin+90))
   for j in range(4):
    base=dict(run_id='run',connection_id='run-target',role='target',uuid='subject',sequence=i*4+j,nano_time=begin+10+j*10,x=j if name=='gliding' else 0,y=100-j if name=='falling' else 100,z=160,elytra=True,crouching=name=='crouched',fall_flying=name=='gliding')
    self.native.append(dict(base,survival=True,on_ground=name not in ('falling','gliding')));self.draw.append(dict(base,connection_id='run-observer',role='observer',native_absent=True,distance=160))
 def result(self):return check(self.draw,self.native,self.phases,'run','subject')
 def test_six_real_phases(self):self.assertTrue(self.result()['passed'])
 def test_fabricated_glide_phase_without_native_flight_rejected(self):
  for row in self.native:row['fall_flying']=False
  self.assertFalse(self.result()['passed'])
 def test_stale_native_session_rejected(self):self.native[0]['connection_id']='old';self.assertFalse(self.result()['passed'])
 def test_native_visible_player_is_not_proxy_proof(self):
  for row in self.draw:row['native_absent']=False
  self.assertFalse(self.result()['passed'])
 def test_three_actual_submissions_required(self):self.draw=self.draw[2:];self.assertFalse(self.result()['passed'])
 def test_duplicate_submission_rows_not_counted(self):self.draw=[self.draw[i//4*4] for i in range(len(self.draw))];self.assertFalse(self.result()['passed'])
 def test_stationary_gliding_rejected(self):
  for row in self.draw:row['x']=0
  self.assertFalse(self.result()['passed'])
 def test_reordered_or_fabricated_labels_rejected(self):self.phases[0]['id']='gliding';self.assertFalse(self.result()['passed'])
if __name__=='__main__':unittest.main()
