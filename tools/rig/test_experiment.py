import copy
import tempfile
import unittest
from pathlib import Path
import hashlib
from unittest.mock import patch
import rig,run_claim
import experiment as ledger
from test_performance import experiment,run,FIELDS

class ExperimentLedgerTests(unittest.TestCase):
 def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)/'experiment';e=experiment();self.registration=ledger.init(self.root,{**{field:hashlib.sha256(e[field].encode()).hexdigest() for field in FIELDS},'arms':{arm:{'source_tree':hashlib.sha1(arm.encode()).hexdigest(),'artifact_hashes':{key:hashlib.sha256(value.encode()).hexdigest() for key,value in identity['artifact_hashes'].items()}} for arm,identity in e['arms'].items()}})
 def prepare_raw_claim(self,report):
  # Sequencing tests substitute only raw assembly; claim/ownership validation is real.
  if not hasattr(self,'raw_reports'):
   self.raw_reports={};mock=patch('assemble_run.assemble_run',side_effect=lambda root:copy.deepcopy(self.raw_reports[str(root)]));mock.start();self.addCleanup(mock.stop)
  root=self.root.parent/report['run_id'];root.mkdir();runtime={'measurement_context':report['measurement_context'],'measurement_experiment_root':str(self.root.resolve()),'launches':[]}
  rm={'runtime_hash':rig.digest(runtime)};manifest={'run_id':report['run_id'],'run_hash':rig.digest(rm),'runtime_hash':rig.digest(runtime),'run_manifest':rm,'status':'created'}
  rig.write(root/'runtime.json',runtime);rig.write(root/'manifest.json',manifest);run_claim.acquire(root,runtime,manifest)
  report['run_hash']=manifest['run_hash'];rig.write(root/'processes.json',[]);rig.write(root/'supervisor.json',{'pid':2147483000,'start':'100','boot':'synthetic-dead'})
  self.raw_reports[str(root)]=copy.deepcopy(report)
 def tearDown(self):self.tmp.cleanup()
 def add(self,phase,identifier):
  binding=ledger.intent(self.root,phase);report=run(identifier);report.update({field:self.registration[field] for field in FIELDS});report['run_hash']='hash-'+identifier;report['measurement_context']=binding;report['artifact_identity']=self.registration['arms'][binding['arm']];self.prepare_raw_claim(report);ledger.record(self.root,report);return report
 def calibrate(self):
  for index in range(3):self.add('calibration','cal'+str(index))
  ledger.freeze(self.root)
 def test_requires_calibration_and_exact_fixed_order(self):
  with self.assertRaises(ValueError):ledger.intent(self.root,'measured')
  self.calibrate();arms=[]
  for index in range(6):arms.append(self.add('measured','run'+str(index))['measurement_context']['arm'])
  self.assertEqual(list(ledger.ORDER),arms)
  self.assertEqual('passed',ledger.evaluate(self.root)['result']['status'])
  with self.assertRaises(ValueError):ledger.intent(self.root,'measured')
 def test_recorded_failure_is_not_replaceable(self):
  binding=ledger.intent(self.root,'calibration');report=run('bad');report.update({field:self.registration[field] for field in FIELDS});report.update(run_hash='bad-hash',measurement_context=binding,artifact_identity=self.registration['arms']['baseline']);report['oracle']=[]
  self.prepare_raw_claim(report);ledger.record(self.root,report)
  with self.assertRaises((ValueError,FileExistsError)):ledger.record(self.root,report)
  self.add('calibration','cal1');self.add('calibration','cal2')
  with self.assertRaises(ValueError):ledger.freeze(self.root)
 def test_changed_calibration_floor_rejected(self):
  self.calibrate();path=self.root/'frozen.json';value=ledger.read(path);value['absolute_floors']['server']['peak_rss_bytes']=1000;path.write_text(__import__('json').dumps(value))
  for index in range(6):self.add('measured','run'+str(index))
  with self.assertRaisesRegex(ValueError,'floors changed'):ledger.evaluate(self.root)
 def test_wrong_arm_and_changed_runtime_intent_rejected(self):
  binding=ledger.intent(self.root,'calibration');report=run('bad');report.update({field:self.registration[field] for field in FIELDS});report.update(run_hash='hash',measurement_context=copy.deepcopy(binding),artifact_identity=self.registration['arms']['candidate'])
  with self.assertRaisesRegex(ValueError,'arm artifact'):ledger.record(self.root,report)
  report['artifact_identity']=self.registration['arms']['baseline'];report['measurement_context']['nonce']='replacement'
  with self.assertRaisesRegex(ValueError,'preregistered intent'):ledger.record(self.root,report)
