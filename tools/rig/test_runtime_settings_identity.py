import copy,json,tempfile,unittest
from pathlib import Path
from runtime_settings_identity import identity
from required_row_match import matches

class SettingsIdentityTests(unittest.TestCase):
 def setUp(self):
  self.runtime={'stage_files':[{'source':'/cache-a/mod.jar','target':'mods/mod.jar','sha256':'a'*64}],
    'generated_files':{'client/config/options.json':'{"fog":true}'},'backend':'linux-headless','private_display':True,'require_gpu':True,
    'gpu_environment':{'GALLIUM_DRIVER':'d3d12'},'world_digest':'b'*64,'launches':[{'id':'client','argv':['/jdk/bin/java','-Xmx2G','-Dfeature=true','-cp','/cache-a/mod.jar','Main'],'cwd':'client'}],
    'cache':{'a'*64:'/cache-a/mod.jar'},'client_endpoint':'127.0.0.1:25575','bind_endpoint':'127.0.0.1:25575'}
  self.target={'profile_hash':'c'*64,'scenario_hash':'d'*64,'scenario_checker_sha256':'e'*64,'candidate_sha256':'a'*64,'scenario_version':1,'candidate_target':'mods/mod.jar','candidate_artifacts':{'mods/mod.jar':'a'*64},'fixture_artifacts':{},**identity(self.runtime)}
  self.row={'profile_id':'profile','scenario_id':'controlled','acceptance_target':self.target};self.record={'profile_id':'profile','profile_hash':'c'*64,'scenario':'controlled','result':'pass','run_manifest':copy.deepcopy(self.target)}
 def test_unchanged_settings_match(self):self.assertTrue(matches(self.row,self.record))
 def test_config_world_backend_gpu_jvm_and_launch_change_cannot_reuse_pass(self):
  mutations=[lambda r:r['generated_files'].update({'client/config/options.json':'{"fog":false}'}),lambda r:r.update(world_digest='f'*64),lambda r:r.update(backend='isolated-linux-prism'),lambda r:r.update(require_gpu=False),lambda r:r['launches'][0]['argv'].append('-Dfeature2=true'),lambda r:r['launches'][0]['argv'].__setitem__(1,'-Xmx3G'),lambda r:r.update(client_endpoint='127.0.0.1:25576')]
  for mutate in mutations:
   with self.subTest(mutation=mutate):
    changed=copy.deepcopy(self.runtime);mutate(changed);self.row['acceptance_target']={**self.target,**identity(changed)};self.assertFalse(matches(self.row,self.record))
 def test_cache_location_and_ephemeral_measurement_intent_do_not_change_identity(self):
  r=copy.deepcopy(self.runtime);r['cache']={'a'*64:'/cache-b/mod.jar'};r['stage_files'][0]['source']='/cache-b/mod.jar';r['launches'][0]['argv'][4]='/cache-b/mod.jar';r['measurement_context']={'nonce':'other'}
  self.assertEqual(identity(self.runtime),identity(r))
 def test_staged_configuration_change_is_bound(self):
  r=copy.deepcopy(self.runtime);r['stage_files'].append({'source':'/cache/options','target':'client/options.txt','sha256':'1'*64});first=identity(r);r['stage_files'][-1]['sha256']='2'*64;self.assertNotEqual(first,identity(r))
 def test_retained_stage_bindings_cannot_be_substituted(self):
  with self.assertRaises(ValueError):identity(self.runtime,[{'target':'mods/mod.jar','sha256':'0'*64}])
 def test_old_records_and_field_stripping_fail_closed(self):
  del self.record['run_manifest']['runtime_settings_sha256'];self.assertFalse(matches(self.row,self.record));del self.target['runtime_settings_sha256'];self.assertFalse(matches(self.row,self.record))
 def test_owned_display_auth_and_lock_identity_are_excluded_but_behavior_is_not(self):
  changed=copy.deepcopy(self.runtime);changed['gpu_environment'].update(DISPLAY=':199',XAUTHORITY='/private/auth',LSS_HARNESS_LOCK_FD='31',ACCESS_TOKEN='private-auth-identity')
  changed['launches'][0]['argv'].extend(['-Dlss.rig.runId=unique','-Dlss.rig.ownerPid=123'])
  self.assertEqual(identity(self.runtime),identity(changed))
  changed['launches'][0]['argv'].append('-Dfeature.authorizationRequired=false')
  self.assertNotEqual(identity(self.runtime),identity(changed))
 def test_javaagent_and_staged_world_mutations_are_bound(self):
  for target in ('server/world/r.0.0.mca','server/world/level.dat'):
   changed=copy.deepcopy(self.runtime);changed['stage_files'].append({'target':target,'source':'/world/input','sha256':'1'*64});first=identity(changed);changed['stage_files'][-1]['sha256']='2'*64;self.assertNotEqual(first,identity(changed))
  changed=copy.deepcopy(self.runtime);changed['launches'][0]['argv'].append('-javaagent:{run}/fixture.jar');self.assertNotEqual(identity(self.runtime),identity(changed))
 def test_intended_staged_config_bytes_must_match_declared_hash(self):
  r=copy.deepcopy(self.runtime);r['stage_files'].append({'source':'/config','target':'server/config.json','sha256':'1'*64})
  with self.assertRaisesRegex(ValueError,'source bytes changed'):identity(r,artifact_checker=lambda target:'2'*64)
 def test_only_digest_and_schema_are_exported(self):
  self.assertEqual({'runtime_settings_version','runtime_settings_sha256'},set(identity(self.runtime)))
if __name__=='__main__':unittest.main()
