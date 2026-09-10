import unittest
from measured_regions import bind
class MeasuredRegionsTest(unittest.TestCase):
 def fixture(self):
  rows=[];events=[]
  for i in range(2):
   rows.append(dict(connection_id=str(i),subject=str(i),region_identity=str(i),start_ns=20,end_ns=40,context='owning-region',owns_region=True))
   events.extend([dict(event='product_registration_observed',connection_id=str(i),subject=str(i),time_ns=10),dict(event='session_end',connection_id=str(i),subject=str(i),time_ns=50)])
  return dict(status='passed',region_samples=rows),events,15,45
 def test_actual_whole_ticks(self):self.assertEqual(2,bind(*self.fixture())['qualified_tick_samples'])
 def test_warmup_only_rejected(self):
  r,e,a,b=self.fixture()
  with self.assertRaises(ValueError):bind(r,e,41,60)
 def test_tick_crossing_registration_not_clipped(self):
  r,e,a,b=self.fixture();e[0]['time_ns']=21
  with self.assertRaises(ValueError):bind(r,e,a,b)
 def test_stale_connection_rejected(self):
  r,e,a,b=self.fixture();r['region_samples'][0]['connection_id']='retired'
  with self.assertRaises(ValueError):bind(r,e,a,b)
 def test_tick_crossing_measure_end_not_clipped(self):
  r,e,a,b=self.fixture()
  with self.assertRaises(ValueError):bind(r,e,a,39)
