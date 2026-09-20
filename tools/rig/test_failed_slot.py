import copy,hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import experiment as ledger

class FailedSlotTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.base=Path(self.tmp.name);self.root=self.base/'experiment';self.run=self.base/'run';(self.run/'evidence').mkdir(parents=True)
  h=lambda value:hashlib.sha256(value.encode()).hexdigest()
  self.profile={'schema_version':1,'id':'fixture-profile'};self.scenario={'schema_version':1,'id':'measured-concurrent','observe_seconds':840}
  self.artifact=h('baseline jar');self.fixture=h('fixture jar')
  registration={field:h(field) for field in ledger.FIELDS};registration['profile_hash']=ledger.digest(self.profile);registration['fixture_hash']=ledger.digest({'fixture.jar':self.fixture});registration['arms']={arm:{'source_tree':hashlib.sha1(arm.encode()).hexdigest(),'artifact_hashes':{'fabric':h(arm+' jar')}} for arm in ('baseline','candidate')}
  self.registration=ledger.init(self.root,registration);self.binding=ledger.intent(self.root,'calibration')
  metadata={key:registration[key] for key in ('hardware_hash','jvm_hash','workload_hash')};metadata.update(artifact_identity=registration['arms']['baseline'],artifact_paths={'fabric':'candidate.jar'},fixture_paths=['fixture.jar'])
  self.runtime={'world_digest':registration['world_digest'],'measurement':metadata,'measurement_context':self.binding,'measurement_experiment_root':str(self.root.resolve()),'launches':[{'id':'server','argv':['/test/jdk/bin/java']},{'id':'client-A','argv':['/test/jdk/bin/java']}]}
  self.values={'runtime.json':self.runtime,'profile.json':self.profile,'scenario.json':self.scenario,'processes.json':[{'pid':2147483601,'start':'100','boot':'fixture-dead-boot'},{'pid':2147483602,'start':'101','boot':'fixture-dead-boot'}],'supervisor.json':{'pid':2147483603,'start':'102','boot':'fixture-dead-boot'}};self.refresh()
 def tearDown(self):self.tmp.cleanup()
 def refresh(self,status='failed'):
  rm={field:ledger.digest(self.values[name]) for name,field in [('runtime.json','runtime_hash'),('profile.json','profile_hash'),('scenario.json','scenario_hash')]};rm['staged_inputs']=[{'target':'candidate.jar','sha256':self.artifact},{'target':'fixture.jar','sha256':self.fixture}]
  manifest={**{key:rm[key] for key in ('runtime_hash','profile_hash','scenario_hash')},'run_id':'owned-test-run','run_manifest':rm,'run_hash':ledger.digest(rm)}
  collected={**{key:manifest[key] for key in ('run_id','run_hash','profile_hash','scenario_hash')},'status':status,'cleanup':'complete','errors':['actual fixture failed'] if status=='failed' else []}
  self.values.update({'manifest.json':manifest,'evidence/result.json':collected})
  for name,value in self.values.items():(self.run/name).write_text(json.dumps(value))
  from run_claim import expected
  b=self.runtime['measurement_context'];ledger.create_file(self.root/f"{b['phase']}-{b['slot']}-claim.json",expected(self.run,self.runtime,manifest)) if not (self.root/f"{b['phase']}-{b['slot']}-claim.json").exists() else None
 def write(self,name,value):(self.run/name).write_text(json.dumps(value))
 def test_failed_collection_consumes_slot_without_metrics_and_is_terminal(self):
  with patch('assemble_run.assemble_run',side_effect=AssertionError('must not assemble failed run')):
   value=ledger.record_failure(self.root,self.run)
  self.assertNotIn('report',value);self.assertNotIn('metrics',value['failure']);self.assertEqual(self.binding,value['failure']['measurement_context']);self.assertTrue((self.root/'calibration-0-result.json').is_file())
  with self.assertRaises(ValueError):ledger.intent(self.root,'calibration')
  with self.assertRaises(ValueError):ledger.freeze(self.root)
  with self.assertRaises(ValueError):ledger.record_failure(self.root,self.run)
  self.assertEqual('failed',ledger.evaluate(self.root)['result']['status'])
 def test_successful_collection_with_reproduced_assembly_error_records_failure(self):
  self.refresh('passed')
  with patch('assemble_run.assemble_run',side_effect=ValueError('missing tick observations')):value=ledger.record_failure(self.root,self.run)
  self.assertEqual('report-assembly-failure',value['failure']['reason']['kind'])
 def test_valid_report_cannot_be_reclassified_as_failure(self):
  self.refresh('passed')
  with patch('assemble_run.assemble_run',return_value={}),patch.object(ledger.performance,'correctness',return_value=[]),patch.object(ledger.performance,'sample_errors',return_value=[]):
   with self.assertRaisesRegex(ValueError,'valid assembled'):ledger.record_failure(self.root,self.run)
  self.assertFalse((self.root/'calibration-0-result.json').exists())
 def test_runtime_intent_cannot_be_added_after_create(self):
  self.runtime['measurement_context']={**self.binding,'nonce':'changed'};self.write('runtime.json',self.runtime)
  with self.assertRaisesRegex(ValueError,'input identity'):ledger.record_failure(self.root,self.run)
 def test_wrong_bound_arm_rejected_even_when_manifest_recomputed(self):
  self.runtime['measurement']['artifact_identity']=self.registration['arms']['candidate'];self.refresh()
  with self.assertRaisesRegex(ValueError,'claim differs'):ledger.record_failure(self.root,self.run)
 def test_foreign_collection_and_incomplete_cleanup_rejected(self):
  for key,value in [('run_id','other'),('cleanup','incomplete')]:
   self.refresh();c=self.values['evidence/result.json'];c[key]=value;self.write('evidence/result.json',c)
   with self.assertRaises(ValueError):ledger.record_failure(self.root,self.run)
 def test_live_owner_rejected(self):
  with patch('rig.alive',return_value=True):
   with self.assertRaisesRegex(ValueError,'stopped'):ledger.record_failure(self.root,self.run)
 def test_measured_failed_slot_is_terminal_and_binds_frozen_calibration(self):
  frozen={'schema_version':1,'registration_sha256':ledger.digest(self.registration),'calibration_report_hashes':['a','b','c'],'absolute_floors':{},'frozen_ns':1}
  (self.root/'frozen.json').write_text(json.dumps(frozen))
  binding=ledger.intent(self.root,'measured');self.runtime['measurement_context']=binding;self.refresh()
  bad=dict(frozen,frozen_ns=2);(self.root/'frozen.json').write_text(json.dumps(bad))
  with self.assertRaisesRegex(ValueError,'frozen calibration'):ledger.record_failure(self.root,self.run)
  (self.root/'frozen.json').write_text(json.dumps(frozen));value=ledger.record_failure(self.root,self.run)
  self.assertEqual('measured',value['failure']['measurement_context']['phase']);self.assertTrue((self.root/'measured-0-result.json').exists())
  with self.assertRaises(ValueError):ledger.intent(self.root,'measured')
  self.assertEqual('failed',ledger.evaluate(self.root)['result']['status'])
 def test_missing_and_malformed_ownership_never_proves_cleanup(self):
  original=copy.deepcopy(self.values)
  changes=[('processes.json',[]),('supervisor.json',None),
           ('processes.json',[original['processes.json'][0]]),
           ('processes.json',[original['processes.json'][0]]*2),
           ('supervisor.json',{'pid':123,'start':'not-ticks','boot':'fixture'})]
  for name,value in changes:
   with self.subTest(name=name,value=value):
    self.values=copy.deepcopy(original);self.refresh();self.write(name,value)
    with self.assertRaisesRegex(ValueError,'owned process evidence invalid'):ledger.record_failure(self.root,self.run)
    self.assertFalse((self.root/'calibration-0-result.json').exists())
 def test_failure_filename_must_match_selected_phase_and_slot(self):
  ledger.record_failure(self.root,self.run)
  (self.root/'calibration-0-result.json').rename(self.root/'calibration-1-result.json')
  with self.assertRaisesRegex(ValueError,'filename disagrees'):ledger.evaluate(self.root)
  with self.assertRaisesRegex(ValueError,'filename disagrees'):ledger.intent(self.root,'calibration')
 def test_failed_artifact_mutation_is_attributed_without_claiming_good_bytes(self):
  # Immutable-input mutation may itself cause collection failure; intended hashes
  # still identify the consumed slot, without asserting these changed bytes match.
  (self.run/'candidate.jar').write_bytes(b'changed after launch')
  value=ledger.record_failure(self.root,self.run)
  self.assertEqual(self.registration['arms']['baseline'],value['failure']['artifact_identity'])
  self.assertIn('No immutable-byte success',value['failure']['artifact_attribution'])
 def test_recorded_failure_tampering_cannot_evaluate(self):
  ledger.record_failure(self.root,self.run);p=self.root/'calibration-0-result.json';value=ledger.read(p);value['failure']['run_hash']='changed';p.write_text(json.dumps(value))
  with self.assertRaisesRegex(ValueError,'evidence changed'):ledger.evaluate(self.root)
if __name__=='__main__':unittest.main()
