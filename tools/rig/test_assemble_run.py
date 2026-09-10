import json
import tempfile
import unittest
from pathlib import Path
from assemble_run import assemble_run
from rig import digest

class RunAssemblyInputTests(unittest.TestCase):
 def base(self,root):
  (root/'evidence').mkdir();runtime={};identity={'run_id':'test','run_hash':'run','profile_hash':'profile','scenario_hash':'scenario'}
  manifest={**identity,'runtime_hash':digest(runtime),'run_manifest':{'staged_inputs':[]}}
  values={'manifest.json':manifest,'runtime.json':runtime,'evidence/result.json':{**identity,'status':'passed','cleanup':'complete'},'processes.json':[],'supervisor.json':None}
  for name,value in values.items():(root/name).write_text(json.dumps(value))
  return values
 def test_live_or_failed_diagnostic_cannot_be_assembled_as_measurement(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);values=self.base(root);values['evidence/result.json']['status']='failed';(root/'evidence/result.json').write_text(json.dumps(values['evidence/result.json']))
   with self.assertRaisesRegex(ValueError,'successful collected'):assemble_run(root)
 def test_measurement_intent_cannot_be_added_after_launch(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);self.base(root)
   with self.assertRaisesRegex(ValueError,'bound before launch'):assemble_run(root)
   (root/'runtime.json').write_text(json.dumps({'measurement_context':{'phase':'measured'}}))
   with self.assertRaisesRegex(ValueError,'runtime bytes changed'):assemble_run(root)
 def test_collection_from_another_run_rejected(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);values=self.base(root);values['evidence/result.json']['run_hash']='other';(root/'evidence/result.json').write_text(json.dumps(values['evidence/result.json']))
   with self.assertRaisesRegex(ValueError,'collection identity'):assemble_run(root)
