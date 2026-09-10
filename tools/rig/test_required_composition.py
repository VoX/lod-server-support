import copy,unittest
import test_required_target as examples
from required_row_match import matches
class ComposedTargets(unittest.TestCase):
 def test_required_native_participant_cannot_be_omitted_or_replaced(self):
  fixture=examples.Targets();fixture.setUp();row=fixture.row;record=fixture.record
  participants={'elytra-target':{'id':'native-target','profile_hash':'e'*64}}
  row['required_participants']=copy.deepcopy(participants)
  self.assertFalse(matches(row,record))
  record['run_manifest']['participant_profiles']=copy.deepcopy(participants)
  row['acceptance_target']['participant_profiles']=copy.deepcopy(participants)
  self.assertTrue(matches(row,record))
  for field in ('id','profile_hash'):
   changed=copy.deepcopy(record);changed['run_manifest']['participant_profiles']['elytra-target'][field]='wrong'
   self.assertFalse(matches(row,changed))
 def test_extra_unexpected_participant_changes_exact_target(self):
  fixture=examples.Targets();fixture.setUp();fixture.row['acceptance_target']['participant_profiles']={}
  fixture.record['run_manifest']['participant_profiles']={'foreign':{'id':'foreign','profile_hash':'f'*64}}
  self.assertFalse(matches(fixture.row,fixture.record))

class UiRouteComposition(unittest.TestCase):
 def test_only_three_sibling_neo_rows_lack_consumer(self):
  import json
  from pathlib import Path
  rows=json.loads(Path(__file__).with_name('required-matrix.json').read_text())['rows']
  absent={r['id'] for r in rows if r['scenario_id']=='client-ui-no-consumer'}
  self.assertEqual({f'sibling-{line}-neoforge--ui-apply' for line in ('1.21.10','1.21.11','26.1')},absent)
  self.assertEqual('ui-apply',next(r for r in rows if r['id']=='sibling-26.2-neoforge--ui-apply')['scenario_id'])
