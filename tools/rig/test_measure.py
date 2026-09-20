import os
import unittest
from unittest.mock import patch
from rig import identity
from measure import Sampler,rss
class RssTest(unittest.TestCase):
    def test_live_identity(self):self.assertGreater(rss(identity(os.getpid()))['rss_bytes'],0)
    def test_stale_pid_is_missing_not_zero(self):
        owner=identity(os.getpid());owner['start']='-1'
        self.assertTrue(rss(owner)['missing'])
        self.assertNotIn('rss_bytes',rss(owner))
    def test_missed_scheduled_samples_are_preserved(self):
        sampler=Sampler({'client':identity(os.getpid())},0)
        rows=sampler.sample(2_000_000_000)
        self.assertEqual(3,len(rows));self.assertEqual(2,sum(row['missing'] for row in rows))
        self.assertEqual([],sampler.sample(2_500_000_000))
