import unittest
from check_receive_run import observations
from check_receive_lifecycle import check
import test_receive_lifecycle
class ReceiveRunTest(unittest.TestCase):
    def test_private_fields_are_not_exported(self):
        source=test_receive_lifecycle.ReceiveLifecycleTest().valid()
        client=source+'\n[WI5-FIXTURE] OBSERVE world=private-world user=private-user /home/private token=secret'
        filtered=observations(client,'[LSS-ABRUPT-CLOSE] CLOSED realTransport=true disconnectPacket=false')
        self.assertNotIn('private','\n'.join(filtered));self.assertNotIn('secret','\n'.join(filtered))
        self.assertTrue(check('\n'.join(filtered))['passed'])
    def test_false_fields_are_not_promoted(self):
        source=test_receive_lifecycle.ReceiveLifecycleTest().valid().replace('oldTileAbsent=true','oldTileAbsent=false')
        filtered=observations(source,'[LSS-ABRUPT-CLOSE] CLOSED realTransport=true disconnectPacket=false')
        self.assertFalse(check('\n'.join(filtered))['passed'])
