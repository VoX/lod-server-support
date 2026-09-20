import json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import native_window as w
from drive_seated_draw import shared_deadline
class Audit(unittest.TestCase):
 def test_checker_selected(self):
  self.assertEqual(json.loads((Path(__file__).parent/'scenarios/seated-draw.json').read_text())['checker'],'seated-draw')
 def match(self,relative=False,reused=False,descendant=True):
  with tempfile.TemporaryDirectory() as tmp:
   base=Path(tmp);proc=base/'123';proc.mkdir();game=base/'game';game.mkdir();(proc/'cwd').symlink_to(base,target_is_directory=True)
   (proc/'cmdline').write_bytes(b'java\0--gameDir\0'+(b'game' if relative else str(game).encode())+b'\0')
   first={'pid':123,'start':1};last={'pid':123,'start':2} if reused else first
   with patch.object(w,'identity',side_effect=[first,last]),patch.object(w,'alive',return_value=True),patch.object(w,'descendant',return_value=descendant):return w.matching_process(proc,game,{'pid':12})
 def test_absolute_owned_game(self):self.assertIsNotNone(self.match())
 def test_relative_resolves_process_cwd(self):self.assertIsNotNone(self.match(relative=True))
 def test_pid_reuse_rejected(self):self.assertIsNone(self.match(reused=True))
 def test_unowned_process_rejected(self):self.assertIsNone(self.match(descendant=False))
 def test_setup_consumes_draw_budget(self):
  deadline=shared_deadline({'timeout_seconds':180},100)
  self.assertEqual(deadline,275)
  self.assertEqual(deadline-250,25) # setup used150seconds; draw gets25, not180
 def test_observation_override_used(self):
  self.assertEqual(shared_deadline({'timeout_seconds':180,'observe_seconds':60},100),155)
 def test_invalid_budget_rejected(self):
  for seconds in (True,0,10,float('nan'),float('inf'),'180'):
   with self.subTest(seconds=seconds),self.assertRaises(ValueError):shared_deadline({'timeout_seconds':seconds},0)
class PrismMatching(unittest.TestCase):
 def match(self,change=None):
  import zipfile
  with tempfile.TemporaryDirectory()as tmp:
   root=Path(tmp);game=root/'instances/observer/minecraft';game.mkdir(parents=True);proc=root/'123';proc.mkdir();java=root/'java';java.write_bytes(b'SYNTHETIC JAVA IDENTITY')
   jar=root/'NewLaunch.jar'
   with zipfile.ZipFile(jar,'w')as z:z.writestr('org/prismlauncher/EntryPoint.class',b'SYNTHETIC CLASS')
   (proc/'cwd').symlink_to(game,target_is_directory=True);(proc/'exe').symlink_to(java)
   args=[str(java),'-Dlss.rig.runId=owned','-cp',str(jar),'org.prismlauncher.EntryPoint']
   runtime=dict(backend='isolated-linux-prism',java=str(java),launches=[dict(argv=['python','launch_prism.py','--instance','observer','--java',str(java)])])
   for name,value in [('runtime',runtime),('manifest',dict(run_id='owned')),('profile',dict(components=[dict(uid='net.minecraft',version='1.21.1')]))]:(root/(name+'.json')).write_text(json.dumps(value))
   (game.parent/'mmc-pack.json').write_text(json.dumps(dict(components=[dict(uid='net.minecraft',version='1.21.1')])))
   if change=='run':args[1]='-Dlss.rig.runId=other'
   if change=='cwd':(proc/'cwd').unlink();(proc/'cwd').symlink_to(root)
   if change=='exe':(proc/'exe').unlink();(proc/'exe').symlink_to(jar)
   if change=='bootstrap':args[3]=str(java)
   if change=='instance':runtime['launches'][0]['argv'][3]='other';(root/'runtime.json').write_text(json.dumps(runtime))
   if change=='version':(game.parent/'mmc-pack.json').write_text(json.dumps(dict(components=[dict(uid='net.minecraft',version='other')])))
   (proc/'cmdline').write_bytes(b'\0'.join(x.encode()for x in args)+b'\0')
   first=dict(pid=123,start='1');last=dict(first,start='2')if change=='reused'else first
   with patch.object(w,'identity',side_effect=[first,last]),patch.object(w,'alive',return_value=True),patch.object(w,'descendant',return_value=change!='foreign'):
    return w.matching_process(proc,game,dict(pid=12),root)
 def test_exact_prism_protocol_process(self):self.assertIsNotNone(self.match())
 def test_prism_wrong_provenance_and_identity_rejected(self):
  for change in ['run','cwd','exe','bootstrap','instance','version','reused','foreign']:
   with self.subTest(change=change):self.assertIsNone(self.match(change))

if __name__=='__main__':unittest.main()

