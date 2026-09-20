"""Exact Loom named C2ME gate allowance; never permits dev jars on native routes."""
import hashlib,json,zipfile
from pathlib import Path

def allowed(root,profile,scenario,runtime,candidate_target,fixture_targets):
 root=Path(root).resolve()
 if scenario.get('id')!='c2me-save-read' or scenario.get('execution_route')!='server-gametest':return set()
 if profile.get('line')!='1.21.11' or profile.get('platform')!='fabric' or profile.get('mapping_namespace')!='named' or profile.get('id') not in ('mc12111-c2me-a-named-gametest','mc12111-c2me-b-named-gametest','mc12111-c2me-a-named-gametest-v2','mc12111-c2me-b-named-gametest-v2'):raise ValueError('exact named C2ME profile required')
 canonical=json.loads((root/'tools/rig/scenarios/c2me-save-read.json').read_text())
 if scenario!=canonical or scenario.get('requires_handshake') is not False or scenario.get('required_test_count')!=3:raise ValueError('exact canonical three-case native save/read scenario required')
 required={'artifacts/lss-gametest-named.jar','artifacts/lss-dev-test-support.jar'}
 if not required<=set(fixture_targets) or candidate_target!='artifacts/lss-main-named.jar':raise ValueError('named C2ME candidate and test-support roles required')
 rows={r['target']:r for r in runtime['stage_files']};candidate=root/'fabric/build/devlibs/lod-server-support-fabric-dev.jar'
 from named_project_component import component,no_project_vendor_artifacts
 origins=runtime.get('named_project_components',{})
 if profile['id'].endswith('-v2'):
  if set(origins)!={'main','common'}:raise ValueError('both explicit named project components required')
  checksum=component(root,origins['main'],rows,'main');common=component(root,origins['common'],rows,'common')
  no_project_vendor_artifacts(profile,{checksum,common},runtime.get('cache',{}))
 else:
  if origins:raise ValueError('project component roles require versioned profile')
  if candidate.is_symlink() or not candidate.is_file() or Path(rows[candidate_target]['source'])!=candidate:raise ValueError('only explicit current Loom named output path is allowed')
  checksum=hashlib.sha256(candidate.read_bytes()).hexdigest()
  if rows[candidate_target]['sha256']!=checksum:raise ValueError('named candidate bytes differ')
 with zipfile.ZipFile(candidate) as jar:
  metadata=json.loads(jar.read('fabric.mod.json'))
  if metadata.get('id')!='lss' or 'fabricloader' not in metadata.get('depends',{}) or metadata['depends'].get('minecraft')!='1.21.11':raise ValueError('named candidate native loader/game identity differs')
 with zipfile.ZipFile(rows['artifacts/lss-gametest-named.jar']['source']) as jar:
  metadata=json.loads(jar.read('fabric.mod.json'))
  entries=metadata.get('entrypoints',{}).get('fabric-gametest',[])
  if metadata.get('id')!='lss-test' or 'dev.vox.lss.test.SerializerParityGameTests' not in entries:raise ValueError('actual serializer game-test fixture required')
 return {checksum,common} if origins else {checksum}
