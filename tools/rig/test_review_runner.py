"""Synthetic harness ownership/approval controls; never Minecraft or human evidence."""
import json,socket,subprocess,tempfile,unittest
from pathlib import Path
import rig
class ReviewRunnerTest(unittest.TestCase):
 def test_cleanup_before_review_and_explicit_review_finalization(self):
  with tempfile.TemporaryDirectory() as temp:
   root=Path(temp);script=root/'fixture.py'
   script.write_text('''import json,sys,time,hashlib
from pathlib import Path
r=Path(sys.argv[1]);m=json.loads((r/'manifest.json').read_text());image=r/'evidence/controlled.png';image.write_bytes(b'synthetic image only')
p={k:m[k] for k in ('run_id','profile_hash','scenario_hash','run_hash')};p.update(ready=True,handshake=True,test_count=1,assertions={'synthetic_only':True},reviews={},review_artifacts={'visual':{'artifact':'controlled.png','artifact_sha256':hashlib.sha256(image.read_bytes()).hexdigest()}})
(r/'proof.json').write_text(json.dumps(p));time.sleep(60)
''')
   profile=dict(schema_version=1,id='synthetic-review-control',line='26.2',platform='fabric',route='native',components=[],capabilities=[],status='unverified',limitations=['synthetic harness control only'],artifacts=[])
   with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
   runtime=dict(backend='linux-headless',bind_endpoint=f'127.0.0.1:{port}',client_endpoint=f'127.0.0.1:{port}',cache={},stage_files=[dict(source=str(script),sha256=rig.sha(script),target='artifacts/fixture.py')],launches=[dict(id='fixture',cwd='client',argv=['python3','{run}/artifacts/fixture.py','{run}'])])
   scenario=dict(id='synthetic-review',timeout_seconds=2,required_test_count=1,assertions=['synthetic_only'],human_reviews=['visual'])
   for name,data in [('profile',profile),('runtime',runtime),('scenario',scenario)]:rig.write(root/(name+'.json'),data)
   wrapper=str(rig.REPO/'tools/rig/rig')
   p=subprocess.run([wrapper,'create',str(root/'profile.json'),str(root/'scenario.json'),'--runtime',str(root/'runtime.json'),'--state',str(root/'runs')],capture_output=True,text=True,timeout=15)
   if p.returncode and ('Another soak' in p.stderr or 'Port 25565' in p.stderr):self.skipTest('coarse resources held by actual harness')
   self.assertEqual(0,p.returncode,p.stderr);run=Path(json.loads(p.stdout)['run'])
   p=subprocess.run([wrapper,'run',str(run)],capture_output=True,text=True,timeout=15)
   self.assertEqual(0,p.returncode,p.stderr);self.assertEqual('awaiting-review',json.loads(p.stdout)['status'])
   def command(name):
    p=subprocess.run([wrapper,name,str(run)],capture_output=True,text=True,timeout=10)
    return p,json.loads(p.stdout)
   p,result=command('collect');self.assertEqual('awaiting-review',result['status']);self.assertEqual('complete',result['cleanup'])
   proof=json.loads((run/'proof.json').read_text());m=json.loads((run/'manifest.json').read_text())
   review=dict(disposition='accepted',run_id=m['run_id'],profile_hash=m['profile_hash'],run_hash=m['run_hash'],reviewer_kind='user',reviewer_id='SYNTHETIC-UNIT-CONTROL',review_source=dict(kind='user-message',reference='SYNTHETIC-UNIT-CONTROL-NOT-A-REAL-USER'),**proof['review_artifacts']['visual'])
   proof['reviews']={'visual':review};rig.write(run/'proof.json',proof)
   p,result=command('collect');self.assertEqual('awaiting-review',result['status'])
   p,result=command('review');self.assertEqual(0,p.returncode,p.stderr);self.assertEqual('passed',result['status'])
   self.assertEqual('passed',json.loads((run/'manifest.json').read_text())['status'])
   p,result=command('review');self.assertEqual('passed',result['status'])
   proof['assertions']['synthetic_only']=False;rig.write(run/'proof.json',proof)
   p,result=command('review');self.assertEqual('failed',result['status'])
if __name__=='__main__':unittest.main()
