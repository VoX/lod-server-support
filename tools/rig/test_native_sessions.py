import unittest
from native_sessions import check
class NativeSessionsTest(unittest.TestCase):
 def fixture(self):
  rows=[];accepted=[]
  for name in ('A','B','C','D','B'):
   connection='connection'+str(len(accepted));subject='RigSubject'+name
   rows.extend([dict(event='join',connection_id=connection,subject=subject,time_ns=1),dict(event='product_registration_observed',connection_id=connection,subject=subject,time_ns=2),dict(event='quit',connection_id=connection,subject=subject,time_ns=3)])
   accepted.append(dict(connection_id=connection,subject=subject,accepted=True))
  return rows,accepted
 def test_actual_reconnect(self):self.assertEqual([],check(*self.fixture()))
 def test_missing_second_handshake(self):
  r,a=self.fixture();a.pop();self.assertTrue(check(r,a))
 def test_old_registration(self):
  r,a=self.fixture();r[-2]['connection_id']=r[4]['connection_id'];self.assertTrue(check(r,a))
 def test_outside_interval(self):
  r,a=self.fixture();r[1]['time_ns']=4;self.assertTrue(check(r,a))
 def test_missing_native_end(self):
  r,a=self.fixture();r.pop();self.assertTrue(check(r,a))
 def test_duplicate_join(self):
  r,a=self.fixture();r.append(r[0]);self.assertTrue(check(r,a))
