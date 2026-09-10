import copy
import unittest
from catalog import Invalid
from port_batch import validate_batch, apply_batch

class PortBatchTests(unittest.TestCase):
    def batch(self):
        return {'schema_version':1,'baseline':{'a':'1'*40,'b':'2'*40},'required_targets':['a','b'],
                'reviewed_candidate_blobs':{'a':{'shared':'3'*40,'deleted':None},'b':{'shared':'3'*40}},
                'prerequisites':{'a':[],'b':['a']},'final_shared_invariants':['shared']}
    def test_cannot_waive_another_lines_adaptation(self):
        batch=self.batch();validate_batch(batch,batch['baseline'])
        report={'issues':[{'path':'shared','line':'b','result':'adaptation-review-required'}]}
        apply_batch(report,batch,'a',{'shared':'3'*40})
        self.assertFalse(report['passed'])
    def test_reviewed_deletion_and_shared_change_do_not_claim_batch_complete(self):
        batch=self.batch()
        report={'issues':[{'path':'deleted','line':'a','result':'adaptation-review-required'},
                          {'path':'shared','result':'shared-divergence'}]}
        apply_batch(report,batch,'a',{'shared':'3'*40})
        self.assertTrue(report['passed']);self.assertFalse(report['batch_complete'])
        clean={'issues':[]};apply_batch(clean,batch,'a',{})
        self.assertFalse(clean['batch_complete'])
    def test_new_unknown_file_and_unreviewed_bytes_remain_failures(self):
        batch=self.batch()
        for kind,blobs in [('unclassified',{'shared':'3'*40}),('shared-divergence',{'shared':'4'*40})]:
            report={'issues':[{'path':'shared','result':kind}]}
            apply_batch(report,batch,'a',blobs);self.assertFalse(report['passed'])
    def test_missing_target_cycle_and_false_shared_invariant_rejected(self):
        for mutation in ('target','cycle','invariant'):
            batch=self.batch()
            if mutation=='target':batch['required_targets'].pop()
            elif mutation=='cycle':batch['prerequisites']['a']=['b']
            else:batch['reviewed_candidate_blobs']['b']['shared']='4'*40
            with self.assertRaises(Invalid):validate_batch(batch,self.batch()['baseline'])
