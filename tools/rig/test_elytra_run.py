import tempfile,unittest,sys,uuid,hashlib,json,copy,struct,zlib
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from rig import write,sha,digest,read
from elytra_contract import PHASES,SUBJECT,OBSERVER,predicates,setup_commands,falling_commands,landing_command
import test_elytra_strict as examples
from check_elytra_run import inspect,make_proof,check_report
def png(width=960,height=540):
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 header=struct.pack('>IIBBBBB',width,height,8,2,0,0,0)
 pixels=(b'\x00'+b'\x20'*(width*3))*height
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',header)+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')
class NativeFiles(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);r=self.root
  for name in ('evidence','participants','commands/results','mods','elytra-target/logs','instances/lss-rig-client/minecraft/logs'):(r/name).mkdir(parents=True,exist_ok=True)
  (r/'mods/candidate.jar').write_bytes(b'synthetic candidate');(r/'mods/fixture.jar').write_bytes(b'synthetic native observer fixture');(r/'evidence/elytra-gliding.png').write_bytes(png())
  self.owner=dict(pid=99999990,start='synthetic',boot='synthetic');write(r/'processes.json',[self.owner])
  self.scenario=dict(id='elytra',version=2,checker='elytra',assertions=['equipment_verified','game_mode_verified','falling_observed','fall_flying_true','movement_verified','render_observed'],required_test_count=6)
  target_profile=dict(id='controlled-target',line='1.21.10');write(r/'participants/elytra-target.json',target_profile)
  self.runtime=dict(elytra_ready=True,client_profiles=[{'role':'elytra-target','profile_hash':digest(target_profile)}],launches=[dict(id='elytra-target',argv=['java','-Dlss.rig.elytraTarget=true','--username',SUBJECT])],elytra_contract=dict(observer=OBSERVER,subject=SUBJECT,target_profile_hash=digest(target_profile),artifacts={name:sha(r/name) for name in ('mods/candidate.jar','mods/fixture.jar')}))
  self.manifest=dict(participants=[{'role':'elytra-target','profile_hash':digest(target_profile)}],run_id='run',run_hash='a'*64,profile_hash='b'*64,scenario_hash=digest(self.scenario),runtime_hash=digest(self.runtime),run_manifest={'staged_inputs':[dict(target=n,sha256=h) for n,h in self.runtime['elytra_contract']['artifacts'].items()]})
  write(r/'runtime.json',self.runtime);write(r/'scenario.json',self.scenario);write(r/'manifest.json',self.manifest)
  example=examples.Strict();example.setUp();subject=str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+SUBJECT).encode()).digest(),version=3))
  for row in example.native+example.draw:row['uuid']=subject
  for role,name,rows,tag,path in [('target',SUBJECT,example.native,'LSS_ELYTRA_NATIVE','elytra-target/logs/latest.log'),('observer',OBSERVER,example.draw,'LSS_ELYTRA_SUBMIT','instances/lss-rig-client/minecraft/logs/latest.log')]:
   session=dict(run_id='run',role=role,connection_id='run-'+role,player=name,protocol=20)
   text='LSS_ELYTRA_SESSION '+json.dumps(session)+'\nServer session config received (protocol v20, LOD distance: 32 chunks, enabled: true)\nLSS_ELYTRA_TARGET_CONSUMER registered=true\n'+'\n'.join(tag+' '+json.dumps(x) for x in rows)
   (r/path).write_text(text)
  camera=dict(run_id='run',role='observer',connection_id='run-observer',nano_time=1440,x=0,eye_y=100.4,z=0,yaw=0,pitch=0)
  with (r/'instances/lss-rig-client/minecraft/logs/latest.log').open('a') as f:f.write('\nLSS_ELYTRA_CAMERA '+json.dumps(camera)+'\n')
  pitchrow=dict(example.native[-1],nano_time=1495,pitch=60,mouse_grabbed=True)
  with (r/'elytra-target/logs/latest.log').open('a') as f:f.write('\nLSS_ELYTRA_NATIVE '+json.dumps(pitchrow)+'\n')
  server=''.join('handshake received from '+name+' (protocol v20, controlled)\n' for name in (SUBJECT,OBSERVER));commands=[];setup=[];number=0
  for phase in example.phases:
   for kind,(command,marker) in predicates(phase['id'],'run').items():
    number+=1;name=str(number)+'.json';receipt=dict(status='response_observed',launch_id='server',log_offset=len(server.encode()),process_identity=self.owner);write(r/'commands/results'/name,receipt);server+='[Server thread/INFO]: [Server] '+marker+'\n';commands.append(dict(request=name,phase=phase['id'],kind=kind,command=command,result=receipt,observed_ns=phase['start_ns']+20))
  for command in setup_commands()+falling_commands()+[landing_command(),landing_command()]:
   number+=1;name=str(number)+'.json';receipt=dict(status='submitted',launch_id='server',process_identity=self.owner);write(r/'commands/results'/name,receipt);setup.append(dict(request=name,command=command,result=receipt,observed_ns=900))
  (r/'server.private.log').write_text(server)
  inputs=[dict(action='hold',key='Shift_L',start_ns=1099,end_ns=1191),dict(action='press',key='space',start_ns=1391,end_ns=1392),dict(action='capture-mouse',x=480,y=270,button=1,start_ns=1491,end_ns=1492),dict(action='relative-look',dx=0,dy=400,start_ns=1493,end_ns=1494)]
  for row in inputs:row.update(game_root='elytra-target',window='0x10',process=self.owner)
  self.journal=dict(run_id='run',run_hash='a'*64,phases=example.phases,native_commands=commands,setup=setup,inputs=inputs,capture=dict(artifact='elytra-gliding.png',sha256=sha(r/'evidence/elytra-gliding.png'),time_ns=1450));write(r/'evidence/elytra-phases.json',self.journal)
 def test_actual_raw_file_recompute_and_proof(self):
  self.assertTrue(inspect(self.root,self.manifest)['passed']);proof=make_proof(self.root,self.manifest);self.assertEqual([],check_report(proof,self.manifest,self.scenario,self.root))
 def test_missing_actual_pitch_rejected(self):
  p=self.root/'elytra-target/logs/latest.log';p.write_text('\n'.join(x for x in p.read_text().splitlines() if 'mouse_grabbed' not in x));self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_missing_native_camera_rejected(self):
  p=self.root/'instances/lss-rig-client/minecraft/logs/latest.log';p.write_text('\n'.join(x for x in p.read_text().splitlines() if 'LSS_ELYTRA_CAMERA' not in x));self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_wrong_artifact(self):
  (self.root/'mods/candidate.jar').write_bytes(b'changed');self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_fabricated_native_command_flags(self):
  (self.root/'server.private.log').write_text('handshake received from ElytraSubject (protocol v20, x)\nhandshake received from Voximus_Maximus (protocol v20, x)\n');self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_syntax_error_echo_does_not_count_as_native_readback(self):
  p=self.root/'server.private.log';p.write_text(p.read_text().replace('[Server] ','syntax error run say '));self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_missing_private_landing_look_rejected(self):
  self.journal['inputs']=[x for x in self.journal['inputs'] if x['action']!='relative-look'];write(self.root/'evidence/elytra-phases.json',self.journal);self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_forbidden_direct_flight_setter(self):
  self.journal['setup'][0]['command']='data merge entity ElytraSubject {FallFlying:1b}';write(self.root/'evidence/elytra-phases.json',self.journal);self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_forged_report_cannot_replace_raw(self):
  proof=make_proof(self.root,self.manifest);path=self.root/'evidence/elytra-report.json';data=read(path);data['phases']['gliding']['submission_count']=99;write(path,data);proof['elytra_report']['artifact_sha256']=sha(path);self.assertTrue(check_report(proof,self.manifest,self.scenario,self.root))
 def test_old_failure_not_erased(self):
  write(self.root/'proof.json',{'failures':['prior failed native flight']});self.assertIn('prior failed native flight',make_proof(self.root,self.manifest)['failures'])
 def test_invalid_capture_rejected_even_with_updated_digest(self):
  for data in (png(639,360),png(640,359),png()[:-5],b'not PNG',png()+b'trailing'):
   with self.subTest(size=len(data)):
    path=self.root/'evidence/elytra-gliding.png';path.write_bytes(data)
    self.journal['capture']['sha256']=sha(path);write(self.root/'evidence/elytra-phases.json',self.journal)
    self.assertFalse(inspect(self.root,self.manifest)['passed'])
 def test_corrupt_png_checksum_rejected(self):
  data=bytearray(png());data[-5]^=1
  path=self.root/'evidence/elytra-gliding.png';path.write_bytes(data)
  self.journal['capture']['sha256']=sha(path);write(self.root/'evidence/elytra-phases.json',self.journal)
  self.assertFalse(inspect(self.root,self.manifest)['passed'])
if __name__=='__main__':unittest.main()
