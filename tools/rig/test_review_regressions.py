"""Synthetic state-machine regressions; no actual user approval or Minecraft evidence."""
import contextlib,copy,io,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,MagicMock
import rig
from review_state import semantic_digest,ownership_errors
from test_seated_run import SeatedRunTest
from check_seated_run import make_proof

class ReviewRegressionTest(unittest.TestCase):
 def prepare(self,root):
  manifest,_=SeatedRunTest().prepare(root)
  scenario=rig.read(root/'scenario.json');scenario.update(timeout_seconds=1,required_test_count=5,assertions=['seated_proxy_draw','fault_injected','next_proxy_draw','matrices_restored','name_tag_draw'],human_reviews=['visual'])
  runtime=rig.read(root/'runtime.json');runtime.update(backend='linux-headless',client_endpoint='127.0.0.1:12345')
  for launch in runtime['launches']:launch['cwd']='client'
  profile=dict(status='unverified',artifacts=[])
  manifest.update(schema_version=1,status='created',profile_id='SYNTHETIC',scenario_id='seated-draw',backend='linux-headless',scenario_hash=rig.digest(scenario),runtime_hash=rig.digest(runtime),profile_hash=rig.digest(profile),run_manifest={'runtime_tools':{}})
  for name,data in [('manifest',manifest),('runtime',runtime),('scenario',scenario),('profile',profile)]:rig.write(root/(name+'.json'),data)
  image=root/'evidence/image.png';image.write_bytes(b'SYNTHETIC IMAGE ONLY')
  proof=make_proof(root,manifest,{'visual':{'artifact':'image.png','artifact_sha256':rig.sha(image)}})
  return manifest,proof,runtime
 def command(self,root,name):
  output=io.StringIO()
  with patch('sys.argv',['rig.py',name,str(root)]),patch('toolchain.verify'),contextlib.redirect_stdout(output):rig.main()
  return json.loads(output.getvalue())
 def pending(self,root):
  m,p,r=self.prepare(root);m.update(status='awaiting-review',review_pending_proof_hash=semantic_digest(p));rig.write(root/'manifest.json',m)
  identity=dict(pid=99999991,start='1',boot='synthetic-dead-process-identity')
  rig.write(root/'supervisor.json',dict(identity,pid=99999990));rig.write(root/'processes.json',[identity,dict(identity,pid=99999992)])
  review=dict(disposition='accepted',run_id=m['run_id'],profile_hash=m['profile_hash'],run_hash=m['run_hash'],reviewer_kind='user',reviewer_id='SYNTHETIC',review_source={'kind':'user-message','reference':'SYNTHETIC-NOT-A-REAL-USER'},**p['review_artifacts']['visual'])
  p['reviews']={'visual':review};rig.write(root/'proof.json',p)
  return m,p,r
 def test_runtime_exception_and_prior_fixture_failure_cannot_be_promoted(self):
  for exception in (True,False):
   with self.subTest(exception=exception),tempfile.TemporaryDirectory()as d:
    root=Path(d);m,p,r=self.prepare(root)
    if not exception:p['failures']=['SYNTHETIC_RUNTIME_FAILURE'];rig.write(root/'proof.json',p)
    fake=MagicMock();fake.pid=99999999;fake.poll.return_value=None
    commands=patch('commands.Commands',side_effect=ValueError('SYNTHETIC_RUNTIME_FAILURE')) if exception else patch('commands.Commands')
    original_identity=rig.identity
    with patch('rig.identity',side_effect=lambda pid:dict(pid=pid,start='1',boot='synthetic') if pid==fake.pid else original_identity(pid)),patch('rig.require_lock'),patch('toolchain.verify'),patch('runtime_trees.verify'),patch('rig.check_available'),patch('rig.signal.signal'),patch('rig.subprocess.Popen',return_value=fake),patch('rig.terminate_owned'),commands,patch('measure.Sampler'):
     result=rig.run(root)
    self.assertEqual('failed',result['status']);self.assertTrue(any('SYNTHETIC_RUNTIME_FAILURE'in e for e in result['errors']))
    self.assertFalse(any('local variable'in e for e in result['errors']))
 def test_rejected_or_malformed_review_is_terminal(self):
  for review in ({'disposition':'rejected'},{},'malformed'):
   with self.subTest(review=review),tempfile.TemporaryDirectory()as d:
    root=Path(d);m,p,r=self.pending(root);accepted=copy.deepcopy(p)
    p['reviews']['visual']=review;rig.write(root/'proof.json',p)
    self.assertEqual('failed',self.command(root,'review')['status'])
    rig.write(root/'proof.json',accepted)
    self.assertEqual('failed',self.command(root,'review')['status'])
    self.assertEqual('failed',rig.read(root/'manifest.json')['status'])
 def test_finalized_review_artifact_and_semantic_proof_are_frozen_for_collect(self):
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,p,r=self.pending(root)
   self.assertEqual('passed',self.command(root,'review')['status'])
   image=root/'evidence/image.png';image.write_bytes(b'CHANGED SYNTHETIC IMAGE')
   p['review_artifacts']['visual']['artifact_sha256']=rig.sha(image);p['reviews']['visual']['artifact_sha256']=rig.sha(image);rig.write(root/'proof.json',p)
   result=self.command(root,'collect');self.assertEqual('failed',result['status']);self.assertTrue(any('after acceptance'in e for e in result['errors']))
 def test_missing_or_invalid_ownership_records_never_establish_cleanup(self):
  for name,value in [('processes.json',None),('supervisor.json',None),('processes.json',[]),('supervisor.json',{})]:
   with self.subTest(name=name,value=value),tempfile.TemporaryDirectory()as d:
    root=Path(d);m,p,r=self.pending(root)
    if value is None:(root/name).unlink()
    else:rig.write(root/name,value)
    self.assertTrue(ownership_errors(root,r))
    result=self.command(root,'collect');self.assertEqual('failed',result['status']);self.assertEqual('incomplete',result['cleanup'])
if __name__=='__main__':unittest.main()
