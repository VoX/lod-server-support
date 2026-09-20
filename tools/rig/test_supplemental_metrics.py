import unittest
from supplemental_metrics import assemble,gc_delta
class SupplementalTests(unittest.TestCase):
    def test_latency_keeps_drain_outcomes_in_offered_cohort(self):
        targets=[dict(subject='A',offered_ns=100,resolved_ns=300)]
        result=assemble(targets,{'A':[]},[],0,200)
        self.assertEqual(1,result['subjects']['A']['latency_samples'])
        self.assertEqual(.0002,result['subjects']['A']['offer_to_commit_p95_ms'])
    def test_gc_unknown_reset_or_missing_is_not_zero(self):
        for values in ([1,-1],[3,1],[1]):
            rows=[dict(event='jvm_gc',time_ns=i,gc_count=v,gc_time_ms=v) for i,v in enumerate(values)]
            self.assertIsNone(gc_delta(rows,0,10)['gc_count_delta'])
        self.assertIsNone(gc_delta([],0,10)['gc_time_ms_delta'])
    def test_actual_gc_observation_interval_retained(self):
        rows=[dict(event='product_metrics',time_ns=t,jvm=dict(gc_count=c,gc_time_ms=c*10)) for t,c in ((2,1),(8,3))]
        result=gc_delta(rows,0,10);self.assertEqual(2,result['gc_count_delta']);self.assertEqual(20,result['gc_time_ms_delta']);self.assertEqual(2,result['observed_start_ns']);self.assertEqual(8,result['observed_end_ns'])
