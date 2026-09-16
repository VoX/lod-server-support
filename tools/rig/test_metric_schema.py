"""metric-schema.json documents the thresholds the checkers enforce; pin them equal."""
import json,re,unittest
from pathlib import Path
import metrics,performance

HERE=Path(__file__).resolve().parent

class MetricSchemaTests(unittest.TestCase):
 def setUp(self):
  self.schema=json.loads((HERE/'metric-schema.json').read_text());self.source=(HERE/'performance.py').read_text()
 def test_sample_and_coverage_thresholds_match_performance_checker(self):
  self.assertIn('< %d)'%self.schema['minimum_duration_samples_per_measured_subject'],self.source)
  self.assertIn("/ sample['rss_scheduled'] < %s"%str(self.schema['minimum_rss_observation_fraction']).lstrip('0'),self.source)
 def test_fixed_phase_durations_match_performance_checker(self):
  self.assertIn("warmup_seconds') != %d"%self.schema['warmup_seconds'],self.source)
  self.assertIn('!= %d_000_000_000'%self.schema['measurement_seconds'],self.source)
  self.assertIn("drain_seconds', math.inf) > %d"%self.schema['drain_seconds'],self.source)
 def test_pair_protocol_and_budget_match_performance_checker(self):
  order=re.search(r'expected_order = (\[.*\])',self.source).group(1)
  self.assertEqual(self.schema['pair_order'],json.loads(order.replace("'",'"')));self.assertEqual(self.schema['pair_count'],len(self.schema['pair_order']))
  self.assertIn('len(pairs) != %d'%self.schema['pair_count'],self.source)
  budget=str(self.schema['duration_relative_regression_budget']).lstrip('0')
  self.assertEqual(self.schema['duration_relative_regression_budget'],self.schema['throughput_relative_regression_budget'])
  self.assertEqual(self.schema['duration_relative_regression_budget'],self.schema['rss_relative_regression_budget'])
  self.assertIn('floor > %s * statistics.median' % budget,self.source)
 def test_percentiles_match_metric_derivation(self):
  out=metrics.duration_metrics([],0,1,'frame')
  self.assertEqual({'frame_p%d_ms'%p for p in self.schema['percentiles']},{k for k in out if k.startswith('frame_p')})
  out=metrics.duration_metrics([],0,1,'tick')
  self.assertEqual({'tick_delay_p%d_ms'%p for p in self.schema['percentiles']},{k for k in out if k.startswith('tick_delay_p')})

if __name__=='__main__':unittest.main()
