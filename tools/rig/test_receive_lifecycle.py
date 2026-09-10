import unittest
from check_receive_lifecycle import check,SAME_WORLD,REPLACEMENT

class ReceiveLifecycleTest(unittest.TestCase):
    def valid(self):
        details={
            'PASS_SAME_WORLD_OFF_ON':'sameNativeWorld=true sameXaeroWorld=true sameConnection=true freshManager=true nativeRebuildsDrained=true',
            'REAL_CALLBACK_HELD':'preparedTile=true originOpen=true',
            'NATIVE_RETIRE_PREMISE':'pendingRebuildsPositive=true realCallbackHeld=true',
            'NATIVE_RETIRED':'pendingRebuilds=0 nativeWorldCleared=true generationChanged=true',
            'REPLACEMENT_READY':'newNativeWorld=true newConnection=true newManager=true sameDimension=true',
            'PASS_REPLACEMENT':'realOldCallbackReturned=true oldReceiptClosed=true oldTileAbsent=true nativeWorldRetired=true'}
        return '[LSS-ABRUPT-CLOSE] CLOSED realTransport=true disconnectPacket=false\n'+'\n'.join('['+marker+'] '+event+' '+details.get(event,'') for marker,names in
            [('WI5-FIXTURE',SAME_WORLD),('WI5-REPLACEMENT',REPLACEMENT)] for event in names)
    def test_complete_observations(self):self.assertTrue(check(self.valid())['passed'])
    def test_no_replacement_not_full_pass(self):
        self.assertFalse(check('\n'.join(line for line in self.valid().splitlines() if 'REPLACEMENT]' not in line))['passed'])
    def test_wrong_dimension_and_closed_receipt_fail(self):
        for field in ('sameDimension=true','oldReceiptClosed=true','pendingRebuildsPositive=true'):
            self.assertFalse(check(self.valid().replace(field,field.replace('true','false')))['passed'])
    def test_timeout_and_reordered_events_fail(self):
        self.assertFalse(check(self.valid()+'\n[WI5-REPLACEMENT] FAIL deadline')['passed'])
        self.assertFalse(check('\n'.join(reversed(self.valid().splitlines())))['passed'])
    def test_duplicate_callback_fails(self):
        self.assertFalse(check(self.valid()+'\n[WI5-REPLACEMENT] REAL_CALLBACK_HELD preparedTile=true originOpen=true')['passed'])
