import copy
import unittest
from assemble_metrics import assemble

class AssembleMetricsTests(unittest.TestCase):
 def inputs(self):
  owner={'pid':1,'start':'1','boot':'boot'};other={'pid':2,'start':'2','boot':'boot'}
  target={'id':'t1','subject':'A','actual':'gold','expected':'gold','offered_ns':0,'resolved_ns':1_000_000_000,'delivery_session':'s1'}
  target2=dict(target,id='t2')
  commit={'event':'target_committed','id':'t1','run_id':'run','subject':'A','connection_id':'s1','lease_active':True,'body_id':'body1','body_bytes':100,'resolved_ns':1_000_000_000}
  rows=[commit,dict(commit,id='t2'),{'event':'frame','run_id':'run','subject':'A','start_ns':0,'end_ns':10_000_000}]
  rss=[]
  for at,left,right in [(0,200,100),(1_000_000_000,100,200)]:
   for launch,identity,value in [('server',owner,left),('client-A',other,right)]:rss.append({'subject':launch,'process_identity':identity,'rss_bytes':value,'missing':False,'scheduled_ns':at})
  return dict(run_id='run',consumers={'A':rows},tick_rows=[],rss_rows=rss,oracle=[target,target2],faults=[],owners={'server':owner,'A':other},launch_ids={'server':'server','A':'client-A'},start_ns=0,end_ns=2_000_000_000)
 def test_same_body_matching_two_targets_counts_once_and_rss_is_simultaneous(self):
  result=assemble(**self.inputs())
  self.assertEqual(result['A']['useful_body_bytes'],100)
  self.assertEqual(result['A']['useful_body_count'],1)
  self.assertEqual(result['aggregate']['peak_rss_bytes'],300)
  self.assertEqual(result['aggregate']['rss_observed'],2)
 def test_missing_identity_and_mixed_run_rejected(self):
  for mutation in ['body','run','session']:
   inputs=self.inputs();row=inputs['consumers']['A'][0]
   if mutation=='body':del row['body_id']
   elif mutation=='run':row['run_id']='old'
   else:row['connection_id']='stale'
   with self.assertRaises(ValueError):assemble(**inputs)
 def test_missing_rss_subject_is_not_filled_with_zero(self):
  inputs=self.inputs();inputs['rss_rows'].pop()
  result=assemble(**inputs)
  self.assertEqual(result['aggregate']['rss_scheduled'],2)
  self.assertEqual(result['aggregate']['rss_observed'],1)
 def test_changed_process_and_duplicate_rss_fail_closed(self):
  inputs=self.inputs();inputs['rss_rows'][0]['process_identity']={'pid':9}
  result=assemble(**inputs);self.assertIn('RSS process identity changed',result['server']['errors'])
  inputs=self.inputs();inputs['rss_rows'].append(copy.deepcopy(inputs['rss_rows'][0]))
  with self.assertRaises(ValueError):assemble(**inputs)
