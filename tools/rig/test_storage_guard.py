"""Physical VHD capacity, no-side-effect admission, and cheap runtime floor controls."""
import copy,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,MagicMock
import storage_guard as g

class StorageGuardTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.source=self.root/'input';self.source.write_bytes(b'a')
  self.profile={'artifacts':[{'file':'input.jar','sha256':'x'}]};self.runtime={'cache':{'x':str(self.source)},'stage_files':[{'source':str(self.source)}],'generated_files':{}}
  self.value=g.estimate(self.profile,self.runtime);self.host={'vhd_path':'C:\\owned\\ext4.vhdx','vhd_bytes':1,'volume_id':'volume-c','drive_letter':'C','free_bytes':100*g.GIB,'size_bytes':1000*g.GIB}
 def test_estimate_counts_duplicate_copy_targets_without_reading_content(self):
  with patch.object(Path,'read_bytes',side_effect=AssertionError('no content scan')):value=g.estimate(self.profile,self.runtime)
  self.assertEqual(2,value['copied_files']);self.assertEqual(64*1024**2+8192,value['copy_bytes']);self.assertEqual(8*g.GIB,value['growth_bytes'])
 def test_create_and_start_boundaries_keep_reserve_after_staging(self):
  self.assertEqual(g.RESERVE+self.value['copy_bytes']+self.value['growth_bytes'],g.requirement(self.value,'create'))
  self.assertEqual(g.RESERVE+self.value['growth_bytes'],g.requirement(self.value,'run'))
 def test_host_low_space_rejects_even_with_large_linux_df(self):
  host=dict(self.host,free_bytes=49*g.GIB)
  with patch('storage_guard.wsl',return_value=True),patch('storage_guard.windows_host',return_value=host),patch('storage_guard.drvfs',return_value='/mnt/c'),patch('storage_guard.capacity',return_value={'free_bytes':1000*g.GIB,'size_bytes':1000*g.GIB}):
   with self.assertRaisesRegex(ValueError,'Physical VHD'):g.preflight(self.profile,self.runtime,self.root)
 def test_unknown_host_never_falls_back_to_linux_df(self):
  with patch('storage_guard.wsl',return_value=True),patch('storage_guard.capacity',return_value={'free_bytes':1000*g.GIB}),patch('storage_guard.windows_host',side_effect=ValueError('unknown actual host')):
   with self.assertRaisesRegex(ValueError,'unknown actual host'):g.preflight(self.profile,self.runtime,self.root)
 def test_linux_low_space_rejects_before_windows_query(self):
  with patch('storage_guard.capacity',return_value={'free_bytes':49*g.GIB}),patch('storage_guard.windows_host') as host:
   with self.assertRaisesRegex(ValueError,'Linux storage'):g.preflight(self.profile,self.runtime,self.root)
   host.assert_not_called()
 def test_run_uses_persisted_estimate_without_scanning_inputs(self):
  with patch('storage_guard.estimate',side_effect=AssertionError('no runtime scan')),patch('storage_guard.wsl',return_value=False),patch('storage_guard.capacity',return_value={'free_bytes':100*g.GIB}):
   self.assertEqual(self.value,g.preflight({}, {},self.root,self.value,'run')['estimate'])
   with self.assertRaisesRegex(ValueError,'immutable storage estimate'):g.preflight({}, {},self.root,None,'run')
 def test_exact_capacity_boundary_is_allowed(self):
  bound=g.requirement(self.value,'create')
  with patch('storage_guard.wsl',return_value=False),patch('storage_guard.capacity',return_value={'free_bytes':bound}):g.preflight(self.profile,self.runtime,self.root)
  with patch('storage_guard.wsl',return_value=False),patch('storage_guard.capacity',return_value={'free_bytes':bound-1}):
   with self.assertRaises(ValueError):g.preflight(self.profile,self.runtime,self.root)
 def test_actual_volume_must_map_to_unique_matching_drvfs(self):
  mount=r'129 80 0:67 / /mnt/c rw - 9p C:\134 rw,aname=drvfs'+'\n'
  with patch.object(Path,'read_text',return_value=mount),patch('storage_guard.capacity',return_value={'size_bytes':1000*g.GIB}):self.assertEqual('/mnt/c',g.drvfs(self.host))
  with patch.object(Path,'read_text',return_value=mount+mount),patch('storage_guard.capacity',return_value={'size_bytes':1000*g.GIB}):
   with self.assertRaisesRegex(ValueError,'unique verified'):g.drvfs(self.host)
  with patch.object(Path,'read_text',return_value=mount),patch('storage_guard.capacity',return_value={'size_bytes':2000*g.GIB}):
   with self.assertRaises(ValueError):g.drvfs(self.host)
 def test_windows_query_is_bounded_and_rejects_malformed_response(self):
  with patch('storage_guard.subprocess.run',return_value=MagicMock(stdout=json.dumps(self.host))) as query:
   self.assertEqual(self.host,g.windows_host());self.assertEqual(20,query.call_args.kwargs['timeout']);self.assertFalse(query.call_args.kwargs.get('shell',False))
  with patch('storage_guard.subprocess.run',return_value=MagicMock(stdout='{}')):
   with self.assertRaises(ValueError):g.windows_host()
 def test_monitor_has_sixty_second_cadence_and_no_windows_subprocess(self):
  root=self.root/'run';(root/'evidence').mkdir(parents=True);monitor=g.Monitor(root,{'linux_path':str(root),'host_monitor':'/mnt/c','host':self.host})
  with patch('storage_guard.time.monotonic',side_effect=[0,1,59.9,60]),patch('storage_guard.capacity',return_value={'free_bytes':100*g.GIB}),patch('storage_guard.drvfs',return_value='/mnt/c') as mount,patch('storage_guard.windows_host',side_effect=AssertionError('no runtime PowerShell')):
   for _ in range(4):monitor.check()
   self.assertEqual(2,mount.call_count)
 def test_runtime_floor_or_mount_change_stops(self):
  root=self.root/'run';(root/'evidence').mkdir(parents=True);info={'linux_path':str(root),'host_monitor':'/mnt/c','host':self.host}
  with patch('storage_guard.capacity',return_value={'free_bytes':g.RESERVE-1}),patch('storage_guard.drvfs',return_value='/mnt/c'):
   with self.assertRaisesRegex(ValueError,'low-water'):g.Monitor(root,info).check()
  with patch('storage_guard.capacity',return_value={'free_bytes':100*g.GIB}),patch('storage_guard.drvfs',return_value='/mnt/d'):
   with self.assertRaisesRegex(ValueError,'mount changed'):g.Monitor(root,info).check()
 def test_create_preflight_follows_ready_plan_before_any_staging(self):
  import rig
  state=self.root/'absent-state'
  with patch('rig.require_lock'),patch('storage_guard.preflight',side_effect=ValueError('low physical disk')),patch('rig.plan',return_value={'status':'ready'}) as plan:
   with self.assertRaisesRegex(ValueError,'low physical disk'):rig.create(self.profile,self.runtime,{},state)
   plan.assert_called_once();self.assertFalse(state.exists())
 def test_blocked_missing_cache_retains_plan_error_before_storage(self):
  import rig
  state=self.root/'absent-state'
  with patch('rig.require_lock'),patch('rig.plan',return_value={'status':'blocked','missing':['absent.jar']}),patch('storage_guard.preflight') as guard:
   with self.assertRaisesRegex(ValueError,'profile blocked'):rig.create(self.profile,{'cache':{}},{},state)
   guard.assert_not_called();self.assertFalse(state.exists())
 def test_doctor_exposes_actual_host_failure_instead_of_linux_green(self):
  import rig
  runtime={'backend':'linux-headless','bind_endpoint':'127.0.0.1:25574','client_endpoint':'127.0.0.1:25574'}
  with patch('storage_guard.preflight',side_effect=ValueError('actual WSL host unknown')),patch('rig.check_available'):
   value=rig.doctor(runtime);self.assertFalse(value['storage_ready']);self.assertIn('host unknown',value['storage_error'])
 def test_runtime_floor_failure_uses_existing_owned_cleanup(self):
  import rig
  root=self.root/'run';(root/'evidence').mkdir(parents=True)
  runtime={'backend':'linux-headless','launches':[]};profile={'status':'unverified','artifacts':[]};scenario={'timeout_seconds':1};rm={'runtime_tools':{},'storage_estimate':self.value}
  manifest={'run_id':'synthetic','run_hash':rig.digest(rm),'run_manifest':rm,'status':'created','runtime_hash':rig.digest(runtime),'profile_hash':rig.digest(profile),'scenario_hash':rig.digest(scenario)}
  for name,value in [('runtime',runtime),('manifest',manifest),('scenario',scenario),('profile',profile)]:rig.write(root/(name+'.json'),value)
  with patch('rig.require_lock'),patch('toolchain.verify'),patch('runtime_trees.verify'),patch('rig.check_available'),patch('run_claim.acquire'),patch('rig.signal.signal'),patch('storage_guard.preflight',return_value={}),patch('storage_guard.Monitor') as monitor,patch('rig.terminate_owned') as cleanup:
   monitor.return_value.check.side_effect=ValueError('Runtime storage low-water floor reached');result=rig.run(root)
   self.assertEqual('failed',result['status']);self.assertIn('low-water',result['errors'][0]);cleanup.assert_called_once()
   self.assertTrue(rig.read(root/'launch-journal.json')['terminal'])
if __name__=='__main__':unittest.main()
