"""Signal-path ownership: a stop signal drains the runner's own protocol before escalation."""
import importlib.util,json,os,signal,subprocess,sys,tempfile,time,unittest
from pathlib import Path
from unittest.mock import patch
import rig

SUPERVISOR=rig.REPO/'scripts/lib/owned-process.py'

def load_supervisor():
    spec=importlib.util.spec_from_file_location('owned_process',SUPERVISOR);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

FAKE_RUNNER='''
import os,signal,subprocess,sys,time
sys.path.insert(0,%r)
from pathlib import Path
import rig
from launch_journal import initialize,before_spawn,spawned,terminal
root=Path(sys.argv[1]);runtime=rig.read(root/'runtime.json');manifest=rig.read(root/'manifest.json')
rig.write(root/'owner.json',rig.identity(os.getpid()));rig.write(root/'supervisor.json',rig.identity(os.getppid()))
initialize(root,manifest,runtime,rig.read(root/'owner.json'),rig.read(root/'supervisor.json'))
manifest.update(launch_journal_version=1,status='running');rig.write(root/'manifest.json',manifest)
stopped=[]
signal.signal(signal.SIGTERM,lambda *a:stopped.append(1))
before_spawn(root,'launch:fixture')
# The fixture ignores SIGTERM for four seconds: longer than the old 3 s SIGKILL escalation.
child=subprocess.Popen([sys.executable,'-c','import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(4)'])
spawned(root,'launch:fixture',child)
while not stopped and not (root/'stop').exists() and child.poll() is None:time.sleep(0.05)
child.terminate()
try:child.wait(timeout=20)
except subprocess.TimeoutExpired:child.kill();child.wait()
manifest.update(status='failed',errors=['stopped']);rig.write(root/'manifest.json',manifest)
terminal(root,'failed')
'''

class SupervisorStopTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
 def test_signal_stop_drains_runner_protocol_and_issues_cleanup_receipt(self):
  run=self.root/'run';run.mkdir()
  runtime={'backend':'linux-headless','launches':[{'id':'fixture'}]};run_manifest={'runtime_tools':{}}
  manifest={'run_id':'synthetic-stop','run_manifest':run_manifest,'run_hash':rig.digest(run_manifest),'runtime_hash':rig.digest(runtime),'status':'created'}
  rig.write(run/'runtime.json',runtime);rig.write(run/'manifest.json',manifest)
  script=self.root/'fake_runner.py';script.write_text(FAKE_RUNNER%str(rig.REPO/'tools/rig'))
  env={k:v for k,v in os.environ.items() if k not in ('LSS_HARNESS_LOCK_FD','LSS_HARNESS_OWNER_PID')}
  supervisor=subprocess.Popen([sys.executable,str(SUPERVISOR),'--inherit-lock','--rig-run-root',str(run),'--',sys.executable,str(script),str(run)],env=env)
  try:
   deadline=time.monotonic()+15
   while time.monotonic()<deadline and not ((run/'processes.json').exists() and json.loads((run/'processes.json').read_text())):time.sleep(0.05)
   fixture=json.loads((run/'processes.json').read_text());self.assertEqual(1,len(fixture))
   started=time.monotonic();os.kill(supervisor.pid,signal.SIGTERM);code=supervisor.wait(timeout=90);elapsed=time.monotonic()-started
  finally:
   if supervisor.poll() is None:supervisor.kill();supervisor.wait()
  self.assertEqual(143,code)
  self.assertGreaterEqual(elapsed,3.5,'runner protocol was cut short by the old 3 s escalation')
  journal=rig.read(run/'launch-journal.json');self.assertTrue(journal['terminal']);self.assertEqual('failed',journal['status'])
  self.assertEqual('failed',rig.read(run/'manifest.json')['status'])
  receipt=rig.read(run/'supervisor-cleanup.json');self.assertTrue(receipt['complete']);self.assertEqual(0,receipt['remaining_children'])
  self.assertTrue((run/'stop').exists())
  self.assertFalse(rig.alive(fixture[0]))
 def test_group_signal_skips_a_reaped_group_and_reaches_a_live_one(self):
  module=load_supervisor()
  reaped=subprocess.Popen([sys.executable,'-c','pass'],start_new_session=True);reaped.wait()
  with patch.object(module.os,'killpg') as killpg:
   self.assertFalse(module.signal_group(reaped.pid,signal.SIGTERM));killpg.assert_not_called()
  live=subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)'],start_new_session=True)
  try:
   self.assertIn(live.pid,module.group_members(live.pid))
   self.assertTrue(module.signal_group(live.pid,signal.SIGTERM));self.assertNotEqual(0,live.wait(timeout=10))
  finally:
   if live.poll() is None:live.kill();live.wait()

if __name__=='__main__':unittest.main()
