import copy
import unittest
from check_workload import check

class WorkloadTests(unittest.TestCase):
    def setUp(self):
        self.oracle=[{'event':'session','subject':'A','connection_id':'one'},
            {'event':'target','id':'edit','subject':'A','connection_id':'one','offered_ns':1},
            {'event':'edit_applied','id':'edit','time_ns':2}]
        self.rows={'A':[{'event':'target_committed','id':'edit','subject':'A','connection_id':'one','resolved_ns':3,'lease_active':True,'body_bytes':12},
                        {'event':'consumer_closed','overflow':False,'held':0}]}
    def test_exact_current_body_and_closed_writer(self):
        self.assertEqual('passed',check(self.oracle,self.rows,1)['status'])
    def test_acknowledgment_precedes_mutation(self):
        self.oracle[1]['requires_ack']=True
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
        self.oracle.append({'event':'target_acknowledged','id':'edit','time_ns':1})
        self.assertEqual('passed',check(self.oracle,self.rows,1)['status'])
        self.oracle[-1]['time_ns']=3
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
    def test_one_receipt_satisfying_two_targets_counts_bytes_once(self):
        self.rows['A'][0].update(body_id=1,body_received_ns=2)
        self.oracle.extend([dict(self.oracle[1],id='second'),dict(self.oracle[2],id='second')])
        self.rows['A'].insert(1,dict(self.rows['A'][0],id='second'))
        result=check(self.oracle,self.rows,1)
        self.assertEqual('passed',result['status']);self.assertEqual(12,result['useful_body_bytes']['A'])
        self.rows['A'][1]['body_bytes']=13
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
    def test_foreign_session_and_unapplied_edit_fail(self):
        self.rows['A'][0]['connection_id']='old'
        result=check(self.oracle,self.rows,1)
        self.assertEqual(0,result['committed']);self.assertEqual('failed',result['status'])
        self.rows['A'][0]['connection_id']='one'
        self.assertEqual('failed',check(self.oracle[:2],self.rows,1)['status'])
    def test_missing_body_and_unclosed_consumer_fail(self):
        self.rows['A'][0]['body_bytes']=-1
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
        self.rows['A'][0]['body_bytes']=12;self.rows['A'].pop()
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
    def test_every_target_required_and_no_duplicate(self):
        self.oracle.append(dict(self.oracle[1],id='lost'))
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
    def test_reconnect_rebinds_only_after_oracle_transfer(self):
        self.oracle.extend([{'event':'session','subject':'A','connection_id':'two'},
                            {'event':'session_transfer','subject':'A','old_connection':'one','connection_id':'two','time_ns':3}])
        self.rows['A'][0].update(connection_id='two',resolved_ns=4)
        self.assertEqual('passed',check(self.oracle,self.rows,1)['status'])
        self.rows['A'][0]['connection_id']='one'
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
    def test_generation_source_and_independent_flat_block_required(self):
        self.oracle[1].update(expected_source=2,expected_block='bedrock')
        self.oracle[2]['event']='target_ready'
        self.rows['A'][0].update(source=2,expected_block='bedrock')
        self.assertEqual('passed',check(self.oracle,self.rows,1)['status'])
        self.rows['A'][0]['source']=0
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
        self.oracle.pop();self.rows['A'].append(copy.deepcopy(self.rows['A'][0]))
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])

    def test_held_old_body_cannot_prove_new_edit(self):
        self.rows['A'][0].update(body_id=1,body_received_ns=1)
        self.assertEqual('failed',check(self.oracle,self.rows,1)['status'])
        self.rows['A'][0]['body_received_ns']=2
        self.assertEqual('passed',check(self.oracle,self.rows,1)['status'])
