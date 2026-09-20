import copy,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from server_smoke_identity import validate_binding
from required_row_match import matches
from catalog import digest
class Identity(unittest.TestCase):
 def setUp(self):
  self.profile={'id':'paper','platform':'paper'};target='server/plugins/lod-server-support-paper.jar'
  self.row={'profile_id':'client','scenario_id':'server-smoke','scenario_version':2,'required_server':dict(id='paper',profile_hash=digest(self.profile),platform='paper',candidate_target=target)}
  self.record={'result':'pass','profile_id':'client','scenario':'server-smoke','run_manifest':dict(execution_route='native-server-smoke',scenario_version=2,server_profile_id='paper',server_profile_hash=digest(self.profile),server_platform='paper',candidate_target=target,server_candidate_target=target)}
  for key,name in [('client_candidate_artifacts','lod-server-support-fabric.jar'),('client_fixture_artifacts','lss-server-smoke-client.jar')]:self.record['run_manifest'][key]={'smoke-'+phase+'/mods/'+name:'a'*64 for phase in ('first','second')}
  self.record['profile_hash']='a'*64
  values=self.record['run_manifest'];values.update(scenario_hash='b'*64,scenario_checker_sha256='c'*64,candidate_sha256='d'*64,fixture_artifacts={},candidate_artifacts={values['candidate_target']:'d'*64})
  values.update(runtime_settings_version=1,runtime_settings_sha256='9'*64)
  self.row['acceptance_target']={k:values[k] for k in ('scenario_hash','scenario_checker_sha256','candidate_sha256','candidate_target','candidate_artifacts','fixture_artifacts','scenario_version','runtime_settings_version','runtime_settings_sha256')}
  self.row['acceptance_target']['profile_hash']='a'*64
 def validate(self):
  def require(ok,message):
   if not ok:raise ValueError(message)
  validate_binding(self.record,{'paper':self.profile},require)
 def test_correct_server_bound(self):self.validate();self.assertTrue(matches(self.row,self.record))
 def test_client_candidate_cannot_close_server(self):
  self.record['run_manifest']['candidate_target']='smoke-first/mods/lod-server-support-fabric.jar'
  with self.assertRaises(ValueError):self.validate()
  self.assertFalse(matches(self.row,self.record))
 def test_platform_cross_credit_rejected(self):
  self.record['run_manifest']['server_platform']='neoforge'
  with self.assertRaises(ValueError):self.validate()
  self.assertFalse(matches(self.row,self.record))
 def test_changed_profile_rejected(self):
  self.record['run_manifest']['server_profile_hash']='b'*64
  with self.assertRaises(ValueError):self.validate()
  self.assertFalse(matches(self.row,self.record))
 def test_missing_second_fresh_client_rejected(self):
  self.record['run_manifest']['client_candidate_artifacts'].pop('smoke-second/mods/lod-server-support-fabric.jar')
  with self.assertRaises(ValueError):self.validate()
 def test_old_scenario_cannot_close_row(self):
  self.record['run_manifest']['scenario_version']=1
  with self.assertRaises(ValueError):self.validate()
  self.assertFalse(matches(self.row,self.record))
if __name__=='__main__':unittest.main()
