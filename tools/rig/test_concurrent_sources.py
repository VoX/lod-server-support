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
        consumers['RigSubjectD'] += [dict(event='acceptance_deferred',subject='RigSubjectD',time_ns=170_000_000_000),dict(event='acceptance_released',subject='RigSubjectD',time_ns=180_000_000_000)]
        oracle.append(dict(event='slow_consumer',subject='RigSubjectD',start_ns=160_000_000_000,end_ns=180_000_000_000))
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
        self.assertIn('actual scheduled deferred consumer hold/release absent or invalid',check(*args)['errors'])

    def test_startup_only_holds_do_not_cover_scheduled_pause(self):
        args=self.fixture()
        for row in args[1]['RigSubjectD']:
            if row['event'].startswith('acceptance_'):row['time_ns']-=160_000_000_000
        self.assertEqual('failed',check(*args)['status'])

    def test_release_before_pause_end_does_not_cover_hold(self):
        args=self.fixture();args[1]['RigSubjectD'][-1]['time_ns']=179_999_999_999
        self.assertEqual('failed',check(*args)['status'])
        # An unrelated later release must not hide an early release.
        args[1]['RigSubjectD'].append(dict(event='acceptance_released',subject='RigSubjectD',time_ns=181_000_000_000))
        self.assertEqual('failed',check(*args)['status'])

    def test_defer_boundary_is_start_inclusive_end_exclusive(self):
        args=self.fixture();args[1]['RigSubjectD'][-2]['time_ns']=160_000_000_000
        self.assertEqual('passed',check(*args)['status'])
        args[1]['RigSubjectD'][-2]['time_ns']=180_000_000_000
        self.assertEqual('failed',check(*args)['status'])

    def test_invalid_or_missing_event_time_and_wrong_subject_refused(self):
        for key,value in [('time_ns',None),('time_ns',True),('time_ns','170000000000'),('time_ns',-1),('subject','RigSubjectA')]:
            for offset in (-2,-1):
                with self.subTest(key=key,value=value,offset=offset):
                    args=self.fixture();args[1]['RigSubjectD'][offset][key]=value
                    self.assertEqual('failed',check(*args)['status'])
        args=self.fixture();del args[1]['RigSubjectD'][-2]['time_ns']
        self.assertEqual('failed',check(*args)['status'])

    def test_missing_duplicate_or_invalid_pause_refused(self):
        for change in ('missing','duplicate','subject','start','end','reverse'):
            with self.subTest(change=change):
                args=self.fixture();pause=next(r for r in args[0] if r['event']=='slow_consumer')
                if change=='missing':args[0].remove(pause)
                elif change=='duplicate':args[0].append(dict(pause))
                elif change=='subject':pause['subject']='RigSubjectA'
                elif change=='start':del pause['start_ns']
                elif change=='end':pause['end_ns']=True
                else:pause['end_ns']=pause['start_ns']-1
                self.assertEqual('failed',check(*args)['status'])

    def test_initial_hold_then_actual_pause_is_valid(self):
        args=self.fixture();rows=args[1]['RigSubjectD'];rows[-2:-2]=[
            dict(event='acceptance_deferred',subject='RigSubjectD',time_ns=10),
            dict(event='acceptance_released',subject='RigSubjectD',time_ns=20)]
        self.assertEqual('passed',check(*args)['status'])

    def test_missing_hold_preserves_delivery_and_debt_diagnostics(self):
        args=self.fixture();args[1]['RigSubjectD']=args[1]['RigSubjectD'][:-2]
        result=check(*args)
        self.assertEqual('failed',result['status'])
        self.assertEqual(16,result['targets']);self.assertEqual(16,result['committed'])
        self.assertEqual('passed',result['debt_check']['status'])

    def test_partial_retirement_preserves_remaining_pause_hold(self):
        args=self.fixture();rows=args[1]['RigSubjectD']
        rows[-1:-1]=[
            dict(event='acceptance_deferred',subject='RigSubjectD',time_ns=171_000_000_000),
            dict(event='acceptance_released',subject='RigSubjectD',time_ns=172_000_000_000)]
        self.assertEqual('passed',check(*args)['status'])
        rows[-1:-1]=[dict(event='acceptance_released',subject='RigSubjectD',time_ns=173_000_000_000)]
        self.assertEqual('failed',check(*args)['status'])

    def test_concurrent_observation_arrival_order_is_not_clock_order(self):
        args=self.fixture();rows=args[1]['RigSubjectD'];rows[-2:]=reversed(rows[-2:])
        self.assertEqual('passed',check(*args)['status'])

class ArmedSourceCheckerTest(unittest.TestCase):
    fixture=SourceCheckerTest.fixture
    def armed_fixture(self):
        args=self.fixture();oracle,consumers,events,bounds=args
        oracle[:]=[r for r in oracle if r.get('event')!='slow_consumer']
        arm=dict(run_id='run',subject='RigSubjectD',connection_id='RigSubjectD-1',target_id='RigSubjectD-0-initial',armed_ns=100,duration_ns=20_000_000_000,deadline_ns=120_000_000_100)
        trigger=dict(arm,body_id=7,wire_capture_id=9,start_ns=200,end_ns=20_000_000_200)
        oracle.extend([dict(arm,event='slow_consumer_arm'),dict(trigger,event='slow_consumer')])
        target=next(r for r in oracle if r.get('id')==arm['target_id'] and r.get('event')=='target')
        target.update(run_id='run',chunk_x=1,chunk_z=2,dimension='minecraft:overworld',offered_ns=100)
        next(r for r in oracle if r.get('event')=='target_ready' and r.get('id')==arm['target_id'])['time_ns']=120
        next(r for r in consumers['RigSubjectD'] if r.get('event')=='target_committed' and r.get('id')==arm['target_id'])['resolved_ns']=20_000_000_201
        rows=consumers['RigSubjectD'];rows[-2:]=[
            dict(trigger,event='slow_consumer_triggered'),
            dict(event='wire_capture',run_id='run',subject='RigSubjectD',connection_id='RigSubjectD-1',wire_capture_id=9,chunk_x=1,chunk_z=2,dimension='minecraft:overworld',source=0,arrival_ns=150),
            dict(event='acceptance_deferred',run_id='run',subject='RigSubjectD',body_id=7,wire_capture_id=9,time_ns=201),
            dict(event='acceptance_released',run_id='run',subject='RigSubjectD',body_id=7,wire_capture_id=9,time_ns=20_000_000_200)]
        return args

    def test_exact_sparse_trigger_pair_passes(self):
        self.assertEqual('passed',check(*self.armed_fixture())['status'])

    def test_sparse_target_cannot_commit_before_trigger_or_during_hold(self):
        for resolved in (199,200,20_000_000_199):
            with self.subTest(resolved=resolved):
                args=self.armed_fixture()
                next(r for r in args[1]['RigSubjectD'] if r.get('event')=='target_committed' and r.get('id')=='RigSubjectD-0-initial')['resolved_ns']=resolved
                result=check(*args)
                self.assertEqual('failed',result['status'])
                self.assertEqual(16,result['committed'])
                self.assertEqual('passed',result['debt_check']['status'])

    def test_sparse_commit_after_redelivery_may_use_another_body(self):
        args=self.armed_fixture()
        row=next(r for r in args[1]['RigSubjectD'] if r.get('event')=='target_committed' and r.get('id')=='RigSubjectD-0-initial')
        row.update(body_id=8,wire_capture_id=10,body_received_ns=20_000_000_200,resolved_ns=20_000_000_200)
        self.assertEqual('passed',check(*args)['status'])

    def test_sparse_identity_and_wire_mismatches_refused(self):
        for event,key,value in [('slow_consumer_triggered','body_id',8),('wire_capture','connection_id','other'),('wire_capture','chunk_x',5),('wire_capture','arrival_ns',201),('wire_capture','arrival_ns',119),('wire_capture','source',3),('acceptance_deferred','wire_capture_id',8),('acceptance_released','body_id',8),('acceptance_released','subject','RigSubjectA')]:
            with self.subTest(event=event,key=key):
                args=self.armed_fixture();next(r for r in args[1]['RigSubjectD'] if r['event']==event)[key]=value
                self.assertEqual('failed',check(*args)['status'])

    def test_sparse_early_release_or_bad_defer_time_refused(self):
        for event,time in [('acceptance_released',20_000_000_199),('acceptance_deferred',199),('acceptance_deferred',True)]:
            args=self.armed_fixture();next(r for r in args[1]['RigSubjectD'] if r['event']==event)['time_ns']=time
            self.assertEqual('failed',check(*args)['status'])

    def test_sparse_duplicate_or_missing_join_refused(self):
        for event in ('slow_consumer_arm','slow_consumer','slow_consumer_triggered','wire_capture','acceptance_deferred','acceptance_released'):
            for duplicate in (False,True):
                with self.subTest(event=event,duplicate=duplicate):
                    args=self.armed_fixture();rows=args[0] if event in ('slow_consumer_arm','slow_consumer') else args[1]['RigSubjectD']
                    row=next(r for r in rows if r['event']==event)
                    if duplicate:rows.append(dict(row))
                    else:rows.remove(row)
                    self.assertEqual('failed',check(*args)['status'])

    def test_sparse_duration_and_deadline_cannot_be_redeclared(self):
        for key,value in [('duration_ns',1),('deadline_ns',200),('start_ns',120_000_000_100)]:
            args=self.armed_fixture()
            for row in args[0]+args[1]['RigSubjectD']:
                if row.get('event') in ('slow_consumer_arm','slow_consumer','slow_consumer_triggered') and key in row:row[key]=value
            self.assertEqual('failed',check(*args)['status'])

if __name__=='__main__':unittest.main()
