"""Defer only absent user visual reviews after all runtime semantics have passed."""
from pathlib import Path

def semantic_digest(proof):
 from rig import digest
 return digest({key:value for key,value in proof.items() if key!='reviews'})

def pending(proof,scenario,errors,root):
 from rig import regular,inside,sha
 if not isinstance(proof,dict):return False
 required=scenario.get('human_reviews',[])
 reviews=proof.get('reviews',{})
 artifacts=proof.get('review_artifacts',{})
 if (not isinstance(required,list) or not required or any(not isinstance(name,str) or not name for name in required)
     or len(set(required))!=len(required) or not isinstance(reviews,dict) or not isinstance(artifacts,dict)):return False
 missing=[name for name in required if name not in reviews]
 if not missing or sorted(errors)!=sorted('visual review pending/rejected: '+name for name in missing):return False
 try:
  for name in required:
   declared=artifacts[name]
   if not isinstance(declared,dict) or set(declared)!={'artifact','artifact_sha256'}:return False
   if not isinstance(declared['artifact'],str) or Path(declared['artifact']).is_absolute():return False
   artifact=regular(inside(Path(root)/'evidence',declared['artifact']))
   if not 0<artifact.stat().st_size<=16*1024*1024 or sha(artifact)!=declared['artifact_sha256']:return False
  return True
 except (KeyError,TypeError,ValueError,OSError):return False

def runtime_status(proof,scenario,errors,root):
 if not errors:return 'passed'
 return 'awaiting-review' if pending(proof,scenario,errors,root) else 'failed'

def check_frozen(proof,manifest):
 from rig import digest
 errors=[]
 if manifest.get('review_pending_proof_hash') is not None:
  if semantic_digest(proof)!=manifest['review_pending_proof_hash']:
   errors.append('runtime proof or declared visual artifact changed after cleanup')
 if manifest.get('review_proof_hash') is not None:
  if digest(proof)!=manifest['review_proof_hash']:
   errors.append('finalized user review or proof changed after acceptance')
 if manifest.get('review_failed_at') is not None:
  errors.append('visual review attempt is terminally failed')
 return errors

def ownership_errors(root,runtime):
 """Missing ownership records cannot establish that native participants are dead."""
 from rig import read,regular
 try:
  root=Path(root)
  processes=read(regular(root/'processes.json'));supervisor=read(regular(root/'supervisor.json'))
  def identity(value):
   return (isinstance(value,dict) and type(value.get('pid')) is int and value['pid']>0
       and isinstance(value.get('start'),str) and value['start'].isdigit()
       and isinstance(value.get('boot'),str) and bool(value['boot']))
  if not identity(supervisor):raise ValueError('supervisor identity absent/invalid')
  if (not isinstance(processes,list) or len(processes)<len(runtime.get('launches',[]))
      or any(not identity(value) for value in processes)):
   raise ValueError('participant process identities absent/invalid')
  if len({(p['pid'],p['start'],p['boot'])for p in processes})!=len(processes):
   raise ValueError('duplicate participant process identity')
 except (ValueError,OSError,TypeError,KeyError)as error:
  return ['owned process evidence invalid: '+str(error)]
 return []
