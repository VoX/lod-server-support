"""Gated native seated captures; all process/input/image operations are mocked."""
import json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock,patch
import drive_seated_draw as drive
from check_seated_draw import check
from check_seated_run import inspect,make_proof,check_report
from rig import read,write,sha,digest
from test_seated_draw import TEXT
import test_seated_run as original_run

HEALTHY='[WI9-FIXTURE] HEALTHY_READY pass=3 both_seated_returns=true native_players_absent=true beyond_128=true scoping=true first_seated=aaa second=bbb\n'

class HealthyPremise(unittest.TestCase):
 def test_preserves_fault_proof_and_accepts_completed_healthy_premise(self):
  self.assertTrue(check(HEALTHY+TEXT)['passed'])
  for old,new in [('both_seated_returns=true','both_seated_returns=false'),('scoping=true','scoping=false'),
                  ('beyond_128=true','beyond_128=false'),('first_seated=aaa','first_seated=wrong')]:
   with self.subTest(old=old):self.assertFalse(check(HEALTHY.replace(old,new)+TEXT)['passed'])
 def test_reordered_or_duplicate_healthy_frame_fails(self):
  self.assertFalse(check(TEXT+HEALTHY)['passed'])
  self.assertFalse(check(HEALTHY+HEALTHY+TEXT)['passed'])

class CaptureOrdering(unittest.TestCase):
 def exercise(self,fail=None):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);(root/'evidence').mkdir();write(root/'owner.json',{'synthetic':True})
   log=root/'instances/lss-rig-client/minecraft/logs/latest.log';log.parent.mkdir(parents=True);log.write_text(HEALTHY)
   manifest={'run_id':'synthetic-owned','run_hash':'synthetic-hash'}
   gate=root/'evidence/seated-healthy-captured.txt'
   if fail=='preexisting':gate.write_text('foreign\n')
   if fail=='premature':log.write_text(HEALTHY+TEXT)
   driver=Mock();driver.display='mock-display';events=[]
   def verify():
    if gate.exists() and gate.read_text()==manifest['run_id']+'\n':log.write_text(HEALTHY+TEXT)
   driver.verify.side_effect=verify
   def capture(name):
    events.append(name)
    self.assertEqual(name=='seated-recovery.png',gate.exists())
    if fail==name:raise OSError('synthetic capture failure')
    (root/'evidence'/name).write_bytes(b'SYNTHETIC-NOT-NATIVE-'+name.encode())
   driver.capture.side_effect=capture
   with patch.object(drive,'alive',return_value=True),patch.object(drive.time,'sleep'),patch.object(drive.time,'monotonic',return_value=1):
    if fail:
     with self.assertRaises((ValueError,OSError)):drive.capture_pair(root,manifest,driver,10)
    else:
     result=drive.capture_pair(root,manifest,driver,10)
     self.assertEqual(result['visual_render']['artifact'],'seated-healthy.png')
     receipt=read(root/'evidence/seated-captures.json')
     self.assertEqual(receipt['recovery']['artifact_sha256'],sha(root/'evidence/seated-recovery.png'))
     self.assertEqual(events,['seated-healthy.png','seated-recovery.png'])
   calls=driver.test.XTestFakeButtonEvent.call_args_list
   if fail!='preexisting':
    self.assertEqual([tuple(c.args) for c in calls],[('mock-display',3,1,0),('mock-display',3,0,0)])
    driver.verify_release.assert_called_once()
   if fail in ('seated-healthy.png','premature'):self.assertFalse(gate.exists())
   return events
 def test_capture_before_gate_then_recovery_and_mouse_release(self):self.exercise()
 def test_failed_capture_releases_mouse_and_healthy_failure_never_arms(self):
  for phase in ['seated-healthy.png','seated-recovery.png','premature','preexisting']:
   with self.subTest(phase=phase):self.exercise(phase)

class CaptureBinding(unittest.TestCase):
 def prepare(self,root):
  manifest,log=original_run.SeatedRunTest().prepare(root)
  runtime=read(root/'runtime.json');runtime['seated_capture_version']=2;write(root/'runtime.json',runtime);manifest['runtime_hash']=digest(runtime)
  body=log.read_text();fields=dict(w.split('=',1) for w in body.split() if '=' in w)
  healthy=HEALTHY.replace('aaa',fields['first_seated']).replace('bbb',fields['second'])
  log.write_text(healthy+body)
  captures={'run_id':manifest['run_id'],'run_hash':manifest['run_hash']}
  for phase in ['healthy','recovery']:
   path=root/'evidence'/('seated-'+phase+'.png');path.write_bytes(b'SYNTHETIC-PIXELS-'+phase.encode())
   captures[phase]={'artifact':path.name,'artifact_sha256':sha(path)}
  gate=root/'evidence/seated-healthy-captured.txt';gate.write_text(manifest['run_id']+'\n');captures['gate_sha256']=sha(gate)
  write(root/'evidence/seated-captures.json',captures)
  return manifest,log,captures
 def test_both_images_bound_but_no_new_manual_review_key(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);m,log,c=self.prepare(root)
   proof=make_proof(root,m,review_artifacts={'visual_render':c['healthy']})
   self.assertEqual([],check_report(proof,m,root));self.assertEqual({},proof['reviews'])
   self.assertEqual({'visual_render'},set(proof['review_artifacts']))
   (root/'evidence/seated-recovery.png').write_bytes(b'CHANGED')
   self.assertTrue(check_report(proof,m,root))
 def test_missing_healthy_premise_wrong_gate_run_and_wrong_review_image_fail(self):
  for fault in ['healthy','gate','run','review']:
   with self.subTest(fault=fault),tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);m,log,c=self.prepare(root)
    if fault=='healthy':log.write_text('\n'.join(x for x in log.read_text().splitlines() if 'HEALTHY_READY' not in x))
    if fault=='gate':(root/'evidence/seated-healthy-captured.txt').write_text('other-run\n')
    if fault=='run':c['run_hash']='other';write(root/'evidence/seated-captures.json',c)
    proof=make_proof(root,m,review_artifacts={'visual_render':c['recovery'] if fault=='review' else c['healthy']})
    self.assertTrue(check_report(proof,m,root))

if __name__=='__main__':unittest.main()
