import unittest
from check_seated_draw import check

TEXT='''[WI9-FIXTURE] ARMED pass=4 observed_two_real_proxy_draws=true native_players_absent=true first_seated=aaa second=bbb
[WI9-FIXTURE] INJECTED pass=5 uuid=aaa passenger=true real_dispatcher_push_translate=true
[WI9-FIXTURE] NEXT_PROXY_HEAD pass=5 uuid=bbb sentinel_and_matrices_restored=true
[WI9-FIXTURE] NEXT_PROXY_RETURN pass=5 uuid=bbb
[WI9-FIXTURE] TAG_HEAD pass=5 sentinel_and_matrices_restored=true
[WI9-FIXTURE] PASS_SAME_FRAME pass=5 next_starts=1 next_returns=1 tag_starts=1 tag_returns=1 outer_unwind=true crash_latched=false assertion_failed=false
'''

class SeatedDrawTest(unittest.TestCase):
    def test_same_frame_observations_pass(self):
        self.assertTrue(check(TEXT)['passed'])
    def test_missing_or_duplicated_native_fault_fails(self):
        rows=TEXT.splitlines()
        for row in rows:
            with self.subTest(row=row):
                self.assertFalse(check(TEXT.replace(row+'\n',''))['passed'])
        self.assertFalse(check(TEXT+rows[1])['passed'])
    def test_next_frame_recovery_and_wrong_subject_fail(self):
        for old,new in [('NEXT_PROXY_RETURN pass=5','NEXT_PROXY_RETURN pass=6'),
                        ('uuid=bbb','uuid=aaa'),('first_seated=aaa','first_seated=ccc')]:
            self.assertFalse(check(TEXT.replace(old,new))['passed'])
    def test_failed_stack_tag_completion_or_crash_fails(self):
        for old,new in [('sentinel_and_matrices_restored=true','sentinel_and_matrices_restored=false'),
                        ('outer_unwind=true','outer_unwind=false'),('tag_returns=1','tag_returns=0'),
                        ('crash_latched=false','crash_latched=true'),('assertion_failed=false','assertion_failed=true')]:
            self.assertFalse(check(TEXT.replace(old,new))['passed'])
        self.assertFalse(check(TEXT+'[WI9-FIXTURE] PREMISE_FAILED pass=5 missing=true')['passed'])
    def test_reordered_native_events_fail(self):
        rows=TEXT.splitlines();rows[2],rows[3]=rows[3],rows[2]
        self.assertFalse(check('\n'.join(rows))['passed'])

if __name__=='__main__':unittest.main()
