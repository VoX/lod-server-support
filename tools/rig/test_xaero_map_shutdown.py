import unittest,tempfile,copy
from pathlib import Path
from unittest.mock import patch
from xaero_map_shutdown import request_stop,validate_stop,KEYS
from test_xaero_map import fixture
class MapShutdownTest(unittest.TestCase):
 def inputs(self):
  rows,oracle=fixture();m=dict(run_id='SYNTHETIC',run_hash='hash');request=dict(**m,phase='all_raw_checks_passed',requested_ns=115)
  rows.insert(-1,dict(event='native_client_stop_requested',time_ns=116,run_id='SYNTHETIC',overflow=False,client_thread=True,request_run_id='SYNTHETIC',request_path='xaero-map-stop-client',requested_ns=115));return request,rows,m,oracle
 def test_request_only_after_exact_five_passes(self):
  with tempfile.TemporaryDirectory()as d:
   with patch('rig.write')as write:
    for flags in ({},dict.fromkeys(KEYS,False),dict(dict.fromkeys(KEYS,True),extra=True)):
     with self.assertRaises(ValueError):request_stop(Path(d),dict(run_id='x',run_hash='h'),flags)
    write.assert_not_called();request_stop(Path(d),dict(run_id='x',run_hash='h'),dict.fromkeys(KEYS,True));self.assertEqual(Path(d)/'evidence/xaero-map-stop-client',write.call_args.args[0])
 def test_native_owner_and_real_footer_order_match(self):
  self.assertTrue(validate_stop(*self.inputs()))
 def test_wrong_request_identity_or_path_rejected(self):
  for field,value in [('request_path','elsewhere'),('client_thread',False),('request_run_id','other'),('requested_ns',1)]:
   request,rows,m,oracle=self.inputs();rows[-2][field]=value
   with self.assertRaises(ValueError):validate_stop(request,rows,m,oracle)
 def test_stop_before_recovery_rejected_even_with_later_correct_rows(self):
  request,rows,m,oracle=self.inputs();request['requested_ns']=105;rows[-2]['requested_ns']=105
  with self.assertRaisesRegex(ValueError,'preceded'):validate_stop(request,rows,m,oracle)
 def test_duplicate_stop_rejected(self):
  request,rows,m,oracle=self.inputs();rows.insert(-1,copy.deepcopy(rows[-2]))
  with self.assertRaisesRegex(ValueError,'exactly one'):validate_stop(request,rows,m,oracle)
