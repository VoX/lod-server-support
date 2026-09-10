import unittest,copy,hashlib,uuid
from check_send_admission import check
from check_send_admission_run import participant_bindings
TEXT='''[WI6-FIXTURE] READY tick=1 observer=Observer observer_uuid=viewer subject=Subject subject_uuid=subject unaffected=Other unaffected_uuid=other
[WI6-FIXTURE] UNAFFECTED_BASELINE tick=2 viewer=other actual_adapter_accepted=true
[WI6-FIXTURE] BASELINE_ROSTER tick=2 epoch=8 subject_index=0
[WI6-FIXTURE] BASELINE_UPDATE tick=2 epoch=8
[WI6-FIXTURE] ARMED tick=3 baseline_epoch=8 subject_index=0 actual_roster_and_update_accepted=true
[WI6-FIXTURE] OFF_ENTERED tick=4 baseline_epoch=8
[WI6-FIXTURE] CLEAR_DECLINED tick=4 count=1 epoch=9 actual_adapter_return=false
[WI6-FIXTURE] UNAFFECTED_CLEAR_ACCEPTED tick=4 viewer=other actual_adapter_accepted=true
[WI6-FIXTURE] CLEAR_DECLINED tick=5 count=2 epoch=9 actual_adapter_return=false
[WI6-FIXTURE] CLEAR_DECLINED tick=6 count=3 epoch=9 actual_adapter_return=false
[WI6-FIXTURE] ON_ENTERED tick=7 held_retries=3 held_epoch=9
[WI6-FIXTURE] REPLACEMENT_ROSTER_ACCEPTED tick=7 epoch=9 subject_index=0
[WI6-FIXTURE] REPLACEMENT_UPDATE_ACCEPTED tick=7 epoch=9
[WI6-FIXTURE] DEBT_RELEASED tick=107 clearPending=false fullRosterPending=false controlFullPending=false owner_thread=true
[WI6-FIXTURE] PASS_SEND_ADMISSION tick=107 held_retries=3 held_epoch=9 replacement_roster=true replacement_subject_update=true observation_ticks=100 obsolete_clear_attempts=0 fixture_scope=adapter_denial_not_physical_netty
'''
class Admission(unittest.TestCase):
 def test_native_consistent_sequence(self):self.assertTrue(check(TEXT)['passed'])
 def test_same_tick_update_before_roster_rejected(self):
  lines=TEXT.splitlines();a=next(i for i,x in enumerate(lines) if 'REPLACEMENT_ROSTER_ACCEPTED' in x);lines[a],lines[a+1]=lines[a+1],lines[a]
  self.assertFalse(check('\n'.join(lines))['passed'])
 def test_actual_baseline_required(self):
  for marker in ('BASELINE_ROSTER','BASELINE_UPDATE'):
   with self.subTest(marker=marker):self.assertFalse(check('\n'.join(x for x in TEXT.splitlines() if marker not in x))['passed'])
 def test_wrong_epoch(self):self.assertFalse(check(TEXT.replace('baseline_epoch=8','baseline_epoch=99'))['passed'])
 def test_wrong_unaffected(self):self.assertFalse(check(TEXT.replace('UNAFFECTED_BASELINE tick=2 viewer=other','UNAFFECTED_BASELINE tick=2 viewer=wrong'))['passed'])
 def test_false_final_replacement(self):self.assertFalse(check(TEXT.replace('replacement_roster=true','replacement_roster=false'))['passed'])
 def test_zero_retries(self):self.assertFalse(check(TEXT.replace('held_retries=3','held_retries=0'))['passed'])
 def test_wrong_observation_interval(self):self.assertFalse(check(TEXT.replace('observation_ticks=100','observation_ticks=0'))['passed'])
 def test_duplicated_decline(self):self.assertFalse(check(TEXT.replace('count=2','count=1'))['passed'])
 def test_noncanonical_number(self):self.assertFalse(check(TEXT.replace('held_epoch=9','held_epoch=09'))['passed'])
 def test_final_epoch_mismatch(self):self.assertFalse(check(TEXT.replace('PASS_SEND_ADMISSION tick=107 held_retries=3 held_epoch=9','PASS_SEND_ADMISSION tick=107 held_retries=3 held_epoch=10'))['passed'])
 def test_unaffected_baseline_not_accepted(self):self.assertFalse(check(TEXT.replace('UNAFFECTED_BASELINE tick=2 viewer=other actual_adapter_accepted=true','UNAFFECTED_BASELINE tick=2 viewer=other actual_adapter_accepted=false'))['passed'])
 def test_native_failure_not_erased(self):self.assertFalse(check(TEXT+'[WI6-FIXTURE] FAIL_OR_INCONCLUSIVE tick=108 bad\n')['passed'])
class Participants(unittest.TestCase):
 def setUp(self):
  self.names={'observer':'Observer','subject':'SeatedSubjectA','unaffected':'SeatedSubjectB'}
  flags=['-Dlss.wi6.enabled=true','-Dlss.rig.serverRoot={run}/server']+['-Dlss.wi6.'+k+'='+v for k,v in self.names.items()]
  self.runtime={'launches':[{'id':'server','argv':['java']+flags},{'id':'client','argv':['prism']},{'id':'seated-target-a','argv':['java','-Dlss.rig.seatedTarget=true','--username','SeatedSubjectA']},{'id':'seated-target-b','argv':['java','-Dlss.rig.seatedTarget=true','--username','SeatedSubjectB']}]}
  self.raw='[WI6-FIXTURE] READY tick=1 '+ ' '.join(k+'='+v+' '+k+'_uuid='+str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+v).encode()).digest(),version=3)) for k,v in self.names.items())+'\n'+'\n'.join(v+' joined the game' for v in self.names.values())
  self.clients={role:'Server session config received (protocol v20, LOD distance: 32 chunks, enabled: true)\nLSS_SEATED_TARGET_CONSUMER registered=true\n' for role in ('client','seated-target-a','seated-target-b')}
 def result(self):return participant_bindings(self.runtime,self.raw,self.clients,'/owned/run')[1]
 def test_all_three_native_participants(self):self.assertEqual(self.result(),[])
 def test_wrong_ready_uuid(self):
  self.raw=self.raw.replace('observer_uuid=','observer_uuid=wrong');self.assertTrue(self.result())
 def test_duplicate_property(self):
  self.runtime['launches'][0]['argv'].append('-Dlss.wi6.observer=Other');self.assertTrue(self.result())
 def test_wrong_direct_username(self):
  self.runtime['launches'][2]['argv'][-1]='Other';self.assertTrue(self.result())
 def test_missing_native_consumer(self):
  self.clients['seated-target-a']=self.clients['seated-target-a'].split('\n')[0];self.assertTrue(self.result())
 def test_wrong_root(self):
  self.runtime['launches'][0]['argv'][2]='-Dlss.rig.serverRoot=/unowned';self.assertTrue(self.result())
 def test_missing_handshake(self):
  self.clients['client']='';self.assertTrue(self.result())
 def test_missing_actual_join(self):
  self.raw=self.raw.replace('Observer joined the game','');self.assertTrue(self.result())

class NativeReports(unittest.TestCase):
 def setUp(self):
  import tempfile,sys,json
  from pathlib import Path
  sys.path.insert(0,str(Path(__file__).resolve().parent))
  from rig import digest
  Participants.setUp(self)
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);(self.root/'evidence').mkdir()
  # Match exact Java offline UUIDs and the three native role roots.
  raw=TEXT
  for key,old in [('observer','viewer'),('subject','subject'),('unaffected','other')]:
   name=self.names[key];identity=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3));raw=raw.replace(key+'_uuid='+old,key+'_uuid='+identity)
   if key=='unaffected':raw=raw.replace('viewer=other','viewer='+identity)
  raw=raw.replace('subject=Subject','subject=SeatedSubjectA').replace('unaffected=Other','unaffected=SeatedSubjectB')
  raw+='\n'.join(name+' joined the game' for name in self.names.values())+'\n'
  (self.root/'server.private.log').write_text(raw)
  for role,path in [('client','instances/lss-rig-client/minecraft'),('seated-target-a','seated-target-a'),('seated-target-b','seated-target-b')]:
   target=self.root/path/'logs/latest.log';target.parent.mkdir(parents=True);target.write_text(self.clients[role])
  self.scenario={'checker':'send-admission'}
  for path,value in [('runtime.json',self.runtime),('scenario.json',self.scenario)]: (self.root/path).write_text(json.dumps(value))
  self.manifest=dict(runtime_hash=digest(self.runtime),scenario_hash=digest(self.scenario),run_id='synthetic-admission-control',run_hash='r'*64,profile_hash='p'*64)
 def test_native_report_roundtrip(self):
  from check_send_admission_run import inspect,make_proof,check_report
  report=inspect(self.root,self.manifest);self.assertTrue(report['passed'],report)
  proof=make_proof(self.root,self.manifest);self.assertEqual(proof['test_count'],4);self.assertEqual(check_report(proof,self.manifest,self.root),[])
 def test_tampered_retained_report_rejected(self):
  from check_send_admission_run import make_proof,check_report
  proof=make_proof(self.root,self.manifest);(self.root/'evidence/send-admission.json').write_text('{}')
  self.assertTrue(check_report(proof,self.manifest,self.root))
 def test_changed_native_log_rejected(self):
  from check_send_admission_run import make_proof,check_report
  proof=make_proof(self.root,self.manifest)
  with (self.root/'server.private.log').open('a') as stream:stream.write('[WI6-FIXTURE] FAIL_OR_INCONCLUSIVE tick=108 changed\n')
  self.assertTrue(check_report(proof,self.manifest,self.root))
 def test_prior_native_failure_retained(self):
  import json
  from check_send_admission_run import make_proof
  (self.root/'proof.json').write_text(json.dumps({'failures':['prior native failure']}))
  self.assertIn('prior native failure',make_proof(self.root,self.manifest)['failures'])

if __name__=='__main__':unittest.main()
