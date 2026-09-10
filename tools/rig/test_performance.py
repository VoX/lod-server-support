import copy
import unittest
import performance as p

FIELDS=('world_digest','profile_hash','fixture_hash','hardware_hash','jvm_hash','workload_hash')

def run(identifier):
    result={'run_id':identifier,'platform':'folia','required_subjects':4,'measurement_start_ns':0,'measurement_end_ns':600_000_000_000,'warmup_seconds':120,
            'sessions':[],'region_samples':[],'oracle':[],'progress_windows':[],'metrics':{},'queue_bounds_ok':True,'cleanup_complete':True,'drain_seconds':30}
    result.update({key:'frozen-'+key for key in FIELDS})
    for i in range(4):
        subject='client'+str(i)
        result['sessions'].append({'subject':subject,'connection_id':identifier+subject,'start_ns':0,'end_ns':900_000_000_000})
        result['region_samples'].append({'subject':subject,'region_identity':str(i),'context':'owning-region','owns_region':True,'start_ns':1,'end_ns':10})
        for window in range(20):
            result['oracle'].append({'id':subject+str(window),'subject':subject,'offered_ns':0,'resolved_ns':(15+window*30)*10**9,'fault_removed_ns':window*30*10**9,'actual':'body','expected':'body','expected_session':identifier+subject,'delivery_session':identifier+subject})
            result['progress_windows'].append({'subject':subject,'start_ns':window*30*10**9,'duration_seconds':30,'eligible':True,'useful_outcomes':1})
    for subject in ['server','aggregate',*['client'+str(i) for i in range(4)]]:
        result['metrics'][subject]={metric:100 for metric in p.METRICS}
        result['metrics'][subject].update(tick_samples=1200,tick_delay_samples=1200,frame_samples=1800,rss_scheduled=60,rss_observed=60,process_identity=identifier+subject)
    return result

def experiment():
    value={'preregistered':True,'calibration_frozen_before_candidate':True,'pairs':[]}
    value['arms']={arm:{'source_tree':arm+'-tree','artifact_hashes':{'fabric':arm+'-fabric','paper':arm+'-paper'}} for arm in ('baseline','candidate')}
    value.update({key:'frozen-'+key for key in FIELDS})
    value['absolute_floors']=p.calibrate([run('cal'+str(i)) for i in range(3)])
    for i,order in enumerate([['baseline','candidate'],['candidate','baseline'],['baseline','candidate']]):
        value['pairs'].append({'order':order,'baseline':run('b'+str(i)),'candidate':run('c'+str(i))})
        for arm in ('baseline','candidate'):
            value['pairs'][-1][arm]['artifact_identity']=copy.deepcopy(value['arms'][arm])
    return value

class PerformanceProtocolTest(unittest.TestCase):
    def test_candidate_cannot_masquerade_as_baseline(self):
        e=experiment();e['pairs'][0]['baseline']['artifact_identity']=e['arms']['candidate']
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_raw_observation_errors_cannot_hide_behind_sample_counts(self):
        e=experiment();e['pairs'][0]['candidate']['metrics']['server']['errors']=['RSS process identity changed']
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_fixed_three_pairs_pass(self):self.assertEqual('passed',p.evaluate(experiment())['status'])
    def test_median_alone_cannot_hide_two_failing_pairs(self):
        e=experiment()
        # Two pairs outside their baseline-scaled bound fail, even if one is much faster.
        for i,v in enumerate([0,111,111]):e['pairs'][i]['candidate']['metrics']['client0']['frame_p99_ms']=v
        self.assertEqual('failed',p.evaluate(e)['status'])
    def test_one_pair_regression_within_median_budget(self):
        e=experiment();e['pairs'][0]['candidate']['metrics']['client0']['frame_p99_ms']=200
        self.assertEqual('passed',p.evaluate(e)['status'])
    def test_throughput_direction(self):
        e=experiment()
        for pair in e['pairs']:pair['candidate']['metrics']['client0']['useful_bytes_per_second']=89
        self.assertEqual('failed',p.evaluate(e)['status'])
    def test_insufficient_samples_not_zero(self):
        e=experiment();e['pairs'][0]['candidate']['metrics']['client0']['frame_samples']=999
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_client_needs_frames_server_needs_ticks(self):
        e=experiment()
        for pair in e['pairs']:
            for arm in ('baseline','candidate'):
                pair[arm]['metrics']['server'].pop('frame_samples')
                pair[arm]['metrics']['client0'].pop('tick_samples')
        self.assertEqual('passed',p.evaluate(e)['status'])
    def test_replacement_pair_not_allowed(self):
        e=experiment();e['pairs'].append(copy.deepcopy(e['pairs'][0]))
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_missing_rss_inconclusive(self):
        e=experiment();e['pairs'][0]['baseline']['metrics']['server']['rss_observed']=56
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_oracle_detects_omitted_starved_subject_window(self):
        e=experiment();e['pairs'][0]['candidate']['progress_windows'].pop()
        self.assertEqual('failed',p.evaluate(e)['status'])
    def test_reported_progress_cannot_invent_outcomes(self):
        e=experiment();e['pairs'][0]['candidate']['progress_windows'][0]['useful_outcomes']=99
        self.assertEqual('failed',p.evaluate(e)['status'])
    def test_aggregate_cannot_replace_subject_metrics(self):
        e=experiment()
        for pair in e['pairs']:
            for arm in ('baseline','candidate'):pair[arm]['metrics'].pop('client3')
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_shortened_warmup_refuses_measurement(self):
        e=experiment();e['pairs'][0]['candidate']['warmup_seconds']=119
        self.assertEqual('inconclusive',p.evaluate(e)['status'])
    def test_sequential_reconnect_is_not_simultaneous_identity_reuse(self):
        value=run('reconnect')
        value['sessions'][0]['end_ns']=300_000_000_000
        value['sessions'].append({'subject':'client0','connection_id':'successor','start_ns':305_000_000_000,'end_ns':900_000_000_000})
        self.assertNotIn('sessions never simultaneously registered',p.correctness(value))
        value['sessions'][-1]['start_ns']=299_000_000_000
        self.assertIn('same subject has overlapping connection identities',p.correctness(value))
    def test_unstable_calibration_cannot_widen_budget(self):
        baselines=[run('cal'+str(i)) for i in range(3)]
        baselines[2]['metrics']['server']['peak_rss_bytes']=112
        with self.assertRaisesRegex(ValueError,'unstable'):p.calibrate(baselines)

if __name__=='__main__':unittest.main()
