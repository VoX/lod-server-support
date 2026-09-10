import json,unittest
from pathlib import Path
from unittest.mock import patch
import test_export_validation as existing
from export_validation import export
from rig import read,write
from catalog import digest,validate_record
class ParticipantExportTest(unittest.TestCase):
 def fixture(self):
  f=existing.ExportTests();f.setUp();self.addCleanup(f.temp.cleanup)
  server={'id':'native-server','line':'1.21.1','platform':'paper'}
  (f.root/'participants').mkdir();write(f.root/'participants/server.json',server)
  manifest=read(f.root/'manifest.json');manifest['participants']=[{'role':'server','id':server['id'],'profile_hash':digest(server)}];write(f.root/'manifest.json',manifest)
  return f,server
 def test_native_server_is_available_to_final_record_validator(self):
  f,server=self.fixture();observed=[]
  def validate(record,profiles):
   observed.append(profiles);self.assertEqual(server,profiles['native-server']);validate_record(record,profiles)
  with patch('export_validation.validate_record',side_effect=validate):result=export(f.root,'mods/candidate.jar',[],'controlled')
  self.assertEqual('pass',result['result']);self.assertEqual(1,len(observed))
 def test_changed_participant_fails_before_export(self):
  f,server=self.fixture();server['platform']='fabric';write(f.root/'participants/server.json',server)
  with self.assertRaisesRegex(ValueError,'participant identity changed'):export(f.root,'mods/candidate.jar',[],'controlled')
 def test_same_id_with_conflicting_profile_is_rejected(self):
  f,server=self.fixture();server['id']='controlled';write(f.root/'participants/server.json',server)
  manifest=read(f.root/'manifest.json');manifest['participants'][0].update(id='controlled',profile_hash=digest(server));write(f.root/'manifest.json',manifest)
  with self.assertRaisesRegex(ValueError,'conflicting retained'):export(f.root,'mods/candidate.jar',[],'controlled')

 def test_dropped_retained_participant_cannot_export(self):
  f,server=self.fixture();(f.root/'participants/server.json').unlink()
  with self.assertRaises((ValueError,FileNotFoundError)):export(f.root,'mods/candidate.jar',[],'controlled')
