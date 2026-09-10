import json,sys
from pathlib import Path
from unittest.mock import patch
import test_export_validation as base
from rig import read,write,sha
from catalog import digest
from export_validation import export
from scenario_checker_identity import closure
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'rig'))
from build_required_target import build
class StagedExportTests(base.ExportTests):
 def setUp(self):
  super().setUp();self.runtime=read(self.root/'runtime.json')
  p=self.root/'preset-tools/driver.py';p.parent.mkdir();p.write_text('from native_window import find\n')
  self.runtime['stage_files'].append({'target':'preset-tools/driver.py','source':'/missing-original/driver.py','sha256':sha(p)})
  self.manifest['run_manifest']['staged_inputs'].append({'target':'preset-tools/driver.py','sha256':sha(p)})
  for name,data in [('native_window','from nested_guard import guard\n'),('nested_guard','VALUE=1\n')]:
   f=self.root/'tool-sources/tools/rig'/(name+'.py');f.write_text(data);self.manifest['run_manifest']['runtime_tools']['tools/rig/'+name+'.py']=sha(f)
  self.persist()
 def persist(self):
  write(self.root/'runtime.json',self.runtime);self.manifest['runtime_hash']=digest(self.runtime);write(self.root/'manifest.json',self.manifest)
 def test_export_uses_retained_script_without_origin(self):
  record=export(self.root,'mods/candidate.jar',[],'controlled');sources=record['run_manifest']['scenario_checker_sources']
  self.assertIn('staged/preset-tools/driver.py',sources);self.assertIn('tools/rig/nested_guard.py',sources)
 def test_retained_script_changed_rejects(self):
  (self.root/'preset-tools/driver.py').write_text('# changed')
  with self.assertRaises(ValueError):export(self.root,'mods/candidate.jar',[],'controlled')
 def test_original_staged_declaration_disagreement_rejects(self):
  self.manifest['run_manifest']['staged_inputs'][-1]['sha256']='0'*64;write(self.root/'manifest.json',self.manifest)
  with self.assertRaisesRegex(ValueError,'retained settings stage bindings changed|original staged Python declaration differs'):export(self.root,'mods/candidate.jar',[],'controlled')
 def test_missing_original_transitive_helper_rejects(self):
  (self.root/'tool-sources/tools/rig/nested_guard.py').unlink()
  with self.assertRaisesRegex(ValueError,'retained runtime tool sources changed'):export(self.root,'mods/candidate.jar',[],'controlled')
 def test_full_target_export_match_and_changed_helper_mismatch(self):
  repo=self.root/'tool-sources';profile=read(self.root/'profile.json');canonical=repo/'config/compatibility/profiles/controlled.json';canonical.parent.mkdir(parents=True);write(canonical,profile)
  out=repo/'fabric/build/libs/lod-server-support-fabric.jar';out.parent.mkdir(parents=True);out.write_bytes((self.root/'mods/candidate.jar').read_bytes())
  intended=json.loads(json.dumps(self.runtime))
  for row in intended['stage_files']:row['source']=str(self.root/row['target'])
  path=self.root/'intended.json';write(path,intended)
  record=export(self.root,'mods/candidate.jar',[],'controlled')
  with patch('catalog.validate_profile'):
   target=build(repo,self.root/'profile.json',self.root/'scenario.json',path,'mods/candidate.jar',[])
  for key,value in target.items():
   self.assertEqual(value,record['profile_hash'] if key=='profile_hash' else record['run_manifest'][key],key)
  # Original evidence remains unchanged; a separate intended current repo helper differs.
  (repo/'tools/rig/nested_guard.py').write_text('VALUE=2\n')
  with patch('catalog.validate_profile'):
   changed=build(repo,self.root/'profile.json',self.root/'scenario.json',path,'mods/candidate.jar',[])
  self.assertNotEqual(changed['scenario_checker_sha256'],target['scenario_checker_sha256'])
