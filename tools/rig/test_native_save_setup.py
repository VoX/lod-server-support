"""Pure acceptance controls. Synthetic rows are not native execution evidence."""
import copy
import unittest
from native_save_setup import check, CONFIG, CONTENT, FLAG, KEY, PROPERTY, SETUP


def recipe(platform='folia'):
    return ({'id': 'unchanged-logical-row', 'checker': 'concurrent-sources',
             'server_platform': platform, KEY: copy.deepcopy(SETUP)},
            {'launches': [{'id': 'server', 'argv': ['java', FLAG+'200', '-jar', 'server.jar']},
                          {'id': 'client-A', 'argv': ['java']}],
             'generated_files': {CONFIG: CONTENT}, 'stage_files': []})


def evidence(platform='folia'):
    return [{'event': 'native_save_setup', 'run_id': 'this-run', 'interval_ticks': 200,
             'max_chunks_per_tick': 24, 'dimension': 'minecraft:overworld', 'world_name': 'world',
             'world_uuid': '12345678-1234-1234-1234-123456789abc',
             'owner_kind': 'owning-region' if platform == 'folia' else 'server-thread',
             'owner_identity': 'region:42' if platform == 'folia' else 'server-thread:7',
             'owns_region': True, 'observed_ns': 50, 'time_ns': 51},
            {'event': 'source_preconditions_ready', 'time_ns': 100},
            {'event': 'workload_started', 'time_ns': 200}]


class NativeSaveSetupTest(unittest.TestCase):
    def test_valid_both_native_platforms(self):
        for platform in ('paper', 'folia'):
            self.assertEqual(check(*recipe(platform), evidence(platform), 'this-run'), [])

    def test_old_absent_behavior(self):
        sc, rt = recipe(); del sc[KEY]; rt['launches'][0]['argv'].remove(FLAG+'200')
        self.assertEqual(check(sc, rt, [], 'historical'), [])
        self.assertEqual(check(sc, rt, evidence(), 'historical'), [])

    def test_strict_schema(self):
        variants = [None, {}, {'interval_ticks': 200}, dict(SETUP, schema_version=True),
                    dict(SETUP, interval_ticks=True), dict(SETUP, max_chunks_per_tick='24'),
                    dict(SETUP, variant='other'), dict(SETUP, extra=1)]
        for value in variants:
            with self.subTest(value=value):
                sc, rt = recipe(); sc[KEY] = value
                self.assertTrue(check(sc, rt, evidence(), 'this-run'))

    def test_flag_missing_wrong_duplicate_bare_and_client(self):
        for tokens in ([], [FLAG+'100'], [FLAG], [PROPERTY], [FLAG+'200', FLAG+'200'],
                       [PROPERTY, FLAG+'200'], [FLAG+'200', PROPERTY]):
            sc, rt = recipe(); rt['launches'][0]['argv'] = ['java'] + tokens
            self.assertTrue(check(sc, rt, evidence(), 'this-run'), tokens)
        sc, rt = recipe(); rt['launches'][1]['argv'].append(FLAG+'200')
        self.assertTrue(check(sc, rt, evidence(), 'this-run'))
        for token in (PROPERTY, FLAG+'200', FLAG+'wrong'):
            sc, rt = recipe(); del sc[KEY]; rt['launches'][0]['argv'] = ['java', token]
            self.assertTrue(check(sc, rt, evidence(), 'this-run'))

    def test_platform_checker_and_generated_config(self):
        for key, value in [('server_platform', 'fabric'), ('server_platform', 'other'), ('checker', 'other')]:
            sc, rt = recipe(); sc[key] = value
            self.assertTrue(check(sc, rt, evidence(), 'this-run'))
        for value in ('', CONTENT.replace('200', 'default'), CONTENT.replace('24', '25')):
            sc, rt = recipe(); rt['generated_files'][CONFIG] = value
            self.assertTrue(check(sc, rt, evidence(), 'this-run'))
        sc, rt = recipe(); rt['stage_files'].append({'target': CONFIG})
        self.assertTrue(check(sc, rt, evidence(), 'this-run'))

    def test_resolved_values_native_identity_and_order(self):
        for key, value in [('interval_ticks', 6000), ('interval_ticks', True),
                           ('max_chunks_per_tick', 0), ('max_chunks_per_tick', '24'),
                           ('run_id', 'prior-run'), ('dimension', 'minecraft:the_end'),
                           ('world_name', ''), ('world_uuid', 'invalid'), ('owner_identity', ''),
                           ('owner_kind', 'server-thread'), ('owns_region', False),
                           ('observed_ns', 52), ('observed_ns', 0), ('time_ns', 101)]:
            with self.subTest(key=key, value=value):
                rows = evidence(); rows[0][key] = value
                self.assertTrue(check(*recipe(), rows, 'this-run'))
        for key in ('observed_ns', 'time_ns', 'world_uuid', 'interval_ticks', 'owns_region'):
            rows = evidence(); del rows[0][key]
            self.assertTrue(check(*recipe(), rows, 'this-run'))
        rows = evidence(); rows[-1]['time_ns'] = 49
        self.assertTrue(check(*recipe(), rows, 'this-run'))

    def test_absent_repeated_failed_and_missing_readiness(self):
        rows = evidence()
        variants = [rows[1:], [rows[0]] + rows, [rows[0], rows[-1]],
                    rows + [rows[1]], rows + [{'event': 'native_save_setup_failed'}]]
        for value in variants:
            self.assertTrue(check(*recipe(), value, 'this-run'))


if __name__ == '__main__':
    unittest.main()
