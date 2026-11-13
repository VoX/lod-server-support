"""Offline tests for the real-stack matrix: the matrix file, hash verification, log parsing for
every check (on excerpts captured from real runs, in fixtures/), port and display selection,
process ownership, the screenshot converter and verdict assembly.

Run: python3 -m unittest discover -s tools/realstack
Nothing here touches the network; every download function is replaced by one that fails.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import checks  # noqa: E402
import fetch  # noqa: E402
import launch  # noqa: E402
import procs  # noqa: E402
import realstack  # noqa: E402
import stackfile  # noqa: E402
import xwdpng  # noqa: E402

FIXTURES = HERE / 'fixtures'
REPO_ROOT = HERE.parents[1]
PLAYER = 'RealStack'
OWN_PATHS = ('/home/user/.cache/lss-realstack', '/home/user/.local/state/lss-realstack/runs')


def fixture(name: str) -> str:
    """A captured log excerpt."""
    return (FIXTURES / name).read_text()


def no_network(*_args, **_kwargs):
    """Stand-in for every download function."""
    raise AssertionError('a test tried to use the network')


class OfflineCase(unittest.TestCase):
    """Base class: the network is unreachable for the duration of each test."""

    def setUp(self) -> None:
        self.saved = (fetch.http_get, fetch.http_json)
        fetch.http_get = no_network
        fetch.http_json = no_network
        self.addCleanup(self.restore)

    def restore(self) -> None:
        """Put the real download functions back."""
        fetch.http_get, fetch.http_json = self.saved

    def temp(self) -> Path:
        """A temporary directory removed after the test."""
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        return Path(holder.name)


class StackFileTest(OfflineCase):
    """The matrix file: the committed rows, validation messages and the --lss selector."""

    def test_committed_matrix_is_valid(self) -> None:
        document = stackfile.load()
        self.assertEqual(stackfile.validate(document), [])
        ids = [row['id'] for row in document['rows']]
        self.assertIn('fabric-26.3-sodium-voxy-xaero', ids)
        self.assertIn('neoforge-1.21.1-c2me-scalablelux', ids)

    def test_protocol_matches_the_source(self) -> None:
        source = REPO_ROOT / 'common/src/main/java/dev/vox/lss/common/LSSConstants.java'
        if not source.is_file():
            self.skipTest('LSSConstants.java is not in this checkout')
        match = re.search(r'int PROTOCOL_VERSION = (\d+);', source.read_text())
        self.assertIsNotNone(match)
        self.assertEqual(stackfile.load()['lss']['protocol'], int(match.group(1)))

    def test_every_pin_is_complete(self) -> None:
        for row in stackfile.load()['rows']:
            for side in ('server', 'client'):
                for pin in row[side]['mods']:
                    self.assertEqual(stackfile.validate_pin(pin), [], pin.get('slug'))

    def test_problems_are_named(self) -> None:
        good = stackfile.load()
        cases = {
            'sha512': lambda row: row['client']['mods'][0].update(sha512='abc'),
            'file must be a plain': lambda row: row['client']['mods'][0].update(file='../x.jar'),
            'status must be': lambda row: row.update(status='maybe'),
            'blocked_reason': lambda row: row.update(status='blocked'),
            'unknown check': lambda row: row['checks'].append('vibes'),
            'server.platform': lambda row: row['server'].update(platform='spigot'),
            'client.loader': lambda row: row['client'].update(loader='quilt'),
            'loader_version': lambda row: row['server'].pop('loader_version'),
            'version_id is missing': lambda row: row['server']['mods'][0].pop('version_id'),
            'version_id must be': lambda row: row['server']['mods'][0].update(version_id='../x'),
            'client.lss': lambda row: row['client'].pop('lss'),
            'id must be': lambda row: row.update(id='Has Spaces'),
        }
        for expected, damage in cases.items():
            document = copy.deepcopy(good)
            damage(document['rows'][0])
            problems = stackfile.validate(document)
            self.assertTrue(any(expected in problem for problem in problems), (expected, problems))

    def test_duplicate_row_and_bad_schema(self) -> None:
        document = copy.deepcopy(stackfile.load())
        document['rows'].append(copy.deepcopy(document['rows'][0]))
        document['schema'] = 2
        problems = stackfile.validate(document)
        self.assertTrue(any('duplicate id' in problem for problem in problems))
        self.assertTrue(any('schema' in problem for problem in problems))

    def test_neoforge_needs_installer_hash(self) -> None:
        document = copy.deepcopy(stackfile.load())
        row = stackfile.find_row(document, 'neoforge-1.21.1-c2me-scalablelux')
        row['server']['neoforge']['installer_sha256'] = 'short'
        self.assertTrue(any('installer_sha256' in problem for problem in stackfile.validate(document)))

    def test_blocked_row_with_reason_is_valid(self) -> None:
        document = copy.deepcopy(stackfile.load())
        document['rows'][0].update(status='blocked', blocked_reason='Voxy has no build yet')
        self.assertEqual(stackfile.validate(document), [])

    def test_find_row(self) -> None:
        document = stackfile.load()
        self.assertEqual(stackfile.find_row(document, document['rows'][0]['id']), document['rows'][0])
        with self.assertRaises(stackfile.StackError):
            stackfile.find_row(document, 'nope')

    def test_lss_selector(self) -> None:
        self.assertEqual(stackfile.parse_lss_selector('published:0.21.0'), ('published', '0.21.0'))
        self.assertEqual(stackfile.parse_lss_selector('published:0.22.0-beta.1'),
                         ('published', '0.22.0-beta.1'))
        self.assertEqual(stackfile.parse_lss_selector('dir:/some/gate/jars'),
                         ('dir', '/some/gate/jars'))
        for bad in ('0.21.0', 'published:', 'published:latest', 'url:https://example.org/x.jar'):
            with self.assertRaises(stackfile.StackError):
                stackfile.parse_lss_selector(bad)

    def test_choose_built_jar(self) -> None:
        gate = ['lod-server-support-fabric-0.21.0+26.3.jar', 'paper-0.21.0.jar',
                'lod-server-support-neoforge-0.21.0+26.3.jar',
                'lod-server-support-paper-0.21.0+26.3.jar',
                'voxy-server-side-fabric-0.21.0+26.3.jar']
        self.assertEqual(stackfile.choose_built_jar(gate, 'lod-server-support-fabric', '26.3'),
                         'lod-server-support-fabric-0.21.0+26.3.jar')
        self.assertEqual(stackfile.choose_built_jar(['lod-server-support-fabric.jar'],
                                                    'lod-server-support-fabric', '26.3'),
                         'lod-server-support-fabric.jar')
        with self.assertRaises(stackfile.StackError):   # a jar of another Minecraft line
            stackfile.choose_built_jar(gate, 'lod-server-support-fabric', '1.21.1')
        with self.assertRaises(stackfile.StackError):   # two candidates
            stackfile.choose_built_jar(gate + ['lod-server-support-fabric-0.22.0+26.3.jar'],
                                       'lod-server-support-fabric', '26.3')


class FetchTest(OfflineCase):
    """Hash verification, the cache, the host allow-list and library resolution."""

    def test_digests(self) -> None:
        path = self.temp() / 'file.bin'
        path.write_bytes(b'terrain')
        self.assertEqual(fetch.file_digest(path, 'sha512'), hashlib.sha512(b'terrain').hexdigest())
        self.assertTrue(fetch.file_matches(path, 'sha1', hashlib.sha1(b'terrain').hexdigest().upper()))
        self.assertFalse(fetch.file_matches(path, 'sha1', '0' * 40))
        self.assertFalse(fetch.file_matches(path.with_name('missing'), 'sha1', '0' * 40))

    def test_write_verified_refuses_a_wrong_hash(self) -> None:
        dest = self.temp() / 'sub' / 'mod.jar'
        with self.assertRaises(fetch.FetchError):
            fetch.write_verified(dest, b'tampered', 'sha512', hashlib.sha512(b'real').hexdigest(), 'x')
        self.assertFalse(dest.exists())
        self.assertFalse(dest.with_name('mod.jar.part').exists())
        fetch.write_verified(dest, b'real', 'sha512', hashlib.sha512(b'real').hexdigest(), 'x')
        self.assertEqual(dest.read_bytes(), b'real')

    def test_verified_file_is_never_downloaded_again(self) -> None:
        dest = self.temp() / 'lib.jar'
        dest.write_bytes(b'library')
        sha1 = hashlib.sha1(b'library').hexdigest()
        # http_get raises in these tests, so 'cached' proves no download was attempted.
        self.assertEqual(fetch.fetch_verified('https://libraries.minecraft.net/x', dest, 'sha1', sha1),
                         'cached')

    def test_corrupt_cached_file_is_replaced(self) -> None:
        dest = self.temp() / 'lib.jar'
        dest.write_bytes(b'corrupt')
        fetch.http_get = lambda url, timeout=180: b'library'
        how = fetch.fetch_verified('https://libraries.minecraft.net/x', dest, 'sha1',
                                   hashlib.sha1(b'library').hexdigest())
        self.assertEqual(how, 'downloaded')
        self.assertEqual(dest.read_bytes(), b'library')

    def test_download_with_wrong_content_is_refused(self) -> None:
        dest = self.temp() / 'lib.jar'
        fetch.http_get = lambda url, timeout=180: b'something else'
        with self.assertRaises(fetch.FetchError):
            fetch.fetch_verified('https://libraries.minecraft.net/x', dest, 'sha1',
                                 hashlib.sha1(b'library').hexdigest())
        self.assertFalse(dest.exists())

    def test_seed_is_used_only_when_its_hash_matches(self) -> None:
        folder = self.temp()
        good, bad, dest = folder / 'good', folder / 'bad', folder / 'out'
        good.write_bytes(b'asset')
        bad.write_bytes(b'not the asset')
        sha1 = hashlib.sha1(b'asset').hexdigest()
        self.assertEqual(fetch.fetch_verified('https://x.invalid/', dest, 'sha1', sha1,
                                              seeds=(bad, good)), 'seeded')
        self.assertEqual(dest.read_bytes(), b'asset')

    def test_only_official_hosts(self) -> None:
        fetch.check_url_allowed('https://cdn.modrinth.com/data/x/versions/y/file.jar')
        fetch.check_url_allowed('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')
        for url in ('http://cdn.modrinth.com/data/x.jar', 'https://example.org/mod.jar',
                    'https://cdn.modrinth.com.evil.example/x.jar', 'file:///etc/passwd'):
            with self.assertRaises(fetch.FetchError):
                fetch.check_url_allowed(url)

    def test_maven_path(self) -> None:
        self.assertEqual(fetch.maven_path('net.fabricmc:fabric-loader:0.20.1'),
                         'net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar')
        self.assertEqual(fetch.maven_path('org.lwjgl:lwjgl:3.4.3:natives-linux'),
                         'org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-linux.jar')
        with self.assertRaises(fetch.FetchError):
            fetch.maven_path('not-a-coordinate')

    def test_rules(self) -> None:
        self.assertTrue(fetch.rules_allow(None))
        self.assertTrue(fetch.rules_allow([{'action': 'allow', 'os': {'name': 'linux'}}]))
        self.assertFalse(fetch.rules_allow([{'action': 'allow', 'os': {'name': 'osx'}}]))
        self.assertFalse(fetch.rules_allow([{'action': 'allow'},
                                            {'action': 'disallow', 'os': {'name': 'linux'}}]))
        self.assertTrue(fetch.rules_allow([{'action': 'allow'},
                                           {'action': 'disallow', 'os': {'name': 'osx'}}]))
        self.assertFalse(fetch.rules_allow([{'action': 'allow', 'os': {'arch': 'x86'}}]))
        self.assertFalse(fetch.rules_allow([{'action': 'allow', 'features': {'is_demo_user': True}}]))

    def test_vanilla_libraries_keep_linux_only(self) -> None:
        def library(name: str, rules=None) -> dict:
            """A library entry as Mojang's version document lists it."""
            entry = {'name': name, 'downloads': {'artifact': {
                'path': fetch.maven_path(name), 'url': 'https://libraries.minecraft.net/x',
                'sha1': '0' * 40}}}
            if rules:
                entry['rules'] = rules
            return entry
        document = {'libraries': [
            library('org.lwjgl:lwjgl:3.4.3'),
            library('org.lwjgl:lwjgl:3.4.3:natives-linux', [{'action': 'allow', 'os': {'name': 'linux'}}]),
            library('org.lwjgl:lwjgl:3.4.3:natives-windows',
                    [{'action': 'allow', 'os': {'name': 'windows'}}]),
            library('ca.weblite:java-objc-bridge:1.1', [{'action': 'allow', 'os': {'name': 'osx'}}])]}
        names = [entry['name'] for entry in fetch.vanilla_libraries(document)]
        self.assertEqual(names, ['org.lwjgl:lwjgl:3.4.3', 'org.lwjgl:lwjgl:3.4.3:natives-linux'])

    def test_loader_library_replaces_the_games(self) -> None:
        loader = [{'name': 'org.ow2.asm:asm:9.10.1'}]
        game = [{'name': 'org.ow2.asm:asm:9.6'}, {'name': 'com.google.code.gson:gson:2.13.1'}]
        merged = [entry['name'] for entry in fetch.merge_libraries(loader, game)]
        self.assertEqual(merged, ['org.ow2.asm:asm:9.10.1', 'com.google.code.gson:gson:2.13.1'])

    def test_match_lss_version(self) -> None:
        listing = [{'version_number': 'v0.21.0+fabric+mc26.3'}, {'version_number': 'v0.20.0+fabric+mc26.3'},
                   {'version_number': 'v0.21.0-beta.1+fabric+mc26.3'}]
        self.assertEqual(fetch.match_lss_version(listing, '0.21.0')['version_number'],
                         'v0.21.0+fabric+mc26.3')
        self.assertEqual(fetch.match_lss_version(listing, '0.21.0-beta.1')['version_number'],
                         'v0.21.0-beta.1+fabric+mc26.3')
        with self.assertRaises(fetch.FetchError):
            fetch.match_lss_version(listing, '0.19.0')

    def test_pin_refuses_names_that_are_not_plain(self) -> None:
        def version(identifier: str, filename: str) -> dict:
            """A Modrinth version with one primary file."""
            return {'id': identifier, 'project_id': 'AANobbMI', 'version_number': '1.0',
                    'files': [{'filename': filename, 'primary': True, 'hashes': {'sha512': 'a' * 128}}]}
        self.assertEqual(fetch.pin_from_version('sodium', version('bAZQdGpg', 'sodium.jar'))['file'],
                         'sodium.jar')
        for identifier, filename in (('bAZQdGpg', '../../evil.jar'), ('../up', 'sodium.jar'),
                                     ('bAZQdGpg', 'sodium.zip')):
            with self.assertRaises(fetch.FetchError):
                fetch.pin_from_version('sodium', version(identifier, filename))

    def test_pinned_mod_from_cache_needs_no_network(self) -> None:
        cache = self.temp()
        pin = {'slug': 'sodium', 'version_id': 'abc', 'file': 'sodium.jar',
               'sha512': hashlib.sha512(b'sodium').hexdigest()}
        dest = cache / 'modrinth' / 'abc' / 'sodium.jar'
        dest.parent.mkdir(parents=True)
        dest.write_bytes(b'sodium')
        self.assertEqual(fetch.fetch_pinned_mod(pin, cache), (dest, 'cached'))

    def test_pin_that_disagrees_with_modrinth_is_refused(self) -> None:
        cache = self.temp()
        pin = {'slug': 'sodium', 'version_id': 'abc', 'file': 'sodium.jar',
               'sha512': hashlib.sha512(b'sodium').hexdigest()}
        fetch.http_json = lambda url: {'files': [{
            'filename': 'sodium.jar', 'url': 'https://cdn.modrinth.com/data/p/versions/abc/sodium.jar',
            'hashes': {'sha512': hashlib.sha512(b'republished').hexdigest()}}]}
        with self.assertRaises(fetch.FetchError):
            fetch.fetch_pinned_mod(pin, cache)
        fetch.http_json = lambda url: {'files': [{
            'filename': 'sodium.jar', 'url': 'https://cdn.modrinth.com/data/p/versions/abc/sodium.jar',
            'hashes': {'sha512': pin['sha512']}}]}
        fetch.http_get = lambda url, timeout=180: b'sodium'
        path, how = fetch.fetch_pinned_mod(pin, cache)
        self.assertEqual((path.read_bytes(), how), (b'sodium', 'downloaded'))

    def test_free_space_guard(self) -> None:
        with self.assertRaises(fetch.FetchError):
            fetch.require_free_space(minimum=1 << 62)
        self.assertGreater(fetch.require_free_space(minimum=1), 0)


class LogParsingTest(OfflineCase):
    """Splitting logs into records and reading an `lsslod diag` block."""

    def test_records_and_continuation_lines(self) -> None:
        entries = checks.parse_entries(fixture('fabric-26.3-client.log'))
        narrator = next(entry for entry in entries if 'loading the narrator' in entry.message)
        self.assertEqual(narrator.level, 'ERROR')
        self.assertGreater(len(narrator.lines), 3)
        self.assertIn('NarratorLinux', narrator.text())

    def test_ansi_colours_and_logger_field(self) -> None:
        line = '\x1b[m\x1b[32m[22:18:01.165] [main/INFO] [mixin/]: SpongePowered MIXIN Subsystem'
        entry = checks.parse_entries(line)[0]
        self.assertEqual((entry.thread, entry.level, entry.logger), ('main', 'INFO', 'mixin/'))
        self.assertTrue(entry.message.startswith('SpongePowered'))

    def test_system_chat_prefix_is_dropped(self) -> None:
        entry = checks.parse_entries(
            '[22:13:53] [Server thread/INFO]: System chat: RealStack joined the game')[0]
        self.assertEqual(entry.message, 'RealStack joined the game')

    def test_uncaught_exception_is_an_error_record(self) -> None:
        text = ('[10:00:00] [main/INFO]: Loading\n'
                'Exception in thread "LSS Worker" java.lang.IllegalStateException: boom\n'
                '\tat dev.vox.lss.Something.run(Something.java:1)\n')
        entries = checks.parse_entries(text)
        self.assertEqual([entry.level for entry in entries], ['INFO', 'ERROR'])
        self.assertEqual(len(checks.lss_entries(entries, ('ERROR',))), 1)

    def test_diag_block(self) -> None:
        for prefix in ('fabric-26.3', 'neoforge-1.21.1'):
            diag = checks.parse_diag(fixture(f'{prefix}-diag.txt'))
            self.assertTrue(diag['present'], prefix)
            self.assertIn('enabled=true', diag['fields']['Config'])
            self.assertGreater(checks.counter(diag['fields']['Throughput'], 'sent'), 0)
            self.assertEqual(checks.counter(diag['fields']['Dialects'], 'v20'), 1)
            self.assertGreater(checks.counter(diag['players'][PLAYER], 'sent'), 0)

    def test_last_diag_block_wins(self) -> None:
        first = fixture('fabric-26.3-diag.txt')
        second = re.sub(r'sent=\d+', 'sent=7', first)
        diag = checks.parse_diag(first + second)
        self.assertEqual(checks.counter(diag['players'][PLAYER], 'sent'), 7)

    def test_counter_is_exact(self) -> None:
        line = 'sq=0/1024, psync=0, pgen=0, sent=3148 (108.5 MB), resent=9'
        self.assertEqual(checks.counter(line, 'sent'), 3148)
        self.assertIsNone(checks.counter(line, 'missing'))
        self.assertIsNone(checks.counter(None, 'sent'))

    def test_no_diag(self) -> None:
        self.assertFalse(checks.parse_diag('[10:00:00] [Server thread/INFO]: Unknown command')['present'])


class CheckTest(OfflineCase):
    """Each check on logs captured from real runs, then with one thing taken away."""

    def material(self, prefix: str) -> dict:
        """What a run collects, built from one set of captured excerpts."""
        server = fixture(f'{prefix}-server.log')
        cut = server.index(f'{PLAYER} lost connection') if f'{PLAYER} lost connection' in server \
            else len(server)
        return {'server_log': server, 'observed_server_log': server[:server.rindex('\n', 0, cut) + 1],
                'client_log': fixture(f'{prefix}-client.log'), 'diag': fixture(f'{prefix}-diag.txt'),
                'player': PLAYER, 'protocol': 20, 'crash_files': [], 'client_alive_at_end': True,
                'own_paths': OWN_PATHS,
                'screenshot': {'path': 'evidence/client.png', 'width': 1280, 'height': 720,
                               'bytes': 300000, 'distinct_colours': 2000,
                               'windows': ['Minecraft* 26.3 - Multiplayer (3rd-party Server)']}}

    def results(self, material: dict) -> dict:
        """Every check's result by name."""
        names = list(stackfile.KNOWN_CHECKS)
        return {result.name: result for result in checks.run_checks(names, material)}

    def test_real_runs_pass_every_check(self) -> None:
        for prefix in ('fabric-26.3', 'neoforge-1.21.1'):
            for name, result in self.results(self.material(prefix)).items():
                self.assertTrue(result.passed, (prefix, name, result.message))
                self.assertTrue(result.evidence, (prefix, name))

    def test_client_without_lss_fails_the_handshake(self) -> None:
        results = self.results(self.material('fabric-26.3-nolss'))
        self.assertFalse(results['handshake'].passed)
        self.assertIn('no LSS handshake', results['handshake'].message)
        self.assertFalse(results['columns_sent'].passed)
        self.assertTrue(results['server_started'].passed)
        self.assertTrue(results['client_clean'].passed)

    def test_server_started_needs_each_witness(self) -> None:
        material = self.material('fabric-26.3')
        without_done = re.sub(r'.*Done \(.*\n', '', material['server_log'])
        self.assertIn('"Done"', checks.check_server_started(without_done, material['diag']).message)
        without_service = re.sub(r'.*Starting LSS LOD request processing service.*\n', '',
                                 material['server_log'])
        self.assertIn('request service',
                      checks.check_server_started(without_service, material['diag']).message)
        disabled = material['diag'].replace('enabled=true', 'enabled=false')
        self.assertFalse(checks.check_server_started(material['server_log'], disabled).passed)
        self.assertFalse(checks.check_server_started(material['server_log'], '').passed)

    def test_lss_error_in_server_log_fails(self) -> None:
        material = self.material('neoforge-1.21.1')
        by_logger = material['server_log'] + \
            '[22:19:00] [LSS Disk Reader #1/ERROR] [LSS/]: Failed to read region r.0.0.mca\n'
        self.assertFalse(checks.check_server_started(by_logger, material['diag']).passed)
        by_stack = material['server_log'] + (
            '[22:19:00] [Server thread/ERROR] [minecraft/MinecraftServer]: Encountered an unexpected '
            'exception\njava.lang.NullPointerException: null\n'
            '\tat dev.vox.lss.networking.server.RequestProcessingService.tick(Unknown Source)\n')
        result = checks.check_server_started(by_stack, material['diag'])
        self.assertFalse(result.passed)
        self.assertIn('ERROR', result.message)

    def test_unrelated_errors_do_not_fail(self) -> None:
        material = self.material('neoforge-1.21.1')
        self.assertIn('RuntimeDistCleaner', material['server_log'])   # C2ME's own ERROR lines
        self.assertTrue(checks.check_server_started(material['server_log'], material['diag']).passed)

    def test_rig_directory_names_are_not_a_mention_of_lss(self) -> None:
        # The vanilla narrator error prints the class path, which lies under .cache/lss-realstack.
        material = self.material('fabric-26.3')
        self.assertIn('lss-realstack', material['client_log'])
        entries = checks.parse_entries(material['client_log'])
        self.assertEqual(checks.lss_entries(entries, ('ERROR',), OWN_PATHS), [])
        self.assertEqual(len(checks.lss_entries(entries, ('ERROR',))), 1)   # red without the fix
        # A jar name under the rig's own folder still counts.
        line = ('[10:00:00] [main/ERROR]: Failed to load mod from /home/user/.local/state/'
                'lss-realstack/runs/x/client/mods/lod-server-support-fabric-0.21.0+26.3.jar')
        self.assertEqual(len(checks.lss_entries(checks.parse_entries(line), ('ERROR',), OWN_PATHS)), 1)

    def test_handshake_needs_each_witness(self) -> None:
        material = self.material('fabric-26.3')

        def judge(**changes) -> checks.CheckResult:
            """The check's result with some of the material replaced."""
            changed = {**material, **changes}
            return checks.check_handshake(changed['server_log'], changed['client_log'],
                                          changed['diag'], PLAYER, changed['protocol'])

        self.assertTrue(judge().passed)
        self.assertIn('joined the game', judge(
            server_log=material['server_log'].replace('joined the game', 'tried')).message)
        self.assertIn('expected v21', judge(protocol=21).message)
        self.assertIn('did not register', judge(server_log=re.sub(
            r'.*registered for LSS LOD request processing.*\n', '', material['server_log'])).message)
        self.assertIn('Server session config', judge(client_log=re.sub(
            r'.*Server session config received.*\n', '', material['client_log'])).message)
        self.assertFalse(judge(client_log=material['client_log'].replace(
            'enabled: true', 'enabled: false')).passed)
        self.assertIn('no live v20', judge(diag=material['diag'].replace('v20=1', 'v20=0')).message)
        self.assertFalse(judge(diag='').passed)

    def test_handshake_of_another_player_does_not_count(self) -> None:
        material = self.material('fabric-26.3')
        server = material['server_log'].replace(f'handshake received from {PLAYER}',
                                                'handshake received from Somebody')
        result = checks.check_handshake(server, material['client_log'], material['diag'], PLAYER, 20)
        self.assertFalse(result.passed)

    def test_columns_need_both_counters(self) -> None:
        material = self.material('neoforge-1.21.1')
        diag = material['diag']
        self.assertTrue(checks.check_columns_sent(diag, material['client_log'], PLAYER).passed)
        zero = re.sub(r'sent=\d+', 'sent=0', diag)
        self.assertFalse(checks.check_columns_sent(zero, material['client_log'], PLAYER).passed)
        other = diag.replace(f'  {PLAYER}: ', '  Somebody: ')
        self.assertIn(f'to {PLAYER}',
                      checks.check_columns_sent(other, material['client_log'], PLAYER).message)
        self.assertIn('no lsslod diag',
                      checks.check_columns_sent('', material['client_log'], PLAYER).message)

    def test_columns_notes_name_the_consumers(self) -> None:
        material = self.material('fabric-26.3')
        notes = ' '.join(checks.check_columns_sent(material['diag'], material['client_log'],
                                                   PLAYER).notes)
        self.assertIn('VoxyCompat', notes)
        self.assertIn('XaeroSession', notes)
        self.assertIn('cached column entries', notes)

    def test_client_clean_failures(self) -> None:
        material = self.material('fabric-26.3')

        def judge(**changes) -> checks.CheckResult:
            """The check's result with some of the material replaced."""
            changed = {**material, **changes}
            return checks.check_client_clean(
                changed['client_log'], changed['crash_files'], changed['client_alive_at_end'],
                PLAYER, changed['observed_server_log'], OWN_PATHS)

        self.assertTrue(judge().passed)
        self.assertIn('crashed', judge(crash_files=['client/crash-reports/crash.txt']).message)
        self.assertIn('crashed', judge(
            client_log=material['client_log'] + '---- Minecraft Crash Report ----\n').message)
        self.assertIn('ended', judge(client_alive_at_end=False).message)
        self.assertIn('wrote no log', judge(client_log='').message)
        dropped = material['observed_server_log'] + \
            f'[22:14:30] [Server thread/INFO]: {PLAYER} lost connection: Internal Exception: ' \
            'io.netty.handler.codec.DecoderException: found 12 bytes extra\n'
        self.assertIn('lost its connection', judge(observed_server_log=dropped).message)
        mixin = material['client_log'] + (
            '[22:14:00] [Render thread/ERROR]: Mixin apply failed lss.mixins.json:SodiumPageMixin\n')
        self.assertIn('ERROR', judge(client_log=mixin).message)

    def test_the_runners_own_kick_is_not_a_lost_connection(self) -> None:
        material = self.material('fabric-26.3')
        self.assertIn('Kicked by an operator', material['server_log'])
        self.assertNotIn('lost connection', material['observed_server_log'])

    def test_lss_warnings_are_reported_not_failed(self) -> None:
        material = self.material('neoforge-1.21.1')
        result = checks.check_server_started(material['server_log'], material['diag'], OWN_PATHS)
        self.assertTrue(result.passed)
        self.assertTrue(any('Background-priority disk reads unavailable' in note
                            for note in result.notes))

    def test_scenario_checks(self) -> None:
        """The "server without the mod" scenario: one notice, in the right brand's words."""
        def log(*messages: str) -> str:
            return ''.join(f'[10:00:{second:02d}] [Render thread/INFO]: {message}\n'
                           for second, message in enumerate(messages))
        lss = ("[System] [CHAT] [LSS] This server isn't sending distant terrain. It probably "
               "doesn't have this mod installed. Nothing is wrong with your game.")
        vss = lss.replace('[LSS]', '[VSS]')
        notice = checks.check_client_notice
        self.assertTrue(notice(log('Connecting to 127.0.0.1, 25900', lss), 'no_support', 'LSS').passed)
        self.assertTrue(notice(log(vss), 'no_support', 'VSS').passed)
        # NeoForge names the logger; other chat is not a notice.
        neoforge = f'[10:00:00] [Render thread/INFO] [minecraft/ChatComponent]: {lss}\n'
        self.assertTrue(notice(neoforge, 'no_support', 'LSS').passed)
        other = '[System] [CHAT] RealStack joined the game'
        self.assertIn('no chat notice', notice(log(other), 'no_support', 'LSS').message)
        # One per connection, and the brand's two names belong together.
        self.assertIn('at most one', notice(log(lss, lss), 'no_support', 'LSS').message)
        self.assertIn('prefixed [LSS]', notice(log(lss), 'no_support', 'VSS').message)
        # The sentence says "this mod": the 0.22 betas' first wording named the brand.
        branded = lss.replace("doesn't have this mod installed", "doesn't run LOD Server Support")
        self.assertIn('does not read', notice(log(branded), 'no_support', 'LSS').message)
        self.assertIn('does not read', notice(log(lss.replace('Nothing is wrong', 'Something is wrong')),
                                              'no_support', 'LSS').message)

        server = fixture('fabric-26.3-nolss-server.log')
        self.assertTrue(checks.check_server_ready(server).passed)
        self.assertFalse(checks.check_server_ready('').passed)
        self.assertTrue(checks.check_client_joined(server, PLAYER).passed)
        self.assertFalse(checks.check_client_joined(server, 'Somebody').passed)

        # A scenario replaces the row's checks, in a run and in a replay.
        row = {'id': 'x', 'checks': ['server_started', 'handshake']}
        self.assertEqual(realstack.judged_row(row, None), row)
        judged = realstack.judged_row(row, 'server-without-lss')
        self.assertEqual(judged['checks'], realstack.SCENARIOS['server-without-lss']['checks'])
        self.assertIn('client_notice', judged['checks'])
        self.assertEqual(row['checks'], ['server_started', 'handshake'], 'the row itself is untouched')
        for scenario in realstack.SCENARIOS.values():
            self.assertIn(scenario['notice'], checks.NOTICE_TEXTS)

    def test_the_other_brand_is_asked_and_watched_under_its_own_name(self) -> None:
        # A run on the VSS build once asked `lsslod diag` and read "Unknown command" as a dead
        # service: the build's server command is its own.
        self.assertEqual(realstack.SERVER_COMMANDS, {'lss': 'lsslod', 'vss': 'vsslod'})
        self.assertEqual(set(realstack.SERVER_COMMANDS), set(realstack.BRAND_PREFIXES))
        error = '[10:00:00] [Server thread/ERROR]: [VSS] the store failed to open\n'
        self.assertEqual(len(checks.lss_entries(checks.parse_entries(error), ('ERROR',), ())), 1)
        other = '[10:00:00] [Server thread/ERROR]: Could not load the class ClassVSSomething\n'
        self.assertEqual(checks.lss_entries(checks.parse_entries(other), ('ERROR',), ()), [])

    def test_brand_selects_the_other_build_of_the_same_jar(self) -> None:
        self.assertEqual(realstack.branded_prefix('lod-server-support-fabric', 'lss'),
                         'lod-server-support-fabric')
        self.assertEqual(realstack.branded_prefix('lod-server-support-neoforge', 'vss'),
                         'voxy-server-side-neoforge')
        with self.assertRaises(stackfile.StackError):
            realstack.branded_prefix('something-else', 'vss')
        names = ['lod-server-support-fabric-0.22.0-beta.1+26.3.jar',
                 'voxy-server-side-fabric-0.22.0-beta.1+26.3.jar']
        self.assertEqual(stackfile.choose_built_jar(names, 'voxy-server-side-fabric', '26.3'), names[1])
        self.assertEqual(stackfile.choose_built_jar(names, 'lod-server-support-fabric', '26.3'), names[0])

    def test_screenshot(self) -> None:
        good = self.material('fabric-26.3')['screenshot']
        self.assertTrue(checks.check_screenshot(good).passed)
        self.assertFalse(checks.check_screenshot(None).passed)
        self.assertFalse(checks.check_screenshot({**good, 'bytes': 0}).passed)
        self.assertIn('no Minecraft window',
                      checks.check_screenshot({**good, 'windows': []}).message)


class VerdictTest(OfflineCase):
    """A run passes only when every listed check ran and passed; replay reads a saved run."""

    ROW = {'id': 'row', 'checks': ['server_started', 'handshake']}

    def result(self, name: str, passed: bool = True) -> checks.CheckResult:
        """A check result with the given outcome."""
        return checks.CheckResult(name, passed, 'message', ['line'])

    def test_all_listed_checks_passed(self) -> None:
        verdict = checks.assemble_verdict(self.ROW, [self.result('server_started'),
                                                     self.result('handshake')], {'port': 25900})
        self.assertTrue(verdict['passed'])
        self.assertEqual(verdict['port'], 25900)
        self.assertEqual([check['name'] for check in verdict['checks']],
                         ['server_started', 'handshake'])

    def test_one_red_check_fails_the_run(self) -> None:
        verdict = checks.assemble_verdict(self.ROW, [self.result('server_started'),
                                                     self.result('handshake', False)], {})
        self.assertFalse(verdict['passed'])
        self.assertEqual(verdict['failed_checks'], ['handshake'])

    def test_a_check_that_did_not_run_fails_the_run(self) -> None:
        verdict = checks.assemble_verdict(self.ROW, [self.result('server_started')], {})
        self.assertFalse(verdict['passed'])
        self.assertEqual(verdict['checks_not_run'], ['handshake'])

    def test_an_aborted_run_never_passes(self) -> None:
        verdict = checks.assemble_verdict(self.ROW, [self.result('server_started'),
                                                     self.result('handshake')],
                                          {'aborted': 'RunAborted: server "Done" line: not seen'})
        self.assertFalse(verdict['passed'])
        self.assertIn('not seen', verdict['aborted'])

    def test_replay_judges_a_saved_run(self) -> None:
        directory = self.temp()
        evidence = directory / 'evidence'
        evidence.mkdir()
        server = fixture('fabric-26.3-server.log')
        (evidence / 'server-console.log').write_text(server)
        (evidence / 'client-stdout.log').write_text(fixture('fabric-26.3-client.log'))
        (evidence / 'server-diag-final.txt').write_text(fixture('fabric-26.3-diag.txt'))
        (evidence / 'client.png').write_bytes(b'png')
        offset = len(server[:server.index(f'{PLAYER} lost connection')].encode())
        facts = {'row': 'fabric-26.3-sodium-voxy-xaero', 'player': PLAYER, 'crash_files': [],
                 'client_alive_at_end': True, 'observation_end_offset': offset,
                 'own_paths': list(OWN_PATHS),
                 'screenshot': {'path': 'evidence/client.png', 'width': 1280, 'height': 720,
                                'bytes': 3, 'windows': ['Minecraft* 26.3']}}
        (directory / 'facts.json').write_text(json.dumps(facts))
        document = stackfile.load()
        material = realstack.load_material(directory, document)
        row = stackfile.find_row(document, facts['row'])
        results = checks.run_checks(row['checks'], material)
        verdict = checks.assemble_verdict(row, results, realstack.verdict_context(row, facts))
        self.assertTrue(verdict['passed'], verdict['failed_checks'])
        self.assertEqual(verdict['pins']['client'][1], 'sodium mc26.3-0.9.2-fabric')
        (evidence / 'client.png').unlink()   # a recorded screenshot that is gone is not evidence
        material = realstack.load_material(directory, document)
        self.assertFalse(checks.check_screenshot(material['screenshot']).passed)


class LaunchTest(OfflineCase):
    """Ports, displays, the private environment and the command lines."""

    def test_port_selection(self) -> None:
        busy = {25900, 25902}
        self.assertEqual(launch.pick_port(range(25900, 25910), launch.RESERVED_PORTS, busy.__contains__),
                         25901)
        self.assertEqual(launch.pick_port([25565, 25701, 25950], launch.RESERVED_PORTS,
                                          lambda port: False), 25950)
        with self.assertRaises(launch.LaunchError):
            launch.pick_port([25565, 25566], launch.RESERVED_PORTS, lambda port: False)
        with self.assertRaises(launch.LaunchError):
            launch.pick_port([25900], launch.RESERVED_PORTS, lambda port: True)

    def test_reserved_ports(self) -> None:
        for port in (25565, 25566, 25569, 25570, 25591, 25592, 25611, 25641, 25642, 25701, 25706):
            self.assertIn(port, launch.RESERVED_PORTS)
            with self.assertRaises(launch.LaunchError):
                launch.check_port(port, launch.RESERVED_PORTS, lambda _port: False)
        self.assertFalse(set(launch.PORT_CANDIDATES) & launch.RESERVED_PORTS)
        self.assertEqual(launch.check_port(25950, launch.RESERVED_PORTS, lambda _port: False), 25950)
        with self.assertRaises(launch.LaunchError):
            launch.check_port(25950, launch.RESERVED_PORTS, lambda _port: True)

    def test_display_selection_never_returns_zero(self) -> None:
        self.assertEqual(launch.pick_display(range(0, 5), {1}), 2)
        self.assertEqual(launch.pick_display(launch.DISPLAY_CANDIDATES, {160, 161}), 162)
        self.assertNotIn(0, launch.DISPLAY_CANDIDATES)
        with self.assertRaises(launch.LaunchError):
            launch.pick_display([0], set())

    def test_client_environment_is_private(self) -> None:
        base = {'WAYLAND_DISPLAY': 'wayland-0', 'XAUTHORITY': '/x', 'DISPLAY': ':0', 'PATH': '/bin'}
        environment = launch.client_environment(base, 161)
        self.assertEqual(environment['DISPLAY'], ':161')
        self.assertNotIn('WAYLAND_DISPLAY', environment)
        self.assertNotIn('XAUTHORITY', environment)
        self.assertEqual(environment['LIBGL_ALWAYS_SOFTWARE'], '1')
        self.assertEqual(environment['PATH'], '/bin')
        self.assertNotIn('DISPLAY', launch.private_environment(base))
        with self.assertRaises(launch.LaunchError):
            launch.client_environment(base, 0)

    def test_server_properties(self) -> None:
        text = launch.server_properties(25901, '26.3')
        for line in ('online-mode=false', 'white-list=false', 'server-ip=127.0.0.1',
                     'server-port=25901', 'enable-rcon=false'):
            self.assertIn(line + '\n', text)

    def test_game_arguments(self) -> None:
        modern = {'assetIndex': {'id': '34'}, 'arguments': {'game': ['--username', '--version']}}
        legacy = {'assetIndex': {'id': '17'}, 'arguments': {'game': ['--userType', '${user_type}']}}
        game_dir, assets = Path('/run/client'), Path('/cache/assets')
        arguments = launch.game_arguments(modern, '26.3', game_dir, assets, '127.0.0.1:25900')
        self.assertEqual(arguments[arguments.index('--accessToken') + 1], '0')
        self.assertEqual(arguments[arguments.index('--assetIndex') + 1], '34')
        self.assertEqual(arguments[arguments.index('--quickPlayMultiplayer') + 1], '127.0.0.1:25900')
        self.assertEqual(arguments[arguments.index('--username') + 1], launch.PLAYER)
        self.assertNotIn('--userType', arguments)
        self.assertIn('--userType', launch.game_arguments(legacy, '1.21.1', game_dir, assets, 'x:1'))

    def test_commands_carry_the_run_tag_and_a_deadline(self) -> None:
        tag = launch.rig_tag('rs20261113abc', 'client')
        self.assertEqual(tag, '-Dlssrig.id=rs20261113abc-client')
        command = launch.fabric_client_command(Path('/jdk/bin/java'), tag, [Path('/a.jar'), Path('/b.jar')],
                                               Path('/client.jar'), ['-XX:StackShadowPages=32'], ['--x'])
        self.assertEqual(command[:2], ['/jdk/bin/java', tag])
        self.assertIn('-Dfabric.gameJarPath=/client.jar', command)
        self.assertEqual(command[command.index('-cp') + 1], '/a.jar:/b.jar')
        self.assertEqual(command[-2:], ['net.fabricmc.loader.impl.launch.knot.KnotClient', '--x'])
        server = launch.fabric_server_command(Path('/jdk/bin/java'), tag, [Path('/a.jar')],
                                              Path('/server.jar'), [])
        self.assertEqual(server[-2:], ['net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'])
        self.assertEqual(launch.bounded(command, 900)[:4], ['timeout', '-k', '15', '900'])
        neoforge = launch.neoforge_server_command(Path('/jdk/bin/java'), tag, '21.1.266', [])
        self.assertIn('@libraries/net/neoforged/neoforge/21.1.266/unix_args.txt', neoforge)

    def test_neoforge_client_command(self) -> None:
        profile = {'id': 'neoforge-21.1.266', 'mainClass': 'cpw.mods.bootstraplauncher.BootstrapLauncher',
                   'arguments': {'jvm': ['-DlibraryDirectory=${library_directory}',
                                         '-p', '${library_directory}/a.jar${classpath_separator}'
                                         '${library_directory}/b.jar'],
                                 'game': ['--launchTarget', 'forgeclient']},
                   'libraries': [{'name': 'org.ow2.asm:asm:9.10.1', 'downloads': {'artifact': {
                       'path': 'org/ow2/asm/asm/9.10.1/asm-9.10.1.jar'}}}]}
        vanilla = [('org.ow2.asm:asm:9.6', Path('/cache/asm-9.6.jar')),
                   ('com.mojang:brigadier:1.3.10', Path('/cache/brigadier.jar'))]
        classpath = launch.neoforge_client_classpath(profile, Path('/neo/libraries'), vanilla)
        self.assertEqual(classpath, [Path('/neo/libraries/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar'),
                                     Path('/cache/brigadier.jar')])
        command = launch.neoforge_client_command(Path('/jdk/bin/java'), '-Dlssrig.id=x-client', profile,
                                                 Path('/neo/libraries'), classpath, [], ['--username', 'P'])
        self.assertIn('-DlibraryDirectory=/neo/libraries', command)
        self.assertIn('/neo/libraries/a.jar:/neo/libraries/b.jar', command)
        self.assertEqual(command[-4:], ['--username', 'P', '--launchTarget', 'forgeclient'])
        self.assertLess(command.index('cpw.mods.bootstraplauncher.BootstrapLauncher'),
                        command.index('--username'))

    def test_offline_uuid_is_stable(self) -> None:
        self.assertEqual(launch.offline_uuid('RealStack'), launch.offline_uuid('RealStack'))
        self.assertNotEqual(launch.offline_uuid('RealStack'), launch.offline_uuid('Other'))


class OwnershipTest(OfflineCase):
    """A run only ever recognises its own java, timeout and Xvfb processes."""

    def fake_proc(self, processes: dict) -> Path:
        """A directory shaped like /proc with the given processes."""
        root = self.temp()
        for pid, (name, arguments) in processes.items():
            (root / str(pid)).mkdir()
            (root / str(pid) / 'comm').write_text(name + '\n')
            (root / str(pid) / 'cmdline').write_bytes(b'\0'.join(arguments) + b'\0')
        (root / 'self').mkdir()
        (root / 'uptime').write_text('1')
        return root

    def test_only_own_named_processes_match(self) -> None:
        marker = 'lssrig.id=rs20261113T000000Zabc123-server'
        root = self.fake_proc({
            100: ('java', [b'/jdk/bin/java', b'-D' + marker.encode(), b'-jar', b'server.jar']),
            101: ('timeout', [b'timeout', b'900', b'/jdk/bin/java', b'-D' + marker.encode()]),
            102: ('bash', [b'bash', b'-c', b'echo ' + marker.encode()]),          # a shell: never
            103: ('python3', [b'python3', b'realstack.py', marker.encode()]),      # the runner: never
            104: ('java', [b'/jdk/bin/java', b'-Dlssrig.id=another-run-server']),  # someone else's
            105: ('java', [b'/jdk/bin/java', b'-jar', b'paper.jar']),              # a normal server
            106: ('Xvfb', [b'Xvfb', b':160', b'-fbdir', b'/runs/mine/xvfb']),
            107: ('Xvfb', [b'Xvfb', b':0']),
        })
        processes = procs.list_processes(root)
        self.assertEqual(len(processes), 8)
        own = procs.owned(processes, [marker, '-fbdir /runs/mine/xvfb'])
        self.assertEqual(sorted(process.pid for process in own), [100, 101, 106])

    def test_empty_marker_matches_nothing(self) -> None:
        root = self.fake_proc({100: ('java', [b'java', b'-jar', b'server.jar'])})
        self.assertEqual(procs.owned(procs.list_processes(root), ['', '']), [])
        self.assertEqual(procs.owned(procs.list_processes(root), []), [])

    def test_run_markers_are_unique_per_run(self) -> None:
        options = realstack.build_parser().parse_args(
            ['run', 'fabric-26.3-sodium-voxy-xaero', '--lss', 'published:0.21.0'])
        document = stackfile.load()
        row = document['rows'][0]
        first = realstack.Run(document, row, 'published:0.21.0', options)
        second = realstack.Run(document, row, 'published:0.21.0', options)
        self.assertNotEqual(first.rig_id, second.rig_id)
        self.assertFalse(set(first.markers('server', 'client')) & set(second.markers('server', 'client')))
        self.assertTrue(all(first.rig_id in marker for marker in first.markers('server', 'client')))

    def test_lock_path_is_the_shared_harness_lock(self) -> None:
        path = procs.lock_path()
        self.assertEqual(path.name, 'port-25565.lock')
        self.assertTrue(path.parent.name.startswith('lss-harness-'))


class ScreenshotTest(OfflineCase):
    """The framebuffer dump to PNG converter."""

    def xwd(self, width: int, height: int, pixels: list[tuple[int, int, int]]) -> bytes:
        """A little-endian BGRX dump like the one Xvfb writes at depth 24."""
        name = b'Xvfb\0'
        header = [xwdpng.XWD_HEADER_BYTES + len(name), 7, 2, 24, width, height, 0, 0, 32, 0, 32, 32,
                  width * 4, 4, 0xFF0000, 0x00FF00, 0x0000FF, 8, 256, 2, width, height, 0, 0, 0]
        body = b''.join(bytes((blue, green, red, 0)) for red, green, blue in pixels)
        return struct.pack('>25I', *header) + name + b'\0' * (2 * xwdpng.XWD_COLOR_BYTES) + body

    def decode_png(self, png: bytes) -> tuple[int, int, bytes]:
        """Read a PNG back (checking every chunk's CRC) as (width, height, RGB bytes)."""
        self.assertEqual(png[:8], xwdpng.PNG_SIGNATURE)
        width, height = struct.unpack('>II', png[16:24])
        position, data = 8, b''
        while position < len(png):
            length = struct.unpack('>I', png[position:position + 4])[0]
            kind = png[position + 4:position + 8]
            payload = png[position + 8:position + 8 + length]
            self.assertEqual(struct.unpack('>I', png[position + 8 + length:position + 12 + length])[0],
                             zlib.crc32(kind + payload) & 0xFFFFFFFF)
            if kind == b'IDAT':
                data += payload
            position += 12 + length
        raw = zlib.decompress(data)
        rows = [raw[row * (width * 3 + 1) + 1:(row + 1) * (width * 3 + 1)] for row in range(height)]
        return width, height, b''.join(rows)

    def test_framebuffer_to_png(self) -> None:
        pixels = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (10, 20, 30), (40, 50, 60), (70, 80, 90)]
        folder = self.temp()
        (folder / 'Xvfb_screen0').write_bytes(self.xwd(3, 2, pixels))
        shot = xwdpng.capture(folder / 'Xvfb_screen0', folder / 'out' / 'client.png')
        self.assertEqual((shot['width'], shot['height']), (3, 2))
        self.assertEqual(shot['bytes'], (folder / 'out' / 'client.png').stat().st_size)
        width, height, rgb = self.decode_png((folder / 'out' / 'client.png').read_bytes())
        self.assertEqual((width, height), (3, 2))
        self.assertEqual(rgb, b''.join(bytes(pixel) for pixel in pixels))

    def test_bad_dumps_are_refused(self) -> None:
        with self.assertRaises(xwdpng.XwdError):
            xwdpng.xwd_to_rgb(b'short')
        good = self.xwd(2, 1, [(1, 2, 3), (4, 5, 6)])
        with self.assertRaises(xwdpng.XwdError):
            xwdpng.xwd_to_rgb(good[:-4])
        sixteen = bytearray(good)
        sixteen[44:48] = struct.pack('>I', 16)
        with self.assertRaises(xwdpng.XwdError):
            xwdpng.xwd_to_rgb(bytes(sixteen))


class JavaTest(OfflineCase):
    """Finding a JDK by its major version."""

    def test_java_major_from_release_file(self) -> None:
        home = self.temp()
        (home / 'release').write_text('IMPLEMENTOR="Eclipse Adoptium"\nJAVA_VERSION="25.0.2"\n')
        self.assertEqual(realstack.java_major(home), 25)
        (home / 'release').write_text('JAVA_VERSION="21.0.13"\n')
        self.assertEqual(realstack.java_major(home), 21)
        self.assertIsNone(realstack.java_major(home / 'missing'))


class SourceRulesTest(OfflineCase):
    """The tool's own files keep the rules they were written under."""

    def sources(self) -> list[Path]:
        """The committed files of the tool."""
        return sorted(HERE.glob('*.py')) + [HERE / 'stacks.json', HERE / 'README.md']

    def test_no_home_directory_or_user_name(self) -> None:
        home = str(Path.home())
        for path in self.sources() + sorted(FIXTURES.glob('*')):
            text = path.read_text()
            self.assertNotIn(home, text, path.name)
            self.assertIsNone(re.search(r'/home/(?!user/)[a-z0-9_-]+/', text), path.name)

    def test_lines_stay_under_110_characters(self) -> None:
        for path in sorted(HERE.glob('*.py')):
            for number, line in enumerate(path.read_text().splitlines(), 1):
                self.assertLess(len(line), 110, f'{path.name}:{number}')

    def test_no_credentials_are_read(self) -> None:
        for path in sorted(HERE.glob('*.py')):
            if path.name == Path(__file__).name:
                continue
            text = path.read_text()
            for forbidden in ('.bot.env', 'Authorization', 'accounts.json', 'MODRINTH_PAT', '/mnt/c'):
                self.assertNotIn(forbidden, text, f'{path.name}: {forbidden}')


if __name__ == '__main__':
    unittest.main()
