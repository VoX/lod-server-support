import unittest,tempfile,copy,json
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
     with self.assertRaises(ValueError):request_stop(Path(d),dict(run_id='x',run_hash='h',profile_hash='p',scenario_hash='s'),flags)
    write.assert_not_called();request_stop(Path(d),dict(run_id='x',run_hash='h',profile_hash='p',scenario_hash='s'),dict.fromkeys(KEYS,True));self.assertEqual(Path(d)/'evidence/xaero-map-stop-client',write.call_args.args[0])
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

class NativeReceiptTest(unittest.TestCase):
 def fixture(self,root):
  from rig import write,sha
  m=dict(run_id='R',run_hash='H',profile_hash='P',scenario_hash='S');base=root/'evidence';base.mkdir()
  request=dict(**m,requested_ns=10);write(base/'xaero-map-stop-client',request)
  rows=[dict(event='observer_closed',time_ns=20)];(base/'xaero-map.jsonl').write_text(json.dumps(rows[0])+'\n')
  receipt=dict(**m,requested_ns=10,time_ns=30,proof_deadline_ns=10_000_000_030,writer_joined=True,overflow=False,pending=0,stream_sha256=sha(base/'xaero-map.jsonl'),request_sha256=sha(base/'xaero-map-stop-client'))
  write(base/'xaero-map-native-close.json',receipt);return m,rows,receipt
 def test_durable_receipt_matches_exact_closed_stream(self):
  from xaero_map_shutdown import validate_close
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,rows,r=self.fixture(root);self.assertEqual(r,validate_close(root,m,rows))
 def test_failure_or_late_publication_stays_failed(self):
  from xaero_map_shutdown import validate_close
  from rig import write
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,rows,r=self.fixture(root)
   with patch('xaero_map_shutdown.time.monotonic_ns',return_value=r['proof_deadline_ns']):
    with self.assertRaisesRegex(ValueError,'deadline'):validate_close(root,m,rows,publication=True)
   write(root/'evidence/xaero-map-native-close-failed.json',{'error':'timeout'})
   with self.assertRaisesRegex(ValueError,'failed'):validate_close(root,m,rows)
 def test_wrong_identity_overflow_pending_unjoined_and_changed_bytes(self):
  from xaero_map_shutdown import validate_close
  from rig import write
  for field,value in [('run_id','wrong'),('run_hash','wrong'),('requested_ns',11),('writer_joined',False),('overflow',True),('pending',1),('stream_sha256','bad'),('request_sha256','bad'),('time_ns',19)]:
   with tempfile.TemporaryDirectory()as d:
    root=Path(d);m,rows,r=self.fixture(root);r[field]=value;write(root/'evidence/xaero-map-native-close.json',r)
    with self.assertRaises(ValueError):validate_close(root,m,rows)
 def test_missing_receipt_rejected(self):
  from xaero_map_shutdown import validate_close
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,rows,r=self.fixture(root);(root/'evidence/xaero-map-native-close.json').unlink()
   with self.assertRaises((ValueError,OSError)):validate_close(root,m,rows)

 def test_timely_original_proof_revalidates_after_delayed_cleanup(self):
  from rig import write
  from check_xaero_map_run import make_proof
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,rows,r=self.fixture(root)
   for name in ['xaero-map-oracle.json','xaero-map-commands.json','xaero-map-viewport.json']:write(root/'evidence'/name,{})
   report=dict(handshake=True,passed=True,closed=True,errors=[],assertions=dict.fromkeys(KEYS,True))
   with patch('check_xaero_map_run.inspect',return_value=report):
    with patch('xaero_map_shutdown.time.monotonic_ns',return_value=31):
     original=make_proof(root,m,publication=True)
    with patch('xaero_map_shutdown.time.monotonic_ns',return_value=r['proof_deadline_ns']+1):
     self.assertEqual(original,make_proof(root,m))
     with self.assertRaisesRegex(ValueError,'deadline'):make_proof(root,m,publication=True)
 def test_failed_receipt_replacement_rejected(self):
  from xaero_map_shutdown import validate_close
  from rig import write
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,rows,r=self.fixture(root)
   write(root/'evidence/xaero-map-native-close.json',dict(run_id='R',error='failure marker unavailable'))
   with self.assertRaises(ValueError):validate_close(root,m,rows)
