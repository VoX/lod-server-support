"""Synthetic journal controls; no Minecraft or actual user acceptance."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import rig,launch_journal as journal
from review_state import ownership_errors

def owner(n):return {'pid':2147483000+n,'start':str(n),'boot':'synthetic-dead-boot'}
def receipt(root,j):
 rig.write(root/'supervisor-cleanup.json',{'schema_version':1,**{k:j[k] for k in ('run_id','run_hash','runtime_hash','owner','supervisor')},'journal_sha256':rig.sha(root/'launch-journal.json'),'processes_sha256':rig.sha(root/'processes.json'),'complete':True,'remaining_children':0})
def prepare(root,states=('spawned','not-attempted'),display=False):
 runtime={'backend':'linux-headless','private_display':display,'launches':[{'id':'server'},{'id':'client'}]}
 if display:states=('spawned',)+states
 rm={'runtime_hash':rig.digest(runtime)};m={'run_id':'SYNTHETIC','run_hash':rig.digest(rm),'runtime_hash':rig.digest(runtime),'run_manifest':rm,'status':'failed','launch_journal_version':1}
 for n,v in [('runtime',runtime),('manifest',m),('owner',owner(1)),('supervisor',owner(2))]:rig.write(root/(n+'.json'),v)
 j=journal.initialize(root,m,runtime,owner(1),owner(2));identities=[]
 for i,(e,state) in enumerate(zip(j['entries'],states)):
  e['state']=state
  if state=='spawned':e['identity']=owner(i+3);identities.append(e['identity'])
 j.update(terminal=True,status='failed');rig.write(root/'launch-journal.json',j);rig.write(root/'processes.json',identities);receipt(root,j)
 return runtime,j

class JournalTests(unittest.TestCase):
 def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
 def tearDown(self):self.temp.cleanup()
 def test_partial_failed_cleanup_and_display_snapshots(self):
  r,j=prepare(self.root,display=True);self.assertEqual([],ownership_errors(self.root,r))
 def test_popen_failure_and_pre_display_failure_can_close(self):
  r,j=prepare(self.root,states=('spawn-failed','not-attempted'));self.assertEqual([],ownership_errors(self.root,r))
 def test_no_native_started_is_failed_not_success(self):
  r,j=prepare(self.root,states=('not-attempted','not-attempted'));self.assertEqual([],ownership_errors(self.root,r))
  m=rig.read(self.root/'manifest.json');m['status']='passed';rig.write(self.root/'manifest.json',m);self.assertTrue(ownership_errors(self.root,r))
 def test_missing_short_foreign_duplicate_inflight_receipt_and_live_rejected(self):
  r,j=prepare(self.root,display=True);saved={p:p.read_bytes() for p in self.root.glob('*.json')}
  changes=[('launch-journal.json',None),('supervisor-cleanup.json',None),('processes.json',[])]
  for mutate in [lambda v:v['entries'].pop(),lambda v:v.update(run_id='foreign'),lambda v:v['entries'][1].update(identity=v['entries'][0]['identity']),lambda v:v['entries'][1].update(state='spawning'),lambda v:v.update(terminal=False)]:
   value=copy.deepcopy(j);mutate(value);changes.append(('launch-journal.json',value))
  for name,value in changes:
   with self.subTest(name=name,value=value):
    for p,b in saved.items():p.write_bytes(b)
    if value is None:(self.root/name).unlink()
    else:rig.write(self.root/name,value)
    self.assertTrue(ownership_errors(self.root,r))
  for p,b in saved.items():p.write_bytes(b)
  with patch('rig.alive',return_value=True):self.assertTrue(ownership_errors(self.root,r))
 def test_spawn_snapshot_is_never_resampled_after_exit(self):
  r,j=prepare(self.root);(self.root/'launch-journal.json').unlink();journal.initialize(self.root,rig.read(self.root/'manifest.json'),r,owner(1),owner(2))
  journal.before_spawn(self.root,'launch:server')
  with patch('rig.identity',return_value=owner(3)):journal.spawned(self.root,'launch:server',type('P',(),{'pid':123})())
  with patch('rig.identity',return_value=None):journal.terminal(self.root,'failed')
  self.assertEqual([owner(3)],rig.read(self.root/'processes.json'))
 def test_missing_identity_leaves_inflight_gap(self):
  r,j=prepare(self.root);(self.root/'launch-journal.json').unlink();journal.initialize(self.root,rig.read(self.root/'manifest.json'),r,owner(1),owner(2));journal.before_spawn(self.root,'launch:server')
  with patch('rig.identity',return_value=None),self.assertRaises(ValueError):journal.spawned(self.root,'launch:server',type('P',(),{'pid':123})())
  journal.terminal(self.root,'failed');self.assertTrue(ownership_errors(self.root,r))
if __name__=='__main__':unittest.main()
