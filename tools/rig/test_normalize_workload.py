import unittest
from normalize_workload import normalize


class NormalizeTests(unittest.TestCase):
    def facts(self):
        oracle=[];consumers={};sessions=[]
        for subject in 'ABCD':
            oracle.extend([{'event':'session','subject':subject,'connection_id':subject},
                           {'event':'target','id':subject,'subject':subject,'connection_id':subject,'offered_ns':1,'expected_block':'gold_block','expected_source':0},
                           {'event':'edit_applied','id':subject,'time_ns':2}])
            consumers[subject]=[{'event':'target_committed','id':subject,'subject':subject,'connection_id':subject,'resolved_ns':3,'lease_active':True,'body_bytes':10,'body_id':1,'body_received_ns':2,'expected_block':'gold_block','source':0},
                                {'event':'consumer_closed','overflow':False,'held':0}]
            sessions.extend([{'event':'join','subject':subject,'connection_id':subject,'time_ns':0},
                             {'event':'product_registration_observed','subject':subject,'connection_id':subject,'time_ns':1},
                             {'event':'quit','connection_id':subject,'time_ns':40_000_000_000}])
        return oracle,consumers,sessions
    def test_derives_current_outcome_and_progress(self):
        result=normalize(*self.facts(),platform='paper',start_ns=0,end_ns=30_000_000_000,
                         debt_result={'status':'passed','drain_seconds':2},cleanup_complete=True)
        self.assertEqual(4,len(result['sessions']))
        self.assertTrue(all(row['start_ns']==1 for row in result['sessions']))
        self.assertTrue(all(w['eligible'] and w['useful_outcomes']==1 for w in result['progress_windows']))
        self.assertTrue(all(t['actual']==t['expected'] for t in result['oracle']))
    def test_missing_raw_close_or_failed_debt_cannot_normalize(self):
        oracle,consumers,sessions=self.facts();sessions.pop()
        with self.assertRaisesRegex(ValueError,'intervals'):
            normalize(oracle,consumers,sessions,platform='paper',start_ns=0,end_ns=30_000_000_000,debt_result={'status':'passed','drain_seconds':2},cleanup_complete=True)
        with self.assertRaisesRegex(ValueError,'debt'):
            normalize(*self.facts(),platform='paper',start_ns=0,end_ns=30_000_000_000,debt_result={'status':'failed'},cleanup_complete=True)
    def test_vanilla_join_cannot_replace_actual_registration(self):
        oracle,consumers,sessions=self.facts();sessions=[row for row in sessions if row.get('event')!='product_registration_observed']
        with self.assertRaisesRegex(ValueError,'actual product registration'):
            normalize(oracle,consumers,sessions,platform='paper',start_ns=0,end_ns=30_000_000_000,debt_result={'status':'passed','drain_seconds':2},cleanup_complete=True)

    def measured_facts(self, source):
        import test_target_intervals as strict
        oracle=[];consumers={};sessions=[]
        for subject in 'ABCD':
            fixture=strict.StrictTargets();fixture.setUp();fixture.measured_fallback(source)
            for row in fixture.oracle+fixture.rows['A']:
                if 'id' in row:row['id']=subject+row['id']
                if row.get('predecessor_id') is not None:row['predecessor_id']=subject+row['predecessor_id']
                if 'subject' in row:row['subject']=subject
                if 'connection_id' in row:row['connection_id']=subject
                row['run_id']='test-run'
            for row in fixture.rows['A']:row['subject']=subject
            oracle.extend(fixture.oracle);consumers[subject]=fixture.rows['A']
            sessions.extend([dict(event='join',subject=subject,connection_id=subject,time_ns=0),
                             dict(event='product_registration_observed',subject=subject,connection_id=subject,time_ns=1),
                             dict(event='quit',connection_id=subject,time_ns=100_000_000_000)])
        return oracle,consumers,sessions

    def normalize_measured(self, facts):
        return normalize(*facts,platform='paper',start_ns=0,end_ns=90_000_000_000,
                         debt_result={'status':'passed','drain_seconds':2},cleanup_complete=True)

    def test_allowed_route_survives_real_correctness_and_metric_pipeline(self):
        from performance import correctness
        from assemble_metrics import assemble
        for source in (1,3):
            facts=self.measured_facts(source);result=self.normalize_measured(facts)
            self.assertEqual([],correctness(result))
            for target in result['oracle']:
                self.assertEqual(source,target['delivery_source'])
                self.assertEqual(0,target['expected_source'])
                self.assertEqual([0,1,3],target['allowed_sources'])
                self.assertEqual({'block':'gold_block'},target['actual'])
                self.assertEqual(target['actual'],target['expected'])
            # Empty timing/RSS samples deliberately cannot qualify performance, but real
            # assembly must still account for every independently validated body exactly once.
            owners={name:{'pid':i+100,'boot':'test','start':i} for i,name in enumerate(['server',*'ABCD'])}
            metrics=assemble('test-run',facts[1],[],[],result['oracle'],[],owners,
                             {name:name for name in owners},0,90_000_000_000)
            for subject in 'ABCD':self.assertEqual(36,metrics[subject]['useful_body_bytes'])

    def test_wrong_route_block_or_original_interval_cannot_normalize(self):
        facts=self.measured_facts(2)
        with self.assertRaisesRegex(ValueError,'raw target proof'):self.normalize_measured(facts)
        facts=self.measured_facts(1);facts[1]['A'][0]['expected_block']='diamond_block'
        with self.assertRaisesRegex(ValueError,'raw target proof'):self.normalize_measured(facts)
        facts=self.measured_facts(3);facts[1]['A'][0]['resolved_ns']=66_000_000_000
        with self.assertRaisesRegex(ValueError,'raw target proof'):self.normalize_measured(facts)


    def test_full_normalization_is_identical_across_fresh_hash_seeds(self):
        import json
        import os
        from pathlib import Path
        import subprocess
        import sys
        code = """
import json
from test_normalize_workload import NormalizeTests
from normalize_workload import normalize
facts=NormalizeTests().facts()
result=normalize(*facts,platform='paper',start_ns=0,end_ns=90_000_000_000,
                 debt_result={'status':'passed','drain_seconds':2},cleanup_complete=True)
print(json.dumps(result,sort_keys=True))
"""
        outputs=[]
        for seed in ('1','2','7','41'):
            env=dict(os.environ,PYTHONHASHSEED=seed)
            env['PYTHONPATH']=os.pathsep.join(sys.path)
            result=subprocess.run([sys.executable,'-c',code],cwd=Path(__file__).parent,
                                  env=env,text=True,capture_output=True,timeout=30,check=True)
            outputs.append(result.stdout)
        self.assertTrue(all(value==outputs[0] for value in outputs[1:]),
                        'fresh-process normalization changed with hash seed')
        windows=json.loads(outputs[0])['progress_windows']
        self.assertEqual([(s,t) for s in 'ABCD' for t in (0,30_000_000_000,60_000_000_000)],
                         [(w['subject'],w['start_ns']) for w in windows])
        self.assertEqual(4,sum(w['useful_outcomes'] for w in windows))
