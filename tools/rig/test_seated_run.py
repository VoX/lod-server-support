import json,tempfile,unittest,hashlib,uuid
from pathlib import Path
from check_seated_run import inspect,make_proof,check_report
from test_seated_draw import TEXT
from rig import digest,write

class SeatedRunTest(unittest.TestCase):
 def prepare(self,root):
  (root/'evidence').mkdir()
  scenario={'id':'seated-draw','checker':'seated-draw'};runtime={'id':'controlled-test-only','launches':[{'id':'seated-target-'+suffix,'argv':['-Dlss.rig.seatedTarget=true','--username',name]}for suffix,name in [('a','SeatedSubjectA'),('b','SeatedSubjectB')]]}
  write(root/'scenario.json',scenario);write(root/'runtime.json',runtime)
  m={'run_id':'test','run_hash':'bound','profile_hash':'profile','scenario_hash':digest(scenario),'runtime_hash':digest(runtime)}
  p=root/'instances/lss-rig-client/minecraft/logs/latest.log';p.parent.mkdir(parents=True)
  ids=[str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3))for name in ['SeatedSubjectA','SeatedSubjectB']]
  text=TEXT.replace('aaa',ids[0]).replace('bbb',ids[1])
  for suffix in ('a','b'):
   target=root/('seated-target-'+suffix+'/logs/latest.log');target.parent.mkdir(parents=True)
   target.write_text('LSS_SEATED_TARGET_CONSUMER registered=true\nServer session config received (protocol v20, LOD distance: 32 chunks, enabled: true)\n')
  p.write_text('Server session config received (protocol v20, LOD distance: 32 chunks, enabled: true)\n'+text)
  return m,p
 def test_raw_proof_recomputed_and_changed_log_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);m,p=self.prepare(root);proof=make_proof(root,m)
   self.assertEqual([],check_report(proof,m,root))
   p.write_text(p.read_text().replace('outer_unwind=true','outer_unwind=false'))
   self.assertTrue(check_report(proof,m,root))
 def test_missing_handshake_and_changed_inputs_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);m,p=self.prepare(root);p.write_text(TEXT)
   self.assertFalse(inspect(root,m)['passed'])
   write(root/'runtime.json',{'changed':True})
   with self.assertRaises(ValueError):inspect(root,m)
 def test_user_review_not_invented_by_semantic_success(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);m,p=self.prepare(root);proof=make_proof(root,m)
   self.assertTrue(proof['seated_report']['passed'])
   self.assertEqual({},proof['reviews'])
 def test_prior_fixture_failure_survives_rebuild(self):
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,p=self.prepare(root);write(root/'proof.json',{'failures':['real earlier failure']})
   proof=make_proof(root,m);self.assertEqual(['real earlier failure'],proof['failures'])
 def test_retained_report_tampering_rejected(self):
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,p=self.prepare(root);proof=make_proof(root,m)
   write(root/'evidence/seated-draw.json',{'changed':True})
   self.assertTrue(check_report(proof,m,root))
 def test_both_actual_subject_consumers_and_exact_proxy_identities_required(self):
  with tempfile.TemporaryDirectory()as d:
   root=Path(d);m,p=self.prepare(root)
   for suffix in ('a','b'):
    target=root/('seated-target-'+suffix+'/logs/latest.log');old=target.read_text();target.write_text(old.replace('registered=true','registered=false'))
    self.assertFalse(inspect(root,m)['passed']);target.write_text(old)
   p.write_text(p.read_text().replace('first_seated=','first_seated=not-the-owned-subject-'))
   self.assertFalse(inspect(root,m)['passed'])
if __name__=='__main__':unittest.main()
