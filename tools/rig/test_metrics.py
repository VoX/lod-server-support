import unittest
from metrics import duration_metrics,rss_metrics,eligible_seconds

class RawMetricsTests(unittest.TestCase):
    def test_tick_execution_separate_from_schedule_delay(self):
        rows=[{'event':'owning_tick','start_ns':5_000_000,'end_ns':8_000_000},
              {'event':'tick_metric','scheduled_ns':0,'start_ns':5_000_000,'end_ns':8_000_000}]
        result=duration_metrics(rows,0,10_000_000,'tick')
        self.assertEqual(3,result['tick_execution_p99_ms']);self.assertEqual(5,result['tick_delay_p99_ms'])
        self.assertIsNone(duration_metrics([],0,10,'frame')['frame_p99_ms'])
    def test_rss_omissions_cannot_reduce_denominator(self):
        row={'subject':'client','scheduled_ns':0,'rss_bytes':100,'missing':False,'process_identity':'creation'}
        result=rss_metrics([row],'client','creation',0,10_000_000_000)
        self.assertEqual(10,result['rss_scheduled']);self.assertEqual(1,result['rss_observed'])
        self.assertEqual(0,rss_metrics([row],'client','replacement',0,10_000_000_000)['rss_observed'])
    def test_burst_samples_cannot_fill_missing_scheduled_seconds(self):
        rows=[{'subject':'client','scheduled_ns':offset,'rss_bytes':100,'missing':False,'process_identity':'creation'} for offset in (0,1,2,3)]
        result=rss_metrics(rows,'client','creation',0,4_000_000_000)
        self.assertEqual(4,result['rss_scheduled']);self.assertEqual(1,result['rss_observed'])
        self.assertIn('multiple RSS observations in scheduled second',result['errors'])
    def test_eligible_time_union_and_independent_stall(self):
        targets=[{'subject':'A','offered_ns':0,'resolved_ns':8_000_000_000},
                 {'subject':'A','offered_ns':2_000_000_000,'resolved_ns':10_000_000_000}]
        faults=[{'subject':'A','kind':'slow-consumer','start_ns':4_000_000_000,'end_ns':6_000_000_000}]
        self.assertEqual(8,eligible_seconds(targets,faults,'A',0,10_000_000_000))
        self.assertEqual(0,eligible_seconds(targets,faults,'B',0,10_000_000_000))

class EligibleSweepTests(unittest.TestCase):
    def test_overlapping_requests_and_faults_match_discrete_time_oracle(self):
        import random
        randomizer=random.Random(193)
        for _ in range(100):
            spans=[sorted(randomizer.sample(range(41),2)) for _ in range(12)]
            faults=[sorted(randomizer.sample(range(41),2)) for _ in range(4)]
            targets=[dict(subject='A',offered_ns=a,resolved_ns=b) for a,b in spans]
            stalled=[dict(subject='A',kind='slow-consumer',start_ns=a,end_ns=b) for a,b in faults]
            expected=sum(any(a<=tick<b for a,b in spans) and not any(a<=tick<b for a,b in faults) for tick in range(40))/1e9
            self.assertEqual(expected,eligible_seconds(targets,stalled,'A',0,40))
