"""The retry rule: only a named, unexpired cause may be retried."""
import datetime
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import flake_gate

TODAY = datetime.date(2026, 11, 13)
SUMMARY = '''[04:13:00] [Server thread/INFO] (Minecraft) ========= 86 GAME TESTS COMPLETE IN 4.318 min ======
\x1b[m\x1b[34m[04:13:00]\x1b[m (Minecraft) \x1b[0m2 required tests failed :(
\x1b[m\x1b[34m[04:13:00]\x1b[m (Minecraft) \x1b[0m   - lss-test:two_player_fan_out: A=3 B=1 on tick 1202
\x1b[m\x1b[34m[04:13:00]\x1b[m (Minecraft) \x1b[0m   - lss-test:region_freshness: frames=0 on tick 1202
[04:13:00] [Server thread/INFO] (Minecraft) ====================================================
[04:13:00] [Server thread/INFO] (Minecraft) 2 required tests failed :(
[04:13:00] [Server thread/INFO] (Minecraft)    - lss-test:two_player_fan_out: A=3 B=1 on tick 1202
[04:13:00] [Server thread/INFO] (Minecraft)    - lss-test:region_freshness: frames=0 on tick 1202
[04:13:00] [Server thread/INFO] (Minecraft) ====================================================
[04:13:00] [Server thread/INFO] (Minecraft)    - not:a_failure_row: outside the summary
'''


def entry(**fields):
    base = dict(id='fan-out', scope='tier2', test='two_player_fan_out', signature=r'A=\d+ B=\d+',
                expires='2026-12-31', cause='c', catalog='d')
    base.update(fields)
    return base


class FlakeGate(unittest.TestCase):
    def test_summary_rows_are_read_once_per_test_and_only_inside_the_summary(self):
        self.assertEqual(flake_gate.failed_tests(SUMMARY),
                         {'lss-test:two_player_fan_out': 'A=3 B=1 on tick 1202',
                          'lss-test:region_freshness': 'frames=0 on tick 1202'})

    def test_one_unknown_failure_denies_the_retry(self):
        allowed, lines = flake_gate.decide([entry()], 'tier2', SUMMARY, TODAY)
        self.assertFalse(allowed)
        self.assertIn('region_freshness', '\n'.join(lines))

    def test_every_failure_known_allows_the_retry(self):
        entries = [entry(), entry(id='freshness', test='region_freshness', signature='frames=0')]
        allowed, lines = flake_gate.decide(entries, 'tier2', SUMMARY, TODAY)
        self.assertTrue(allowed)
        self.assertIn('fan-out', '\n'.join(lines))

    def test_a_different_assertion_in_a_known_test_is_a_regression(self):
        entries = [entry(), entry(id='freshness', test='region_freshness', signature='frames=7')]
        self.assertFalse(flake_gate.decide(entries, 'tier2', SUMMARY, TODAY)[0])

    def test_an_expired_entry_allows_nothing(self):
        entries = [entry(expires='2026-11-12'), entry(id='freshness', test='region_freshness', signature='frames=0')]
        allowed, lines = flake_gate.decide(entries, 'tier2', SUMMARY, TODAY)
        self.assertFalse(allowed)
        self.assertIn('expired', '\n'.join(lines))

    def test_another_scope_does_not_apply(self):
        entries = [entry(scope='tier3'), entry(id='freshness', scope='tier3', test='region_freshness', signature='frames=0')]
        self.assertFalse(flake_gate.decide(entries, 'tier2', SUMMARY, TODAY)[0])

    def test_a_crash_needs_a_crash_entry(self):
        crash = 'java.lang.NullPointerException\n\tat net.minecraft.TicketStorage.purgeStaleTickets(TicketStorage.java:1)\n'
        named = entry(id='npe', scope='tier3', test=None, signature=r'NullPointerException[\s\S]*purgeStaleTickets')
        named.pop('test')
        self.assertTrue(flake_gate.decide([named], 'tier3', crash, TODAY)[0])
        self.assertFalse(flake_gate.decide([named], 'tier3', 'BUILD FAILED: something else', TODAY)[0])
        # A test-scoped entry never excuses a crash.
        self.assertFalse(flake_gate.decide([entry(scope='tier3')], 'tier3', crash, TODAY)[0])

    def test_the_checked_in_catalogue_loads_and_every_entry_names_its_cause(self):
        entries = flake_gate.load(flake_gate.ROOT / 'config/test-flakes.json')
        self.assertTrue(all(len(e['cause']) > 20 for e in entries))


if __name__ == '__main__':
    unittest.main()
