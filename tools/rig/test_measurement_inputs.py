import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from measurement_inputs import fingerprints

class MeasurementFingerprintTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
  for name in ['bin/java','release','lib/server/libjvm.so']:
   path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(name.encode())
  self.runtime={'stage_files':[{'target':'candidate.jar','sha256':'a'*64},{'target':'fixture.jar','sha256':'b'*64},{'target':'dependency.jar','sha256':'c'*64}], 'launches':[{'id':'server','argv':[str(self.root/'bin/java'),'-Xmx2G','-jar','candidate.jar']}], 'generated_files':{'config.json':'{}'},'backend':'linux-headless'}
  self.patch=patch('measurement_inputs.hardware',return_value={'stable':'host'});self.patch.start()
 def tearDown(self):self.patch.stop();self.tmp.cleanup()
 def calculate(self,runtime=None):return fingerprints(runtime or self.runtime,{'paper':'candidate.jar'},['fixture.jar'],{})
 def test_arm_artifact_change_does_not_change_shared_workload(self):
  baseline=self.calculate();candidate=copy.deepcopy(self.runtime);candidate['stage_files'][0]['sha256']='d'*64
  self.assertEqual(baseline,self.calculate(candidate))
 def test_fixture_and_jvm_changes_cannot_hide_in_shared_identity(self):
  baseline=self.calculate();changed=copy.deepcopy(self.runtime);changed['stage_files'][1]['sha256']='d'*64
  self.assertNotEqual(baseline['fixture_hash'],self.calculate(changed)['fixture_hash']);self.assertNotEqual(baseline['workload_hash'],self.calculate(changed)['workload_hash'])
  changed=copy.deepcopy(self.runtime);changed['launches'][0]['argv'][1]='-Xmx4G'
  self.assertNotEqual(baseline['jvm_hash'],self.calculate(changed)['jvm_hash'])
  (self.root/'lib/server/libjvm.so').write_bytes(b'changed JVM')
  self.assertNotEqual(baseline['jvm_hash'],self.calculate()['jvm_hash'])
 def test_production_cannot_be_classified_as_identical_fixture(self):
  with self.assertRaises(ValueError):fingerprints(self.runtime,{'paper':'candidate.jar'},['candidate.jar'],{})
