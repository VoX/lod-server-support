"""Owned launches inherit no ownership bindings, credentials or the desktop display."""
import json,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import rig

class LaunchEnvironmentTests(unittest.TestCase):
 def test_ownership_and_credential_names_are_stripped(self):
  source={'PATH':'/usr/bin','HOME':'/home/x','LSS_HARNESS_LOCK_FD':'10','LSS_HARNESS_OWNER_PID':'5','MODRINTH_PAT':'a','RCON_PASSWORD':'b','ARCHON_TOKEN':'c','AWS_SECRET_ACCESS_KEY':'d','PAT':'e','GITHUB_TOKEN':'f','JAVA_HOME':'/jvm','MESA_D3D12_DEFAULT_ADAPTER_NAME':'gpu'}
  self.assertEqual({'PATH':'/usr/bin','HOME':'/home/x','JAVA_HOME':'/jvm','MESA_D3D12_DEFAULT_ADAPTER_NAME':'gpu'},rig.launch_environment(source))
 def test_host_display_dropped_without_private_display(self):
  env={'DISPLAY':':0','XAUTHORITY':'/home/x/.Xauthority','WAYLAND_DISPLAY':'wayland-0','PATH':'/usr/bin'}
  rig.drop_host_display(env);self.assertEqual({'PATH':'/usr/bin'},env)
 def test_gui_client_markers(self):
  self.assertTrue(rig.is_gui_client_launch(['java','-cp','x','net.fabricmc.loader.impl.launch.knot.KnotClient']))
  self.assertTrue(rig.is_gui_client_launch(['java','--quickPlayMultiplayer','host:1']))
  self.assertTrue(rig.is_gui_client_launch(['python3','/repo/tools/rig/launch_prism.py','--run','x']))
  self.assertFalse(rig.is_gui_client_launch(['java','-jar','server.jar','nogui']))

class RunnerEnvironmentTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)/'run';(self.root/'evidence').mkdir(parents=True)
 def prepare(self,launches):
  runtime={'backend':'linux-headless','client_endpoint':'127.0.0.1:12345','launches':launches};profile={'status':'unverified','artifacts':[]};scenario={'timeout_seconds':1}
  rm={'runtime_tools':{},'storage_estimate':{}}
  manifest={'run_id':'synthetic-env','run_hash':rig.digest(rm),'run_manifest':rm,'status':'created','runtime_hash':rig.digest(runtime),'profile_hash':rig.digest(profile),'scenario_hash':rig.digest(scenario)}
  for name,value in [('runtime',runtime),('manifest',manifest),('scenario',scenario),('profile',profile)]:rig.write(self.root/(name+'.json'),value)
 def patches(self):
  return [patch('rig.require_lock'),patch('toolchain.verify'),patch('runtime_trees.verify'),patch('rig.check_available'),patch('run_claim.acquire'),patch('rig.signal.signal'),
          patch('storage_guard.preflight',return_value={}),patch('storage_guard.Monitor'),patch('commands.Commands'),patch('measure.Sampler')]
 def run_with(self,environment):
  from contextlib import ExitStack
  with ExitStack() as stack:
   for p in self.patches():stack.enter_context(p)
   stack.enter_context(patch.dict(os.environ,environment))
   return rig.run(self.root)
 def test_launched_fixture_never_sees_host_display_lock_or_credentials(self):
  dump=self.root/'observed-env.json'
  script='import json,os,sys;open(sys.argv[1],"w").write(json.dumps(sorted(os.environ)))'
  self.prepare([{'id':'fixture','cwd':'fixture','argv':[sys.executable,'-c',script,'{run}/observed-env.json']}])
  result=self.run_with({'DISPLAY':':0','WAYLAND_DISPLAY':'wayland-0','XAUTHORITY':'/host/.Xauthority','LSS_HARNESS_LOCK_FD':'10','LSS_HARNESS_OWNER_PID':'1','R05_SYNTHETIC_SECRET':'x','R05_API_TOKEN':'y'})
  self.assertEqual('failed',result['status'])
  observed=set(json.loads(dump.read_text()))
  for key in ('DISPLAY','WAYLAND_DISPLAY','XAUTHORITY','LSS_HARNESS_LOCK_FD','LSS_HARNESS_OWNER_PID','R05_SYNTHETIC_SECRET','R05_API_TOKEN'):
   self.assertNotIn(key,observed)
  self.assertIn('LSS_RIG_RUN_ID',observed);self.assertIn('ALSOFT_DRIVERS',observed)
 def test_gui_client_launch_without_private_display_is_refused_before_spawn(self):
  self.prepare([{'id':'client','cwd':'client','argv':['java','-cp','x','net.fabricmc.loader.impl.launch.knot.KnotClient']}])
  with patch('rig.subprocess.Popen') as popen:
   result=self.run_with({'DISPLAY':':0'})
  popen.assert_not_called()
  self.assertEqual('failed',result['status']);self.assertTrue(any('requires an owned private display' in e for e in result['errors']))
  self.assertTrue(rig.read(self.root/'launch-journal.json')['terminal'])

if __name__=='__main__':unittest.main()
