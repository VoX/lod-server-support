"""Reject normal helpers inside Mixin's exclusively owned package in actual jars."""
import json,zipfile
from pathlib import Path

def validate(path):
 with zipfile.ZipFile(path) as z:
  meta=json.loads(z.read('fabric.mod.json'));configs=meta['mixins'];ordinary=[]
  for values in meta.get('entrypoints',{}).values():
   ordinary.extend(x if isinstance(x,str) else x['value'] for x in values)
  for item in configs:
   config=json.loads(z.read(item if isinstance(item,str) else item['config']));prefix=config['package'].replace('.','/')+'/'
   declared={prefix+name.replace('.','/')+'.class' for key in ('mixins','client','server') for name in config.get(key,[])}
   if not declared:raise ValueError('empty mixin declaration')
   for name in declared:
    if b'Lorg/spongepowered/asm/mixin/Mixin;' not in z.read(name):raise ValueError('declared class lacks Mixin annotation: '+name)
   helpers=[name for name in z.namelist() if name.startswith(prefix) and name.endswith('.class') and name.split('$',1)[0]+('.class' if '$' in name else '') not in declared]
   if helpers:raise ValueError('ordinary helper inside exclusive mixin package: '+repr(helpers))
   if any(x.replace('.','/').startswith(prefix) for x in ordinary):raise ValueError('ordinary entrypoint inside exclusive mixin package')
 return True
if __name__=='__main__':
 import sys
 for path in sys.argv[1:]:validate(Path(path));print('packaging validated:',path)
