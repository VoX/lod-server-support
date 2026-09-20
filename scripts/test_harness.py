#!/usr/bin/env python3
"""Isolated actual-script regressions; never launches Gradle, Minecraft or a socket.

Copies the checked-in orchestrators into TemporaryDirectory. Only external
producers/checkers/observers are replaced. PATH clock/port fakes keep deadlines
fast and ensure no test can inspect or affect the normal test server.
Run: python3 scripts/test_harness.py
"""
import json
import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FAKE_GRADLE = r'''#!/usr/bin/env python3
import json, os, signal, subprocess, sys, time
from pathlib import Path
root = Path(__file__).resolve().parent
with (root / 'launches').open('a') as f: f.write(' '.join(sys.argv[1:]) + '\n')
assert '--no-daemon' in sys.argv, sys.argv
assert 'LSS_HARNESS_LOCK_FD' not in os.environ, 'game inherited ownership claim'
assert not any(p.resolve().name == 'port-25565.lock' for p in Path('/proc/self/fd').iterdir()), 'game inherited lock fd'
mode = os.environ.get('FAKE_MODE', 'success')
if not any('runBenchmark' in a or 'runSoak' in a for a in sys.argv):
    if mode == 'build-fail': sys.exit(7)
    if mode == 'daemon-tail':
        subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(.2)'], start_new_session=True)
    if mode == 'build-hold':
        (root / 'held').touch()
        while True: time.sleep(.02)
    sys.exit(0)
soak = any('runSoak' in a for a in sys.argv)
kind = 'server' if any('Server' in a for a in sys.argv) else 'client'
family = 'soak' if soak else 'benchmark'
run = root / f'fabric/build/run/{family}-{kind}'
run.mkdir(parents=True, exist_ok=True)
if kind == 'server':
    count_file = root / 'server-count'
    count = int(count_file.read_text()) + 1 if count_file.exists() else 1
    count_file.write_text(str(count))
    (root / 'client-started').unlink(missing_ok=True)
    (root / 'server-finished').unlink(missing_ok=True)
    if mode == 'pre-ready-crash': sys.exit(8)
    if mode == 'orphan':
        child = subprocess.Popen([sys.executable, '-c', "import os,time,signal; from pathlib import Path; signal.signal(signal.SIGTERM, lambda *_: (time.sleep(.3), exit(0))); Path('orphan-pid').write_text(str(os.getpid())); time.sleep(60)"], start_new_session=True)
        while not (root / 'orphan-pid').exists(): time.sleep(.005)
        sys.exit(0)
    (run / 'logs').mkdir(exist_ok=True)
    (run / 'logs/latest.log').write_text('Done ready\n')
    (run / 'world/lss-lod').mkdir(parents=True, exist_ok=True)
    (run / 'world/generated').write_text('fresh world')
    (run / 'world/lss-lod/store.db').write_text('fixture')
    (root / 'game-pid').write_text(str(os.getpid()))
    (root / 'ready').touch()
    if any('store-migration-join' in a for a in sys.argv):
        print('Store downgraded to the released v0.9.x shape\nStore migration walk HELD\nfixture joined the game\nStore migration walk RESUMING\nbackground migration complete', flush=True)
    if mode == 'timeout':
        while True: time.sleep(.02)
    while not (root / 'client-started').exists(): time.sleep(.005)
    time.sleep(.03)
    duration = int(next((a.split('=')[-1] for a in sys.argv if a.startswith('-Pbenchmark.duration=')), '1'))
    metrics = dict(timestamp='fixture-current', duration_seconds=duration,
                   throughput=dict(total_sections_sent=42, total_bytes_sent=420))
    if mode == 'invalid-metrics': metrics['throughput']['total_sections_sent'] = 'forty-two'
    if mode == 'measure-fail' and count == 2: mode = 'server-crash'
    if mode != 'missing-server':
        target = run / f'{family}-results'
        target.mkdir(exist_ok=True)
        (target / ('server.jsonl' if soak else 'server.json')).write_text('{bad' if mode == 'malformed' else json.dumps(metrics))
    (root / 'server-finished').touch()
    if mode == 'server-crash': sys.exit(9)
else:
    (root / 'client-started').touch()
    if mode == 'client-timeout':
        while True: time.sleep(.02)
    while not (root / 'server-finished').exists(): time.sleep(.005)
    if mode != 'missing-client':
        target = run / f'{family}-results'
        target.mkdir(exist_ok=True)
        (target / ('client.jsonl' if soak else 'client.json')).write_text(json.dumps(dict(timestamp='fixture-current', columns_received=42, bytes_received=420)))
    (run / '.lss/cache').mkdir(parents=True, exist_ok=True)
    (run / '.lss/cache/fixture').touch()
    if mode == 'client-crash': sys.exit(10)
'''


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='lss-harness-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.clone('one')
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.executable(self.bin / 'ss', '#!/bin/sh\n[ "${FAKE_PORT_BUSY:-0}" = 0 ] || echo "LISTEN 0 10 127.0.0.1:25565"\nexit 0\n')
        self.executable(self.bin / 'sleep', '#!/usr/bin/env python3\nimport time; time.sleep(.01)\n')
        self.executable(self.bin / 'git', '#!/bin/sh\necho fixture-commit\n')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], FAKE_MODE='success')
        for key in list(self.env):
            if key.startswith(('SOAK_', 'BENCHMARK_', 'LSS_HARNESS_', 'PROFILE_', 'PROC_SAMPLER_')):
                del self.env[key]

    @staticmethod
    def executable(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(0o755)

    def clone(self, name):
        root = self.base / name
        shutil.copytree(REPO / 'scripts', root / 'scripts', ignore=shutil.ignore_patterns('__pycache__'))
        # The one path seam redirects the stable /tmp ownership namespace. Both
        # fixture worktrees share it; tests never acquire the production lock.
        lock = root / 'scripts/lib/harness-lock.sh'
        lock.write_text(lock.read_text().replace('local root="/tmp/lss-harness-$UID"',
                                                f'local root="{self.base}/locks"'))
        shutil.copy2(REPO / 'gradle.properties', root / 'gradle.properties')
        source = Path('common/src/main/java/dev/vox/lss/common/LSSConstants.java')
        (root / source.parent).mkdir(parents=True)
        shutil.copy2(REPO / source, root / source)
        self.executable(root / 'gradlew', FAKE_GRADLE)
        self.executable(root / 'scripts/lib/proc_sampler.sh', '#!/usr/bin/env python3\nimport os,sys,time\nfrom pathlib import Path\n(Path(__file__).resolve().parents[2] / "observer-pid").write_text(str(os.getpid()))\nPath(sys.argv[1]).write_text("{}\\n")\ntime.sleep(60)\n')
        self.executable(root / 'scripts/check_soak.py', '#!/usr/bin/env python3\nimport os,sys\nsys.exit(0 if "--validate" in sys.argv else int(os.environ.get("FAKE_CHECKER_EXIT", "0")))\n')
        self.executable(root / 'scripts/store_gate_check.py', '#!/usr/bin/env python3\nprint("fixture gate")\n')
        self.executable(root / 'scripts/soak_report.py', '#!/usr/bin/env python3\nprint("fixture digest")\n')
        return root

    def invoke(self, script='benchmark.sh', args=('fresh', '1'), root=None, **env):
        root = root or self.root
        return subprocess.run(['bash', str(root / 'scripts' / script), *args], cwd=root,
                              env=dict(self.env, **env), text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=20)

    def start(self, script='benchmark.sh', args=('fresh', '1'), **env):
        log = (self.base / f'process-{time.monotonic_ns()}.log').open('w+')
        self.addCleanup(log.close)
        process = subprocess.Popen(['bash', str(self.root / 'scripts' / script), *args], cwd=self.root,
                                   env=dict(self.env, **env), stdout=log, stderr=subprocess.STDOUT)
        def cleanup():
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        self.addCleanup(cleanup)
        return process, log

    def wait_file(self, path):
        deadline = time.monotonic() + 8
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(path.exists(), str(path))

    def seed_sentinels(self, root=None):
        root = root or self.root
        paths = [root / p for p in (
            'fabric/build/run/soak-server/world/region/sentinel',
            'fabric/build/run/soak-client/.lss/cache/sentinel',
            'fabric/build/run/soak-server/logs/latest.log',
            'fabric/build/run/benchmark-server/world/region/sentinel',
            'fabric/build/run/benchmark-client/.lss/cache/sentinel',
            'fabric/build/run/benchmark-server/config/lss-server-config.json',
            'benchmark-results/server.json', 'soak-results/store-migration-carry.sentinel/world')]
        for p in paths:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('untouched')
        return {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in paths}

    def unchanged(self, snapshot):
        self.assertEqual(snapshot, {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in snapshot})

    def manifest(self):
        paths = list((self.root / 'benchmark-results/runs').glob('*/manifest.json'))
        return json.loads(max(paths, key=lambda p: p.stat().st_mtime_ns).read_text())

    def assert_incomplete(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn('Benchmark Complete', result.stdout)
        self.assertNotIn('old-sentinel', result.stdout)
        self.assertFalse((self.root / 'benchmark-results/current.json').exists())
        self.assertFalse((self.root / 'benchmark-results/server.json').exists())
        self.assertEqual('incomplete', self.manifest()['status'])

    def test_occupied_port_refuses_all_mutating_entrypoints_before_staging(self):
        snapshot = self.seed_sentinels()
        commands = [('soak.sh', ['fresh-backfill']), ('benchmark.sh', ['fresh', '1']),
                    ('store_gate.sh', ['cold', '1', '1']), ('compress_gate.sh', ['cold', '1', '1']),
                    ('store_offline_edit.sh', []), ('store_migration_gate.sh', []),
                    ('summary_evicted.sh', []), ('stamp_heal.sh', []), ('store_save_storm.sh', []),
                    ('benchmark_compare.sh', ['baseworld', '1']), ('profile_disk_read.sh', ['setup']),
                    ('backfill_profile.sh', ['setup'])]
        for script, args in commands:
            with self.subTest(script=script):
                result = self.invoke(script, args, FAKE_PORT_BUSY='1')
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Port 25565', result.stdout)
                self.unchanged(snapshot)
                self.assertFalse((self.root / 'launches').exists())

    def test_concurrent_worktrees_and_soak_benchmark_share_ownership(self):
        other = self.clone('two')
        snapshot = self.seed_sentinels(other)
        owner, _ = self.start(FAKE_MODE='build-hold')
        self.wait_file(self.root / 'held')
        for script, args in [('benchmark.sh', ('fresh', '1')), ('soak.sh', ('fresh-backfill',))]:
            result = self.invoke(script, args, root=other)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Another soak/benchmark', result.stdout)
            self.unchanged(snapshot)
            self.assertFalse((other / 'launches').exists())
        owner.terminate()
        self.assertNotEqual(0, owner.wait(timeout=10))
        result = self.invoke(root=other, FAKE_MODE='build-fail')
        self.assertIn('Building mod', result.stdout)  # lock is reusable after owned cleanup

    def test_forged_environment_cannot_bypass_lock(self):
        snapshot = self.seed_sentinels()
        result = self.invoke(LSS_HARNESS_LOCK_FD='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Invalid inherited', result.stdout)
        self.unchanged(snapshot)

    def test_success_records_current_identity_and_optional_jfr_absence(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('Benchmark Complete', result.stdout)
        manifest = self.manifest()
        self.assertEqual('complete', manifest['status'])
        self.assertEqual('fixture-commit', manifest['commit'])
        self.assertEqual('absent (optional)', manifest['cycles']['measure']['server_jfr'])
        self.assertEqual(manifest, json.loads((self.root / 'benchmark-results/current.json').read_text()))
        self.assertTrue((self.root / 'benchmark-worlds/base/world/generated').exists())

    def test_success_then_failure_cannot_publish_old_metrics_or_base(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout)
        first = self.manifest()['run_id']
        base = self.root / 'benchmark-worlds/base/world/generated'
        base.write_text('old-sentinel')
        result = self.invoke(FAKE_MODE='server-crash')
        self.assert_incomplete(result)
        self.assertEqual('old-sentinel', base.read_text())
        archived = self.root / 'benchmark-results/runs' / first / 'manifest.json'
        self.assertEqual('complete', json.loads(archived.read_text())['status'])

    def test_missing_malformed_and_failed_exports_are_incomplete(self):
        for mode in ('missing-server', 'missing-client', 'malformed', 'invalid-metrics', 'server-crash', 'client-crash', 'pre-ready-crash', 'build-fail'):
            with self.subTest(mode=mode):
                self.assert_incomplete(self.invoke(FAKE_MODE=mode))

    def test_server_and_client_timeouts_fail_and_release_ownership(self):
        for mode in ('timeout', 'client-timeout'):
            with self.subTest(mode=mode):
                self.assert_incomplete(self.invoke(FAKE_MODE=mode))
                manifest = self.manifest()
                statuses = manifest['cycles']['measure']
                self.assertIn(124, (statuses['server_exit'], statuses['client_exit']))
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout)

    def test_warm_populate_must_succeed_before_measure(self):
        base = self.root / 'benchmark-worlds/base/world'
        base.mkdir(parents=True)
        result = self.invoke(args=('warm-join', '1'), FAKE_MODE='missing-client')
        self.assert_incomplete(result)
        self.assertEqual('1', (self.root / 'server-count').read_text())
        self.assertEqual({'populate'}, set(self.manifest()['cycles']))

    def test_failed_warm_measure_does_not_publish_populate_aliases(self):
        (self.root / 'benchmark-worlds/base/world').mkdir(parents=True)
        result = self.invoke(args=('warm-join', '1'), FAKE_MODE='measure-fail')
        self.assert_incomplete(result)
        self.assertEqual('complete', self.manifest()['cycles']['populate']['status'])
        self.assertFalse((self.root / 'benchmark-results/server-populate.json').exists())

    def test_warm_success_keeps_both_cycles_and_staged_config(self):
        (self.root / 'benchmark-worlds/base/world').mkdir(parents=True)
        config = self.root / 'fabric/build/run/benchmark-server/config/lss-server-config.json'
        config.parent.mkdir(parents=True)
        config.write_text('{"lodDistanceChunks":123,"lodStore":"full"}')
        result = self.invoke(args=('warm-join', '1'), BENCHMARK_CONFIG_STAGED='1')
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(123, json.loads(config.read_text())['lodDistanceChunks'])
        self.assertEqual({'populate', 'measure'}, set(self.manifest()['cycles']))
        self.assertTrue((self.root / 'benchmark-results/server-populate.json').exists())

    def test_launcher_orphan_is_reaped_before_lock_reuse(self):
        process, log = self.start(FAKE_MODE='orphan')
        self.wait_file(self.root / 'orphan-pid')
        pid = int((self.root / 'orphan-pid').read_text())
        self.assertTrue(Path(f'/proc/{pid}').exists())
        denied = self.invoke(FAKE_MODE='build-fail')
        self.assertIn('Another soak/benchmark', denied.stdout)
        self.assertNotEqual(0, process.wait(timeout=10))
        self.assertFalse(Path(f'/proc/{pid}').exists(), 'owned detached descendant survived')
        retry = self.invoke(FAKE_MODE='build-fail')
        self.assertIn('Building mod', retry.stdout)
        log.seek(0)
        self.assertNotIn('Benchmark Complete', log.read())

    def test_sigterm_reaps_actual_game_and_releases_lock(self):
        process, _ = self.start(FAKE_MODE='timeout')
        self.wait_file(self.root / 'ready')
        process.terminate()
        self.assertEqual(143, process.wait(timeout=10))
        result = self.invoke(FAKE_MODE='build-fail')
        self.assertIn('Building mod', result.stdout)

    def test_sigkill_controller_still_reaps_game_before_releasing_lock(self):
        process, _ = self.start(FAKE_MODE='timeout')
        self.wait_file(self.root / 'ready')
        game = int((self.root / 'game-pid').read_text())
        self.wait_file(self.root / 'observer-pid')
        observer = int((self.root / 'observer-pid').read_text())
        process.kill()
        process.wait(timeout=10)
        deadline = time.monotonic() + 10
        while Path(f'/proc/{game}').exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(Path(f'/proc/{game}').exists(), 'kernel parent-death cleanup failed')
        while Path(f'/proc/{observer}').exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(Path(f'/proc/{observer}').exists(), 'observer survived its controller')
        # Supervisor may be finishing its final wait/reap while the game vanishes.
        while True:
            result = self.invoke(FAKE_MODE='build-fail')
            if 'Another soak/benchmark' not in result.stdout or time.monotonic() >= deadline:
                break
            time.sleep(.02)
        self.assertIn('Building mod', result.stdout)

    def test_auto_prime_recursion_uses_same_lock_and_only_saves_green_base(self):
        result = self.invoke('soak.sh', ('warm-rejoin',))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('running fresh-backfill first', result.stdout)
        self.assertNotIn('Another soak/benchmark', result.stdout)
        self.assertTrue((self.root / 'soak-worlds/base/world/generated').exists())
        base = self.root / 'soak-worlds/base/world/generated'
        base.write_text('old-sentinel')
        result = self.invoke('soak.sh', ('fresh-backfill',), FAKE_CHECKER_EXIT='4')
        self.assertEqual(4, result.returncode, result.stdout)
        self.assertEqual('old-sentinel', base.read_text())

    def test_all_recursion_propagates_child_failure_without_self_deadlock(self):
        result = self.invoke('soak.sh', ('all',), FAKE_CHECKER_EXIT='4')
        self.assertEqual(4, result.returncode, result.stdout)
        self.assertIn('FAIL: fresh-backfill', result.stdout)
        self.assertNotIn('Another soak/benchmark', result.stdout)

    def test_multi_phase_wrapper_inherits_lock_and_cleans_owned_carry(self):
        # Real wrapper and real child script; stop at the first checker verdict so
        # its cross-phase content assertions are not counterfeited by fixtures.
        result = self.invoke('store_migration_gate.sh', (), FAKE_CHECKER_EXIT='4')
        self.assertEqual(4, result.returncode, result.stdout)
        self.assertNotIn('Another soak/benchmark', result.stdout)
        self.assertEqual([], list((self.root / 'soak-results').glob('store-migration-carry.*')))

    def test_single_use_daemon_tail_exits_naturally_without_lock_leak(self):
        result = self.invoke(FAKE_MODE='daemon-tail')
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('Benchmark Complete', result.stdout)
        self.assertNotIn('owned children remained', result.stdout)
        result = self.invoke(FAKE_MODE='build-fail')
        self.assertIn('Building mod', result.stdout)

    def test_store_gate_collects_verified_identity_for_both_arms(self):
        (self.root / 'benchmark-worlds/base/world').mkdir(parents=True)
        result = self.invoke('store_gate.sh', ('cold', '1', '1'))
        self.assertEqual(0, result.returncode, result.stdout)
        manifests = list((self.root / 'store-gate-results').glob('*/*/benchmark-manifest.json'))
        self.assertEqual(2, len(manifests), result.stdout)
        identities = [json.loads(p.read_text()) for p in manifests]
        self.assertTrue(all(m['status'] == 'complete' for m in identities))
        self.assertEqual(2, len({m['run_id'] for m in identities}))
        config = self.root / 'fabric/build/run/benchmark-server/config/lss-server-config.json'
        self.assertEqual('full', json.loads(config.read_text())['lodStore'])

    def test_multi_phase_wrapper_completes_with_inherited_ownership(self):
        # A normal prepared rig already has a version-matched base. Auto-prime
        # recursion is covered separately; keep this case about the two phases.
        base = self.root / 'soak-worlds/base'
        (base / 'world').mkdir(parents=True)
        version = next(line.split('=', 1)[1] for line in (self.root / 'gradle.properties').read_text().splitlines()
                       if line.startswith('minecraft_version='))
        (base / 'mc-version').write_text(version)
        result = self.invoke('store_migration_gate.sh', ())
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn('PASS: downgrade ran', result.stdout)
        self.assertEqual('2', (self.root / 'server-count').read_text())
        self.assertEqual([], list((self.root / 'soak-results').glob('store-migration-carry.*')))

    def test_reused_wrapper_destination_archives_old_verified_attempt(self):
        (self.root / 'benchmark-worlds/base/world').mkdir(parents=True)
        result = self.invoke('store_gate.sh', ('cold', '1', '1'), RUN_STAMP='repeat')
        self.assertEqual(0, result.returncode, result.stdout)
        result = self.invoke('store_gate.sh', ('cold', '1', '1'), RUN_STAMP='repeat', FAKE_MODE='build-fail')
        self.assertNotEqual(0, result.returncode, result.stdout)
        destination = self.root / 'store-gate-results/repeat'
        self.assertFalse((destination / 'off-rep1/benchmark-manifest.json').exists())
        self.assertFalse((destination / 'off-rep1/server.json').exists())
        prior = list((destination / '.previous').glob('off-rep1-*/benchmark-manifest.json'))
        self.assertEqual(1, len(prior))
        self.assertEqual('complete', json.loads(prior[0].read_text())['status'])

    def test_historical_output_is_explicitly_unverified(self):
        historical = self.clone('historical')
        (historical / 'scripts/lib/benchmark-results.py').unlink()
        results = historical / 'benchmark-results'
        results.mkdir()
        (results / 'server.json').write_text('{"old-sentinel":true}')
        out = self.base / 'legacy-evidence'
        result = subprocess.run(['python3', str(self.root / 'scripts/lib/benchmark-results.py'),
                                 'record', str(historical), str(out), 'legacy'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((out / 'benchmark-manifest.json').read_text())
        self.assertFalse(manifest['verified'])
        self.assertEqual('legacy-unverified', manifest['status'])
        self.assertFalse((results / 'current.json').exists())
        # Ordinary acceptance callers must fail closed for this same legacy output.
        result = subprocess.run(['python3', str(self.root / 'scripts/lib/benchmark-results.py'),
                                 'record', str(historical), str(out)], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main(verbosity=2)
