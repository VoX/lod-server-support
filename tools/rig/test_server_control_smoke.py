import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from commands import Commands
from rig import identity
from server_control_smoke import Driver, check_snapshot, expect_config, COMPONENTS

def snapshot():
    return dict(schemaVersion=1,capturedAtMillis=1,serviceAvailable=True,enabled=True,
        generationEnabled=True,generationConfiguredForRestart=False,lodDistanceChunks=32,
        uptimeSeconds=1,sentSections=0,rawBytes=0,wireBytes=0,bandwidthWindowBytesPerSecond=0,
        versions={'components':dict.fromkeys(COMPONENTS,'unknown')})

class ServerControlTest(unittest.TestCase):
    def test_restart_overlay_is_distinct(self):check_snapshot(snapshot(),'Generation configured for restart: false (restart pending)',True,False)
    def test_running_change_is_rejected(self):
        row=snapshot();row['generationEnabled']=False
        with self.assertRaisesRegex(ValueError,'distinction'):check_snapshot(row,'(restart pending)',True,False)
    def test_arbitrary_metadata_is_rejected(self):
        row=snapshot();row['address']='private-server'
        with self.assertRaisesRegex(ValueError,'allowlist'):check_snapshot(row,'(restart pending)',True,False)
    def test_bad_summary_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'summary'):check_snapshot(snapshot(),'no restart note',True,False)
    def test_unknown_version_component_is_rejected(self):
        row=snapshot();row['versions']['components']['OTHER']='anything'
        with self.assertRaisesRegex(ValueError,'allowlist'):check_snapshot(row,'(restart pending)',True,False)
    def test_scope_checks_include_nested_settings(self):
        before={'enableChunkGeneration':True,'worlds':{'first':32}}
        after={'enableChunkGeneration':False,'worlds':{'first':64}}
        with self.assertRaisesRegex(ValueError,'unrelated'):expect_config(before,after,{'enableChunkGeneration'})
    def test_actual_owned_command_queue(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'server').mkdir();(root/'evidence').mkdir()
            (root/'manifest.json').write_text(json.dumps(dict(status='running',run_id='owned-test',run_hash='h',profile_hash='p',scenario_hash='s')))
            (root/'owner.json').write_text(json.dumps(identity(os.getpid())))
            (root/'runtime.json').write_text(json.dumps({'launches':[{'id':'server','cwd':'server'}]}))
            config=root/'server/config.json';config.write_text('{}')
            log=open(root/'server.private.log','wb')
            proc=subprocess.Popen([sys.executable,'-u','-c','import sys\nfor line in sys.stdin: print("ACK "+line.strip(),flush=True)'],stdin=subprocess.PIPE,stdout=log)
            queue=Commands(root,[({'id':'server'},proc)]);stop=threading.Event()
            def pump():
                while not stop.is_set():queue.poll();time.sleep(.01)
            thread=threading.Thread(target=pump);thread.start()
            try:
                driver=Driver(root,config,'lss','apply-undo')
                driver.send('diag','ACK lsslod diag')
                self.assertEqual('response_observed',driver.steps[0]['queue_result']['status'])
                self.assertEqual('lsslod diag',driver.steps[0]['command'])
                self.assertEqual(1,len(list((root/'commands/results').glob('*.json'))))
            finally:
                stop.set();thread.join();proc.stdin.close();proc.wait(timeout=3);log.close()
    def test_foreign_configuration_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'server').mkdir()
            (root/'manifest.json').write_text(json.dumps({'status':'running'}))
            (root/'owner.json').write_text(json.dumps(identity(os.getpid())))
            (root/'runtime.json').write_text(json.dumps({'launches':[{'id':'server','cwd':'server'}]}))
            config=root/'outside-server.json';config.write_text('{}')
            with self.assertRaisesRegex(ValueError,'inside this owned server'):Driver(root,config,'lss','apply-undo')

if __name__=='__main__':unittest.main()
