#!/usr/bin/env python3
"""Derive a target only from explicit current native recipe and selected artifacts."""
import argparse,hashlib,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from candidate_identity import candidate_bindings

def build(root,profile_path,scenario_path,runtime_path,candidate_target,fixture_targets):
 root=Path(root).resolve();sys.path[:0]=[str(root/'tools/compat'),str(root/'tools/rig')]
 from catalog import digest,validate_profile
 from scenario_checker_identity import closure
 profile=json.loads(Path(profile_path).read_text());scenario=json.loads(Path(scenario_path).read_text());runtime=json.loads(Path(runtime_path).read_text())
 canonical=root/'config/compatibility/profiles'/(profile['id']+'.json')
 if not canonical.is_file() or json.loads(canonical.read_text())!=profile:raise ValueError('explicit profile must equal owning maintained profile')
 validate_profile(profile,allow_unresolved_ranges=True)
 staged={r['target']:r for r in runtime['stage_files']}
 def artifact(target):
  row=staged[target];path=Path(row['source'])
  if path.is_symlink() or not path.is_file():raise ValueError('regular selected artifact required')
  actual=hashlib.sha256(path.read_bytes()).hexdigest()
  if actual!=row['sha256']:raise ValueError('recipe artifact changed: '+target)
  return actual
 candidates=candidate_bindings(runtime,artifact)
 if candidate_target not in candidates:raise ValueError('selected primary candidate not declared native product')
 # Require candidate role bytes to agree with the explicit current owning build outputs.
 available={hashlib.sha256(p.read_bytes()).hexdigest() for platform in ('fabric','paper','neoforge') for p in (root/platform/'build/libs').glob('lod-server-support-'+platform+'.jar')}
 from named_gametest_target import allowed
 available.update(allowed(root,profile,scenario,runtime,candidate_target,fixture_targets))
 if not set(candidates.values())<=available:raise ValueError('recipe candidates differ from current explicit build artifacts')
 checker_names=('proof.py','check_source_seed.py','check_regions.py','check_workload.py','performance.py','metrics.py','measure.py')
 refs=([('server',runtime['server_profile'])] if runtime.get('server_profile') else [])+[(r['role'],r) for r in runtime.get('client_profiles',[])]
 participants={}
 for role,reference in refs:
  locked=json.loads(Path(reference['path']).read_text())
  if role in participants or locked['id']!=reference['id'] or digest(locked)!=reference['profile_hash']:raise ValueError('participant identity differs')
  participants[role]={'id':locked['id'],'profile_hash':digest(locked)}
 return dict(participant_profiles=participants,profile_hash=digest(profile),scenario_version=scenario.get('version',1),scenario_hash=digest(scenario),scenario_checker_sha256=digest(closure(root,scenario,runtime)),candidate_sha256=candidates[candidate_target],candidate_target=candidate_target,candidate_artifacts=candidates,fixture_artifacts={target:artifact(target) for target in fixture_targets})
if __name__=='__main__':
 p=argparse.ArgumentParser()
 for field in ('root','profile','scenario','runtime','candidate-target','output'):p.add_argument('--'+field,required=True)
 p.add_argument('--fixture-target',action='append',default=[]);a=p.parse_args();out=Path(a.output)
 if out.exists():raise ValueError('refuse existing target output')
 value=build(a.root,a.profile,a.scenario,a.runtime,a.candidate_target,a.fixture_target);out.write_text(json.dumps(value,indent=2)+'\n')
