"""Enumerate actual LSS/VSS candidate roles from declared metadata, never labels."""
def candidate_bindings(runtime,artifact):
 hashes=set()
 def visit(value):
  if isinstance(value,dict):
   for key,item in value.items():
    if key=='candidate_artifacts':
     for row in item:
      metadata=row.get('metadata',{});fabric=metadata.get('fabric',{});neo=metadata.get('neoforge',{});paper=metadata.get('paper',{})
      if fabric.get('id')=='lss' or any(m.get('modId')=='lss' for m in neo.get('mods',[])) or str(paper.get('main','')).startswith('dev.vox.lss.'):
       hashes.add(row['sha256'])
    else:visit(item)
  elif isinstance(value,list):
   for item in value:visit(item)
 visit(runtime)
 values={row['target']:artifact(row['target']) for row in runtime.get('stage_files',[]) if row['sha256'] in hashes}
 components=runtime.get('named_project_components',{})
 if components:
  if set(components)!={'main','common'} or components['common'].get('target')!='artifacts/lss-common-named.jar':raise ValueError('invalid named project component roles')
  for role,reference in components.items():
   target=reference['target'];value=artifact(target)
   if value!=reference['sha256']:raise ValueError('named project component bytes differ')
   values[target]=value
 if not values:raise ValueError('no metadata-bound LSS candidate roles')
 return values
