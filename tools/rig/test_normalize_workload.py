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
