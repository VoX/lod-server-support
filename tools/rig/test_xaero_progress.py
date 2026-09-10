import unittest
from check_xaero_progress import check
class XaeroProgressTest(unittest.TestCase):
    def rows(self):return {'RigSubject'+s:[{'event':'client_gpu','context_current':True,'renderer':'GPU'}]+[{'event':'xaero_bridge','time_ns':t,'bridge_generation':1,'written':v,'instance_present':True} for t,v in [(1,0),(10,100),(20,105)]] for s in 'ABCD'}
    def test_actual_writes_inside_measurement(self):
        result=check(self.rows(),10,20,{'accelerated':True,'renderer':'OpenGL renderer string: GPU'})
        self.assertEqual('passed',result['status']);self.assertEqual(5,result['written_deltas']['RigSubjectA'])
    def test_initialization_only_is_not_measurement_progress(self):
        rows=self.rows();rows['RigSubjectA'][-1]['written']=100
        self.assertEqual('failed',check(rows,10,20,{'accelerated':True,'renderer':'GPU'})['status'])
    def test_counter_reset_needs_new_observed_instance(self):
        rows=self.rows();rows['RigSubjectA'][-1]['written']=5
        self.assertEqual('failed',check(rows,10,20,{'accelerated':True,'renderer':'GPU'})['status'])
    def test_missing_observer_and_software_fail(self):
        rows=self.rows();rows['RigSubjectA']=[]
        self.assertEqual('failed',check(rows,10,20,{'accelerated':True,'renderer':'GPU'})['status'])
        self.assertEqual('failed',check(self.rows(),10,20,{'accelerated':True,'renderer':'llvmpipe'})['status'])

    def test_actual_game_fallback_rejected(self):
        rows=self.rows();rows['RigSubjectA'][0]['renderer']='llvmpipe'
        self.assertEqual('failed',check(rows,10,20,{'accelerated':True,'renderer':'GPU'})['status'])
