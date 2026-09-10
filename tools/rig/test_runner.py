"""Actual wrapper/supervisor tests with a synthetic fixture, never Minecraft evidence."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest
import rig
class RunnerScriptTest(unittest.TestCase):
    def exercise(self,body,passed,closure=False,ready=False):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);script=root/'driver.py';script.write_text(body)
            checksum=rig.sha(script)
            profile={'schema_version':1,'id':'synthetic-harness-unit','line':'26.2','platform':'fabric','route':'native','components':[], 'capabilities':[], 'status':'unverified','limitations':['synthetic harness unit only'], 'artifacts':[]}
            with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
            runtime={'backend':'linux-headless','bind_endpoint':f'127.0.0.1:{port}','client_endpoint':f'127.0.0.1:{port}', 'cache':{},'stage_files':[{'source':str(script),'sha256':checksum,'target':'artifacts/driver.py'}],'launches':[{'id':'fixture','cwd':'client','argv':['python3','{run}/artifacts/driver.py','{run}']}]}
            if closure:
                context=root/'launcher';(context/'assets').mkdir(parents=True)
                pinned=context/'assets/pinned';pinned.write_bytes(b'pinned')
                runtime.update(authorized_launcher_context=str(context),launcher_inputs={'files':{'assets/pinned':rig.sha(pinned)}})
            if ready:runtime['ready_conditions']=[{'launch_id':'fixture','marker':'JOINT_READY','timeout_seconds':.3}]
            scenario={'id':'synthetic','timeout_seconds':1,'required_test_count':1,'assertions':['synthetic_only']}
            for name,value in [('profile',profile),('runtime',runtime),('scenario',scenario)]:rig.write(root/(name+'.json'),value)
            wrapper=str(rig.REPO/'tools/rig/rig')
            created=subprocess.run([wrapper,'create',str(root/'profile.json'),str(root/'scenario.json'),'--runtime',str(root/'runtime.json'),'--state',str(root/'runs')],capture_output=True,text=True,timeout=15)
            if created.returncode and ('Another soak' in created.stderr or 'Port 25565' in created.stderr):self.skipTest('coarse resources held by an actual harness')
            self.assertEqual(0,created.returncode,created.stderr)
            run=json.loads(created.stdout)['run']
            result=subprocess.run([wrapper,'run',run],capture_output=True,text=True,timeout=15)
            self.assertEqual(0 if passed else 1,result.returncode,result.stderr)
            collected=subprocess.run([wrapper,'collect',run],capture_output=True,text=True,timeout=10)
            data=json.loads(collected.stdout)
            self.assertEqual('complete',data['cleanup'])
            self.assertEqual('passed' if passed else 'failed',data['status'])
    def test_actual_wrapper_success_and_collection(self):
        self.exercise('import json,sys,time\nfrom pathlib import Path\nr=Path(sys.argv[1]);m=json.loads((r/"manifest.json").read_text())\np={k:m[k] for k in ("run_id","profile_hash","scenario_hash","run_hash")}\np.update(ready=True,handshake=True,test_count=1,assertions={"synthetic_only":True})\n(r/"proof.json").write_text(json.dumps(p))\ntime.sleep(60)\n',True)
    def test_actual_failed_startup_is_not_success(self):self.exercise('raise RuntimeError("synthetic startup failure")\n',False)
    def test_actual_timeout_without_proof(self):self.exercise('import time;time.sleep(60)\n',False)
    def test_joint_readiness_cannot_be_replaced_by_early_proof(self):
        self.exercise('import json,sys,time\nfrom pathlib import Path\nr=Path(sys.argv[1]);m=json.loads((r/"manifest.json").read_text())\np={k:m[k] for k in ("run_id","profile_hash","scenario_hash","run_hash")}\np.update(ready=True,handshake=True,test_count=1,assertions={"synthetic_only":True})\n(r/"proof.json").write_text(json.dumps(p))\ntime.sleep(60)\n',False,ready=True)
    def test_shutdown_rechecks_launcher_closure(self):
        body='import json,sys,time\nfrom pathlib import Path\nr=Path(sys.argv[1]);m=json.loads((r/"manifest.json").read_text());runtime=json.loads((r/"runtime.json").read_text())\n(Path(runtime["authorized_launcher_context"])/"assets/pinned").write_bytes(b"changed")\np={k:m[k] for k in ("run_id","profile_hash","scenario_hash","run_hash")}\np.update(ready=True,handshake=True,test_count=1,assertions={"synthetic_only":True})\n(r/"proof.json").write_text(json.dumps(p))\ntime.sleep(60)\n'
        self.exercise(body,False,closure=True)
