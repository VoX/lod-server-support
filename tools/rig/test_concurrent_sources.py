import copy
import unittest
from check_concurrent_sources import check

class SourceCheckerTest(unittest.TestCase):
    def fixture(self):
        oracle=[];consumers={};players=[]
        for letter in 'ABCD':
            subject='RigSubject'+letter;connection=subject+'-1'
            oracle.append(dict(event='session',subject=subject,connection_id=connection))
            rows=[dict(event='consumer_closed',overflow=False,held=0)]
            for source in range(4):
                target=dict(event='target',id=subject+'-'+str(source)+'-initial',subject=subject,connection_id=connection,expected_source=source,expected_block='gold_block',offered_ns=1)
                oracle.extend([target,dict(event='target_ready',id=target['id'],time_ns=2)])
                rows.append(dict(event='target_committed',id=target['id'],subject=subject,connection_id=connection,resolved_ns=3,source=source,expected_block='gold_block',lease_active=True,body_bytes=10))
            consumers[subject]=rows
            players.append(dict(name=subject,held_sync=0,held_gen=0,send_queue=0,backlog=0))
        consumers['RigSubjectD'] += [dict(event='acceptance_deferred'),dict(event='acceptance_released')]
        oracle.append(dict(event='session_transfer',subject='RigSubjectB',old_connection='RigSubjectB-1',connection_id='RigSubjectB-2',time_ns=4))
        events=[dict(event='source_preconditions_ready'),dict(event='writer_closed',overflow=False),dict(event='offers_closed',time_ns=5)]
        for stamp in [5,1_000_000_005]:
            events.append(dict(event='product_metrics',time_ns=stamp,players=players,disk=dict(pending=0),generation=dict(active=0),store=dict(queue=0),adapter_denial_reads=1))
        bounds=dict(held_sync=200,held_gen=1,send_queue=1024,backlog=1024,**{'disk.pending':800,'generation.active':4,'store.queue':1024})
        return oracle,consumers,events,bounds
    def test_complete_evidence(self):self.assertEqual('passed',check(*self.fixture())['status'])
    def test_source_mismatch_rejected(self):
        args=self.fixture();args[1]['RigSubjectA'][1]['source']=9
        self.assertEqual('failed',check(*args)['status'])
    def test_destroyed_players_do_not_prove_drain(self):
        args=self.fixture()
        for row in args[2]:
            if row['event']=='product_metrics':row['players']=[]
        self.assertIn('four simultaneous product registrations absent',check(*args)['errors'])
    def test_initial_hold_is_not_fault_evidence(self):
        args=self.fixture()
        for row in args[2]:
            if row['event']=='product_metrics':row['adapter_denial_reads']=0
        self.assertIn('admission adapter never exercised',check(*args)['errors'])
    def test_slow_path_requires_real_deferred_receipt(self):
        args=self.fixture();args[1]['RigSubjectD']=[row for row in args[1]['RigSubjectD'] if row['event']!='acceptance_deferred']
        self.assertIn('actual deferred consumer receipt/release absent',check(*args)['errors'])

if __name__=='__main__':unittest.main()
