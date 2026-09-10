import copy,unittest
from required_row_match import matches,evaluate,FIELDS
class Targets(unittest.TestCase):
 def setUp(self):
  self.target={k:'a'*64 for k in FIELDS};self.target.update(runtime_settings_version=1,scenario_version=2,candidate_target='client/mods/lss.jar',fixture_artifacts={'client/mods/fixture.jar':'b'*64},candidate_artifacts={'client/mods/lss.jar':'a'*64})
  self.row=dict(profile_id='p',scenario_id='ui-apply',acceptance_target=self.target)
  self.record=dict(profile_id='p',profile_hash='a'*64,scenario='ui-apply',result='pass',timestamp='2026-09-10T00:00:00Z',run_id='one',run_manifest={k:v for k,v in self.target.items() if k!='profile_hash'})
 def test_exact(self):self.assertTrue(matches(self.row,self.record));self.assertEqual('passed',evaluate(self.row,[self.record])['status'])
 def test_missing(self):self.row.pop('acceptance_target');self.assertFalse(matches(self.row,self.record));self.assertEqual('unverified',evaluate(self.row,[self.record])['status'])
 def test_each_identity_axis(self):
  for key in self.target:
   with self.subTest(key=key):
    r=copy.deepcopy(self.record)
    if key=='profile_hash':r[key]='c'*64
    else:r['run_manifest'][key]={'new':'c'*64} if key=='fixture_artifacts' else 1 if key=='scenario_version' else 'c'*64
    self.assertFalse(matches(self.row,r))
 def test_newer_failure(self):
  r=copy.deepcopy(self.record);r.update(result='fail',timestamp='2026-09-10T00:01:00Z');self.assertEqual('unverified',evaluate(self.row,[self.record,r])['status'])
 def test_new_candidate_cannot_borrow_old_pass(self):
  self.row=copy.deepcopy(self.row);self.row['acceptance_target']['candidate_sha256']='d'*64;self.row['acceptance_target']['candidate_artifacts']['client/mods/lss.jar']='d'*64;self.assertEqual('unverified',evaluate(self.row,[self.record])['status'])
 def test_new_candidate_failure_cannot_borrow_old_pass(self):
  r=copy.deepcopy(self.record);r['run_manifest']['candidate_sha256']='d'*64;r['run_manifest']['candidate_artifacts']['client/mods/lss.jar']='d'*64;r.update(result='fail',timestamp='2026-09-10T00:01:00Z');self.row=copy.deepcopy(self.row);self.row['acceptance_target']['candidate_sha256']='d'*64;self.row['acceptance_target']['candidate_artifacts']['client/mods/lss.jar']='d'*64;self.assertEqual('unverified',evaluate(self.row,[self.record,r])['status'])
 def test_tie_failure_not_hidden(self):
  r=copy.deepcopy(self.record);r.update(result='fail',run_id='aaa');self.assertEqual('unverified',evaluate(self.row,[r,self.record])['status'])
 def test_retained_old_record_without_scenario_identity_unverified(self):
  self.record['run_manifest'].pop('scenario_hash');self.assertEqual('unverified',evaluate(self.row,[self.record])['status'])
 def test_secondary_server_candidate_changed(self):
  self.target['candidate_artifacts']['server/mods/lss.jar']='e'*64
  self.record['run_manifest']=copy.deepcopy(self.record['run_manifest']);self.record['run_manifest']['candidate_artifacts']['server/mods/lss.jar']='f'*64
  self.assertFalse(matches(self.row,self.record))
 def test_native_server_binding_stays_mandatory(self):
  self.row.update(scenario_id='server-smoke',scenario_version=2,required_server={'id':'paper','profile_hash':'f'*64,'platform':'paper','candidate_target':'client/mods/lss.jar'})
  self.record.update(scenario='server-smoke');self.record['run_manifest'].update(execution_route='native-server-smoke',server_profile_id='paper',server_profile_hash='f'*64,server_platform='paper',server_candidate_target='client/mods/lss.jar')
  self.assertTrue(matches(self.row,self.record));self.record['run_manifest']['server_platform']='neoforge';self.assertFalse(matches(self.row,self.record))
if __name__=='__main__':unittest.main()
