import unittest
from elytra_camera import framed
class CameraTest(unittest.TestCase):
 def setUp(self):
  self.camera=dict(run_id='r',role='observer',connection_id='r-observer',nano_time=100,x=0,z=0,eye_y=100.4,yaw=0,pitch=0)
  self.draw=dict(run_id='r',role='observer',connection_id='r-observer',uuid='s',nano_time=100,x=0,y=100,z=160,native_absent=True,fall_flying=True)
 def test_actual_centered_view(self):self.assertTrue(framed([self.camera],[self.draw],90,'r','s',110))
 def test_camera_facing_away_rejected(self):self.camera['yaw']=90;self.assertFalse(framed([self.camera],[self.draw],90,'r','s',110))
 def test_future_camera_not_borrowed(self):self.camera['nano_time']=200;self.assertFalse(framed([self.camera],[self.draw],90,'r','s',110))
 def test_stale_session_not_borrowed(self):self.camera['connection_id']='old';self.assertFalse(framed([self.camera],[self.draw],90,'r','s',110))
if __name__=='__main__':unittest.main()
