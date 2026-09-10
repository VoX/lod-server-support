"""Declared scenario checker/driver closure; unrelated tool files are excluded."""
import ast,hashlib
from pathlib import Path
ROUTES={'client-ui':['check_client_ui.py','client_ui_steps.py','finalize_client_ui.py'],'native-server-smoke':['check_server_smoke_report.py'],'server-gametest':[], 'client-ui-no-consumer':['check_client_ui.py','client_ui_steps.py','finalize_client_ui.py'],'source-prefill':['check_prefill.py'],'source-seed':['check_source_seed.py']}
CHECKERS={'export-lifecycle':['check_export_lifecycle.py'],'client-ui':['check_client_ui.py','client_ui_steps.py','finalize_client_ui.py'],'elytra':['check_elytra_run.py'],'concurrent-sources':['check_source_run.py'],'receive-lifecycle':['check_receive_run.py'],'send-admission':['check_send_admission_run.py'],'xaero-map':['check_xaero_map_run.py'],'seated-draw':['check_seated_run.py'],'folia-regions':['check_regions.py'],'client-ui-no-consumer':['check_client_ui.py','client_ui_steps.py','finalize_client_ui.py']}

def closure(repo,scenario,runtime,source_repo=None,*,staged_reader=None):
 repo=Path(repo).resolve();source_repo=Path(source_repo).resolve() if source_repo else repo;roots=['tools/rig/proof.py','tools/rig/rig.py','tools/rig/review_state.py','tools/rig/rig','scripts/lib/harness-lock.sh','scripts/lib/owned-process.py']
 route=scenario.get('execution_route');checker=scenario.get('checker')
 if route is not None and route not in ROUTES:raise ValueError('undeclared scenario execution route')
 if checker is not None and checker not in CHECKERS:raise ValueError('undeclared scenario checker')
 roots+=['tools/rig/'+name for name in ROUTES.get(route,[])+CHECKERS.get(checker,[])]
 if scenario.get('id')=='ui-apply':roots+=['tools/rig/check_client_ui.py','tools/rig/client_ui_steps.py','tools/rig/finalize_client_ui.py']
 if scenario.get('id')=='elytra':roots.append('tools/rig/check_elytra_run.py')
 # Bind actual maintained producers invoked by the recipe, including owned wrappers.
 for launch in runtime.get('launches',[]):
  for token in launch.get('argv',[]):
   if token.endswith('.py') and token.startswith(str(source_repo)+'/tools/'):
    roots.append(str(Path(token).relative_to(source_repo)))
 for row in runtime.get('stage_files',[]):
  p=Path(row['source'])
  if p.suffix=='.py' and p.is_relative_to(source_repo/'tools'):roots.append(str(p.relative_to(source_repo)))
 staged={};seen=set()
 for row in runtime.get('stage_files',[]):
  target=row['target']
  if target in seen:raise ValueError('duplicate staged target')
  seen.add(target)
  if not (target.endswith('.py') or Path(row['source']).suffix=='.py'):continue
  if not target or target.startswith('/') or any(part in ('','.','..') for part in target.split('/')) or '\\' in target:
   raise ValueError('unsafe staged Python target')
  if staged_reader is None:raise ValueError('staged Python byte reader required')
  data=staged_reader(target)
  if not isinstance(data,bytes) or hashlib.sha256(data).hexdigest()!=row['sha256']:
   raise ValueError('staged Python source changed: '+target)
  staged[target]=data
 pending=list(roots);result={'staged/'+target:hashlib.sha256(data).hexdigest() for target,data in staged.items()}
 # Parse verified script bytes, never import/execute or read external origin paths.
 for data in staged.values():
  for node in ast.walk(ast.parse(data)):
   modules=[node.module] if isinstance(node,ast.ImportFrom) else [a.name for a in node.names] if isinstance(node,ast.Import) else []
   for module in modules:
    if not module:continue
    for directory in ('tools/rig','tools/compat'):
     local=directory+'/'+module.replace('.','/')+'.py'
     if (repo/local).is_file():pending.append(local);break
 while pending:
  name=pending.pop()
  if name in result:continue
  path=repo/name
  if path.is_symlink() or not path.is_file():raise ValueError('missing scenario dependency: '+name)
  data=path.read_bytes();result[name]=hashlib.sha256(data).hexdigest()
  # Follow common dispatcher dependencies too: ownership, immutable-tree and
  # toolchain verification affect acceptance even without a scenario checker.
  # Only explicitly known scenario branches may be omitted; selected branches
  # are independently rooted above. New common imports remain included.
  branch_modules={Path(p).stem for group in (*ROUTES.values(),*CHECKERS.values()) for p in group}
  dispatcher=name in ('tools/rig/proof.py','tools/rig/rig.py')
  if path.suffix!='.py':continue
  for node in ast.walk(ast.parse(data)):
   modules=[node.module] if isinstance(node,ast.ImportFrom) else [a.name for a in node.names] if isinstance(node,ast.Import) else []
   for module in modules:
    if not module:continue
    if dispatcher and module in branch_modules:continue
    for directory in ('tools/rig','tools/compat'):
     local=directory+'/'+module.replace('.','/')+'.py'
     if (repo/local).is_file():pending.append(local);break
 return dict(sorted(result.items()))
