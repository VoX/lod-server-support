"""Synthetic fixed-slot claim and raw-report protocol regressions."""
import copy,tempfile,unittest,shutil
from pathlib import Path
from unittest.mock import patch
import rig,run_claim,experiment as ledger,launch_journal
import test_failed_slot
from test_launch_journal import owner,receipt

class ClaimTests(unittest.TestCase):
 def setUp(self):
  self.fixture=test_failed_slot.FailedSlotTests();self.fixture.setUp();self.addCleanup(self.fixture.tearDown)
  self.root=self.fixture.root;self.run=self.fixture.run;self.runtime=self.fixture.runtime
 def test_other_created_run_cannot_reuse_unrecorded_failed_intent(self):
  other=self.run.parent/'other';shutil.copytree(self.run,other)
  m=rig.read(other/'manifest.json');m.update(status='created',run_id='other');rig.write(other/'manifest.json',m)
  self.assertEqual(self.fixture.binding,ledger.intent(self.root,'calibration'))
  with self.assertRaisesRegex(ValueError,'another run'):run_claim.acquire(other,self.runtime,m)
 def test_copied_registration_cannot_claim_in_another_directory(self):
  other=self.root.parent/'clone';shutil.copytree(self.root,other)
  r=dict(self.runtime,measurement_experiment_root=str(other))
  with self.assertRaisesRegex(ValueError,'root/protocol'):run_claim.context(other,r)
 def test_missing_locator_protocol_and_claim_rejected(self):
  m=rig.read(self.run/'manifest.json');m['status']='created'
  r=dict(self.runtime);r.pop('measurement_experiment_root')
  with self.assertRaisesRegex(ValueError,'locator'):run_claim.acquire(self.run,r,m)
  (self.root/'calibration-0-claim.json').unlink()
  with self.assertRaises((ValueError,FileNotFoundError)):run_claim.verify(self.root,self.run)
 def test_same_created_pre_native_claim_resumes_but_running_does_not(self):
  m=rig.read(self.run/'manifest.json');m['status']='created';rig.write(self.run/'manifest.json',m)
  run_claim.acquire(self.run,self.runtime,m)
  m['status']='running'
  with self.assertRaisesRegex(ValueError,'cannot restart'):run_claim.acquire(self.run,self.runtime,m)
 def test_raw_reassembly_rejects_changed_metric(self):
  report={'measurement_context':self.fixture.binding,'run_id':'owned-test-run','run_hash':rig.read(self.run/'manifest.json')['run_hash'],'throughput':100}
  changed=dict(report,throughput=1000)
  with patch('assemble_run.assemble_run',return_value=report):
   run_claim.verify_report(self.root,report)
   with self.assertRaisesRegex(ValueError,'raw run reassembly'):run_claim.verify_report(self.root,changed)
 def test_valid_partial_failure_consumes_slot_without_metrics(self):
  m=rig.read(self.run/'manifest.json');m.update(status='failed',launch_journal_version=1);rig.write(self.run/'manifest.json',m)
  rig.write(self.run/'owner.json',owner(1));rig.write(self.run/'supervisor.json',owner(2))
  j=launch_journal.initialize(self.run,m,self.runtime,owner(1),owner(2));j['entries'][0].update(state='spawned',identity=owner(3));j.update(terminal=True,status='failed')
  rig.write(self.run/'launch-journal.json',j);rig.write(self.run/'processes.json',[owner(3)]);receipt(self.run,j)
  value=ledger.record_failure(self.root,self.run)
  self.assertNotIn('metrics',value['failure'])
  with self.assertRaisesRegex(ValueError,'terminal'):ledger.intent(self.root,'calibration')
if __name__=='__main__':unittest.main()
