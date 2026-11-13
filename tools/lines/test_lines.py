"""The follower rules, each against a throwaway repository: main on one branch, a support line on
another, and the line's sync.json."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lines

BODY = ''.join('row %d\n' % number for number in range(1, 21))


def git(root, *args):
    return subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True, text=True).stdout.strip()


def write(root, files):
    for path, text in files.items():
        target = root / path
        if text is None:
            target.unlink()
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)


def commit(root, files, message):
    write(root, files)
    git(root, 'add', '-A')
    git(root, 'commit', '-q', '-m', message)
    return git(root, 'rev-parse', 'HEAD')


class Follower(unittest.TestCase):
    """main: shared.txt, adapted.txt, tools/compat/locked.py, a corpus file, docs.
    line:  adapted.txt differs near the top, line-only.txt exists only here."""

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        git(self.root, 'init', '-q', '-b', 'main')
        git(self.root, 'config', 'user.email', 'test@example.invalid')
        git(self.root, 'config', 'user.name', 'test')
        self.main1 = commit(self.root, {
            'shared.txt': 'one\n', 'adapted.txt': BODY, 'tools/compat/locked.py': 'x = 1\n',
            'fabric/src/test/resources/nbt-corpus/a.bin': 'captured\n', 'README.md': 'main readme\n',
            'docs/guide.md': 'main guide\n', 'main-only/overlay.txt': 'overlay\n',
            'config/lines/lines.json': json.dumps({'must_match': ['tools/compat/']}),
            'config/compatibility/line.json': json.dumps({'role': 'reference'})}, 'main 1')
        git(self.root, 'checkout', '-q', '-b', 'line')
        self.sync = dict(schema_version=2, reference_commit=self.main1,
                         adapted=['adapted.txt', 'line-only.txt'], absent=['main-only/*'])
        self.line1 = commit(self.root, {
            'adapted.txt': BODY.replace('row 2\n', 'row 2 (this line)\n'), 'line-only.txt': 'here\n',
            'README.md': 'line readme\n', 'main-only/overlay.txt': None, 'config/lines/lines.json': None,
            'config/compatibility/line.json': json.dumps({'role': 'follower'}),
            'config/lines/sync.json': json.dumps(self.sync)}, 'line 1')

    def tearDown(self):
        self.scratch.cleanup()

    def results(self, issues):
        return sorted((issue['result'], issue['path']) for issue in issues)

    def main_commit(self, files, message):
        git(self.root, 'checkout', '-q', 'main')
        sha = commit(self.root, files, message)
        git(self.root, 'checkout', '-q', 'line')
        return sha

    def resync(self, reference, extra=None, message='sync'):
        self.sync = dict(self.sync, reference_commit=reference)
        files = {'config/lines/sync.json': json.dumps(self.sync)}
        files.update(extra or {})
        return commit(self.root, files, message)

    # ---- always-on rules ----

    def test_a_synced_line_is_clean_and_docs_and_line_config_are_never_compared(self):
        self.assertEqual(lines.compare(self.root, self.sync, 'HEAD'), [])
        self.assertEqual(lines.role(self.root), 'follower')

    def test_a_shared_file_that_drifts_is_reported(self):
        commit(self.root, {'shared.txt': 'edited on the line\n'}, 'drift')
        self.assertEqual(self.results(lines.compare(self.root, self.sync, 'HEAD')), [('differs', 'shared.txt')])

    def test_a_file_on_one_side_only_counts_as_different(self):
        commit(self.root, {'shared.txt': None, 'extra.txt': 'new\n'}, 'one-sided')
        issues = lines.compare(self.root, self.sync, 'HEAD')
        self.assertEqual(self.results(issues), [('differs', 'extra.txt'), ('differs', 'shared.txt')])
        self.assertIn('only on this line', issues[0]['detail'])
        self.assertIn('only on main', issues[1]['detail'])

    def test_a_mode_change_alone_is_drift(self):
        (self.root / 'shared.txt').chmod(0o755)
        commit(self.root, {}, 'chmod')
        issues = lines.compare(self.root, self.sync, 'HEAD')
        self.assertEqual(self.results(issues), [('differs', 'shared.txt')])
        self.assertIn('mode', issues[0]['detail'])

    def test_an_adapted_entry_that_is_identical_again_is_stale(self):
        commit(self.root, {'adapted.txt': BODY}, 'no longer adapted')
        self.assertEqual(self.results(lines.compare(self.root, self.sync, 'HEAD')), [('stale-adapted', 'adapted.txt')])

    def test_an_absent_pattern_covers_main_only_paths_but_not_a_copy_on_the_line(self):
        commit(self.root, {'main-only/overlay.txt': 'copied by mistake\n'}, 'copied')
        self.assertEqual(self.results(lines.compare(self.root, self.sync, 'HEAD')),
                         [('absent-but-present', 'main-only/overlay.txt')])

    def test_main_can_forbid_adapting_a_path(self):
        sync = dict(self.sync, adapted=sorted(self.sync['adapted'] + ['tools/compat/locked.py']))
        commit(self.root, {'tools/compat/locked.py': 'x = 2\n', 'config/lines/sync.json': json.dumps(sync)}, 'fork the check')
        self.assertEqual(self.results(lines.compare(self.root, sync, 'HEAD')), [('must-match', 'tools/compat/locked.py')])

    def test_the_working_tree_is_what_status_reads(self):
        write(self.root, {'shared.txt': 'uncommitted\n'})
        self.assertEqual(self.results(lines.compare(self.root, self.sync, lines.worktree_tree(self.root))),
                         [('differs', 'shared.txt')])
        self.assertEqual(lines.compare(self.root, self.sync, 'HEAD'), [])

    def test_sync_json_is_validated(self):
        for broken in (dict(self.sync, schema_version=1), dict(self.sync, reference_commit='abc'),
                       dict(self.sync, adapted=['b', 'a']), dict(self.sync, surprise=1)):
            with self.assertRaises(lines.Invalid):
                lines.validate_sync(broken)

    def test_a_role_is_required(self):
        write(self.root, {'config/compatibility/line.json': json.dumps({})})
        with self.assertRaises(lines.Invalid):
            lines.role(self.root)

    # ---- rules about the port itself ----

    def test_a_mechanical_port_passes_and_is_counted(self):
        main2 = self.main_commit({'adapted.txt': BODY.replace('row 19\n', 'row 19 (main)\n'), 'shared.txt': 'two\n'}, 'main 2')
        lines.port(self.root, self.main1, main2)
        head = self.resync(main2, message='port main 2')
        self.assertEqual(lines.compare(self.root, self.sync, head), [])
        issues, residue, mechanical = lines.check_port(self.root, self.sync, self.line1, head)
        self.assertEqual((issues, residue, mechanical), ([], [], 1))
        self.assertIn('row 2 (this line)\n', (self.root / 'adapted.txt').read_text())
        self.assertIn('row 19 (main)\n', (self.root / 'adapted.txt').read_text())

    def test_an_adapted_file_main_changed_and_the_line_left_alone_needs_a_reason(self):
        main2 = self.main_commit({'adapted.txt': BODY.replace('row 19\n', 'row 19 (main)\n')}, 'main 2')
        head = self.resync(main2)
        issues, _, _ = lines.check_port(self.root, self.sync, self.line1, head)
        self.assertEqual(self.results(issues), [('not-ported', 'adapted.txt')])
        git(self.root, 'commit', '-q', '--amend', '-m', 'sync\n\nNot-ported: adapted.txt: this line has no row 19')
        self.assertEqual(lines.check_port(self.root, self.sync, self.line1, 'HEAD')[0], [])

    def test_a_hand_edited_port_passes_and_its_residue_is_shown(self):
        main2 = self.main_commit({'adapted.txt': BODY.replace('row 19\n', 'row 19 (main)\n')}, 'main 2')
        text = (self.root / 'adapted.txt').read_text().replace('row 19\n', 'row 19 (main, this line\'s way)\n')
        head = self.resync(main2, {'adapted.txt': text})
        issues, residue, mechanical = lines.check_port(self.root, self.sync, self.line1, head)
        self.assertEqual((issues, mechanical), ([], 0))
        self.assertEqual([item['path'] for item in residue], ['adapted.txt'])
        self.assertTrue(any("this line's way" in row for row in residue[0]['diff']))

    def test_a_change_main_made_that_the_line_already_has_needs_nothing(self):
        git(self.root, 'checkout', '-q', 'main')
        main2 = commit(self.root, {'adapted.txt': BODY.replace('row 2\n', 'row 2 (this line)\n')}, 'main adopts the line text')
        git(self.root, 'checkout', '-q', 'line')
        self.sync = dict(self.sync, adapted=['line-only.txt'])
        head = self.resync(main2)
        self.assertEqual(lines.compare(self.root, self.sync, head), [])
        self.assertEqual(lines.check_port(self.root, self.sync, self.line1, head)[0], [])

    def test_a_sync_never_moves_backwards(self):
        main2 = self.main_commit({'shared.txt': 'two\n'}, 'main 2')
        lines.port(self.root, self.main1, main2)
        forward = self.resync(main2)
        back = self.resync(self.main1, {'shared.txt': 'one\n'})
        issues, _, _ = lines.check_port(self.root, self.sync, forward, back)
        self.assertEqual(self.results(issues), [('sync-moved-backwards', 'config/lines/sync.json')])

    def test_a_newly_adapted_shared_file_needs_a_reason(self):
        self.sync = dict(self.sync, adapted=sorted(self.sync['adapted'] + ['shared.txt']))
        head = commit(self.root, {'shared.txt': 'forked\n', 'config/lines/sync.json': json.dumps(self.sync)}, 'fork')
        self.assertEqual(lines.compare(self.root, self.sync, head), [])
        self.assertEqual(self.results(lines.check_port(self.root, self.sync, self.line1, head)[0]),
                         [('adapted-without-reason', 'shared.txt')])
        git(self.root, 'commit', '-q', '--amend', '-m', 'fork\n\nAdapted: shared.txt: this version has no such API')
        self.assertEqual(lines.check_port(self.root, self.sync, self.line1, 'HEAD')[0], [])

    def test_a_corpus_that_changes_while_mains_did_not_needs_a_reason(self):
        path = 'fabric/src/test/resources/nbt-corpus/a.bin'
        self.sync = dict(self.sync, adapted=sorted(self.sync['adapted'] + [path]))
        head = commit(self.root, {path: 'regenerated\n', 'config/lines/sync.json': json.dumps(self.sync)},
                      'regen\n\nAdapted: ' + path + ': the line captures its own bytes')
        self.assertEqual(self.results(lines.check_port(self.root, self.sync, self.line1, head)[0]),
                         [('corpus-changed', path)])
        git(self.root, 'commit', '-q', '--amend', '-m', 'regen\n\nAdapted: ' + path + ': own bytes\nCorpus-regenerated: '
            + path + ': the section encoding changed on this line')
        self.assertEqual(lines.check_port(self.root, self.sync, self.line1, 'HEAD')[0], [])

    # ---- the port applier ----

    def test_port_classifies_every_outcome_and_a_dry_run_writes_nothing(self):
        commit(self.root, {'clash.txt': 'a\nLINE\nc\n'}, 'line has its own clash.txt')
        git(self.root, 'checkout', '-q', 'main')
        commit(self.root, {'clash.txt': 'a\nb\nc\n', 'gone.txt': 'bye\n'}, 'main base for the applier')
        base = git(self.root, 'rev-parse', 'HEAD')
        new = commit(self.root, {'shared.txt': 'two\n', 'adapted.txt': BODY.replace('row 19\n', 'row 19 (main)\n'),
                                 'clash.txt': 'a\nMAIN\nc\n', 'gone.txt': None, 'new.txt': 'n\n',
                                 'main-only/overlay.txt': 'overlay 2\n',
                                 'config/lines/lines.json': json.dumps({'must_match': []})}, 'main work')
        git(self.root, 'checkout', '-q', 'line')
        dry = lines.port(self.root, base, new, dry=True)
        self.assertEqual(dry['identical_base'], ['shared.txt'])
        self.assertEqual(dry['merged_clean'], ['adapted.txt'])
        self.assertEqual(dry['conflict'], ['clash.txt'])
        self.assertEqual(dry['added'], ['new.txt'])
        self.assertEqual(dry['deleted'], ['gone.txt'])
        # The fixture's line lists main-only/* as absent: a change there is not this line's to take.
        self.assertEqual(dry['absent'], ['main-only/overlay.txt'])
        self.assertEqual(dry['target_missing'], [])
        self.assertEqual(dry['never_ported'], ['config/lines/lines.json'])
        self.assertEqual((self.root / 'shared.txt').read_text(), 'one\n')
        lines.port(self.root, base, new)
        self.assertEqual((self.root / 'shared.txt').read_text(), 'two\n')
        self.assertIn('<<<<<<< this line', (self.root / 'clash.txt').read_text())
        self.assertFalse((self.root / 'config/lines/lines.json').exists())

    def test_port_never_copies_in_what_the_line_lists_as_absent(self):
        # Main gained a main-only tool. The line lists it as absent in its working tree just
        # before the port (the entry is not committed yet): the port must leave it out, whether
        # main adds, changes or deletes such a file.
        git(self.root, 'checkout', '-q', 'main')
        base = commit(self.root, {'tools/only-main/old.py': 'old\n'}, 'main has a main-only tool')
        new = commit(self.root, {'tools/only-main/new.py': 'new\n', 'tools/only-main/old.py': 'changed\n',
                                 'shared.txt': 'two\n'}, 'main work')
        git(self.root, 'checkout', '-q', 'line')
        sync_path = self.root / 'config/lines/sync.json'
        sync = json.loads(sync_path.read_text())
        sync['absent'] = ['tools/only-main/*']
        sync_path.write_text(json.dumps(sync))
        ported = lines.port(self.root, base, new)
        self.assertEqual(ported['absent'], ['tools/only-main/new.py', 'tools/only-main/old.py'])
        self.assertEqual(ported['added'], [])
        self.assertEqual(ported['target_missing'], [])
        self.assertFalse((self.root / 'tools/only-main').exists())
        self.assertEqual((self.root / 'shared.txt').read_text(), 'two\n', 'everything else still ports')

    def test_init_lists_exactly_what_differs(self):
        (self.root / 'config/lines/sync.json').unlink()
        self.assertEqual(lines.main(['--root', str(self.root), 'init', '--reference', self.main1]), 0)
        written = json.loads((self.root / 'config/lines/sync.json').read_text())
        self.assertEqual(written['adapted'], ['adapted.txt', 'line-only.txt', 'main-only/overlay.txt'])
        self.assertEqual(written['reference_commit'], self.main1)


if __name__ == '__main__':
    unittest.main()
