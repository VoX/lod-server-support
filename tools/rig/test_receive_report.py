import importlib,json,tempfile,unittest
from pathlib import Path
import rig
import check_receive_run as checker
import test_receive_lifecycle

class ReceiveReportTest(unittest.TestCase):
    def test_report_rechecks_actual_observations_and_excludes_private_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'evidence').mkdir()
            log=root/'instances/lss-rig-client/minecraft/logs/latest.log';log.parent.mkdir(parents=True)
            source=test_receive_lifecycle.ReceiveLifecycleTest().valid().replace('[WI5-FIXTURE] PRECONDITION ',
                '[WI5-FIXTURE] PRECONDITION enabled=true sessionConfig=true version=20 user=PrivateTester world=PrivateWorld ')
            log.write_text(source);(root/'server.private.log').write_text('[LSS-ABRUPT-CLOSE] CLOSED realTransport=true disconnectPacket=false')
            runtime={};scenario={'checker':'receive-lifecycle'}
            rig.write(root/'runtime.json',runtime);rig.write(root/'scenario.json',scenario)
            manifest={'run_id':'one','run_hash':'bound','runtime_hash':rig.digest(runtime),'scenario_hash':rig.digest(scenario),'profile_hash':'profile'}
            proof=checker.make_proof(root,manifest)
            self.assertEqual([],checker.check_report(proof,manifest,scenario,root))
            self.assertTrue(proof['receive_report']['passed'])
            self.assertNotIn('Private',json.dumps(proof))
            # Editing the raw semantic outcome invalidates even an unchanged saved proof.
            log.write_text(source.replace('oldTileAbsent=true','oldTileAbsent=false'))
            self.assertTrue(checker.check_report(proof,manifest,scenario,root))
