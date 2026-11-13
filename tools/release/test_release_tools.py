"""Offline tests for the release tools: line order, tag names, version types, notes matching and
the three-way port applier. They build throwaway git repositories and touch no network."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import port_apply
import release_common as rc


def run(root, *args):
    subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True)


def repository(root):
    run(root, 'init', '-q', '-b', 'main')
    run(root, 'config', 'user.email', 'test@example.invalid')
    run(root, 'config', 'user.name', 'test')


def commit(root, files, message):
    for path, content in files.items():
        target = root / path
        if content is None:
            target.unlink()
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    run(root, 'add', '-A')
    run(root, 'commit', '-q', '-m', message)
    return subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()


class LineFacts(unittest.TestCase):
    def test_lines_sort_oldest_first(self):
        self.assertEqual(sorted(['26.1', '1.21.10', '1.21.1', '26.3', '1.21.11'], key=rc.line_key),
                         ['1.21.1', '1.21.10', '1.21.11', '26.1', '26.3'])

    def test_tag_names(self):
        self.assertEqual(rc.tag_for('0.22.0', '26.3', '26.3'), 'v0.22.0')
        self.assertEqual(rc.tag_for('0.22.0', '1.21.1', '26.3'), 'v0.22.0+mc1.21.1')
        self.assertEqual(rc.tag_for('0.22.0-beta.1', '1.21.1', '26.3'), 'v0.22.0-beta.1+mc1.21.1')

    def test_version_types(self):
        self.assertFalse(rc.is_prerelease('0.22.0'))
        self.assertTrue(rc.is_prerelease('0.22.0-beta.1'))
        self.assertEqual(rc.modrinth_version_type('0.22.0'), 'release')
        self.assertEqual(rc.modrinth_version_type('0.22.0-beta.2'), 'beta')
        self.assertEqual(rc.modrinth_version_type('0.22.0-rc.1'), 'beta')
        self.assertEqual(rc.modrinth_version_type('0.22.0-alpha.1'), 'alpha')

    def test_platforms_follow_ship_flags(self):
        self.assertEqual(rc.platforms({'LINE_SHIP_NEOFORGE': 'true', 'LINE_SHIP_SPONGE': 'true'}),
                         ['fabric', 'paper', 'neoforge', 'sponge'])
        self.assertEqual(rc.platforms({'LINE_SHIP_NEOFORGE': 'false'}), ['fabric', 'paper'])

    def test_env_parsing_ignores_comments_and_carriage_returns(self):
        self.assertEqual(rc.parse_env('# c\r\nA=1\r\n\r\nB = two words \r\n'), {'A': '1', 'B': 'two words'})

    def test_published_notes_strip_only_the_changelog_link(self):
        notes = '### Bug Fixes\n- **Thing** — fixed'
        tail = '\n\n**Full Changelog**: https://github.com/' + rc.REPO_SLUG + '/compare/v0.21.0...v0.22.0'
        self.assertEqual(rc.published_notes(notes + tail, 'v0.22.0'), notes)
        first = '\n\n**Full Changelog**: https://github.com/' + rc.REPO_SLUG + '/commits/v0.22.0+mc1.21.1'
        self.assertEqual(rc.published_notes(notes + first, 'v0.22.0+mc1.21.1'), notes)
        other = notes + tail.replace('v0.22.0', 'v0.23.0')
        self.assertEqual(rc.published_notes(other, 'v0.22.0'), other)

    def test_release_lines_use_local_tags_and_put_the_reference_last(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            repository(root)
            commit(root, {'config/lines/lines.json': json.dumps({
                'schema_version': 2, 'reference': '26.3',
                'lines': {'26.3': {}, '26.2': {}, '1.21.1': {}, '1.21.11': {}}})}, 'lines')
            for tag in ('v0.22.0', 'v0.22.0+mc1.21.1', 'v0.22.0+mc26.2', 'v0.21.0+mc1.21.11'):
                run(root, 'tag', tag)
            self.assertEqual(rc.release_lines('0.22.0', root),
                             [('1.21.1', 'v0.22.0+mc1.21.1'), ('26.2', 'v0.22.0+mc26.2'), ('26.3', 'v0.22.0')])
            self.assertEqual(rc.release_lines('0.22.0', root, {'26.3', '1.21.1'}),
                             [('1.21.1', 'v0.22.0+mc1.21.1'), ('26.3', 'v0.22.0')])


class PortApply(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.root = Path(self.scratch.name)
        repository(self.root)
        base_text = ''.join('line %d\n' % i for i in range(1, 21))
        self.base = commit(self.root, {'same.txt': 'one\n', 'adapted.txt': base_text, 'clash.txt': 'a\nb\nc\n',
                                       'gone.txt': 'bye\n', 'main-only.txt': 'm\n'}, 'base')
        # The support line: an adaptation near the top of one file, a different middle in another.
        run(self.root, 'checkout', '-q', '-b', 'support')
        commit(self.root, {'adapted.txt': base_text.replace('line 2\n', 'line 2 (line flavor)\n'),
                           'clash.txt': 'a\nLINE\nc\n', 'main-only.txt': None}, 'line adaptations')
        run(self.root, 'checkout', '-q', 'main')
        self.head = commit(self.root, {'same.txt': 'two\n',
                                       'adapted.txt': base_text.replace('line 19\n', 'line 19 changed on main\n'),
                                       'clash.txt': 'a\nMAIN\nc\n', 'gone.txt': None, 'new.txt': 'n\n',
                                       'main-only.txt': 'm2\n'}, 'main work')
        run(self.root, 'checkout', '-q', 'support')

    def tearDown(self):
        self.scratch.cleanup()

    def test_dry_run_classifies_and_writes_nothing(self):
        result = port_apply.apply_range(self.root, self.base, self.head, dry=True)
        self.assertEqual(result['identical_base'], ['same.txt'])
        self.assertEqual(result['merged_clean'], ['adapted.txt'])
        self.assertEqual(result['conflict'], ['clash.txt'])
        self.assertEqual(result['added'], ['new.txt'])
        self.assertEqual(result['deleted'], ['gone.txt'])
        self.assertEqual(result['target_missing'], ['main-only.txt'])
        self.assertEqual((self.root / 'same.txt').read_text(), 'one\n')
        self.assertFalse((self.root / 'new.txt').exists())

    def test_apply_keeps_the_line_adaptation_and_takes_the_main_change(self):
        port_apply.apply_range(self.root, self.base, self.head)
        self.assertEqual((self.root / 'same.txt').read_text(), 'two\n')
        merged = (self.root / 'adapted.txt').read_text()
        self.assertIn('line 2 (line flavor)\n', merged)
        self.assertIn('line 19 changed on main\n', merged)
        self.assertIn('<<<<<<< this line', (self.root / 'clash.txt').read_text())
        self.assertEqual((self.root / 'new.txt').read_text(), 'n\n')
        self.assertFalse((self.root / 'gone.txt').exists())
        self.assertFalse((self.root / 'main-only.txt').exists())


if __name__ == '__main__':
    unittest.main()
