"""Offline tests for the release tools: line order, tag names, version types and notes matching.
They build throwaway git repositories and touch no network."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
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

    def test_one_rule_for_version_strings(self):
        # The same forms release.yml accepts; anything else would publish as a prerelease there
        # and be taken for the previous stable tag later.
        for good in ('0.22.0', '0.22.0-beta.1', '0.22.0-rc.2', '1.0.0-alpha.10'):
            self.assertEqual(rc.check_version(good), good)
        for bad in ('0.22', '0.22.0-pre.1', '0.22.0-beta', '0.22.0-beta1', 'v0.22.0', '0.22.0+mc26.3', ''):
            with self.assertRaises(SystemExit, msg=bad):
                rc.check_version(bad)

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


class ReleaseSync(unittest.TestCase):
    def test_a_support_line_must_be_in_step_with_the_release_commit(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            repository(root)
            table = json.dumps({'schema_version': 2, 'reference': '26.3', 'lines': {'26.3': {}, '1.21.1': {}}})
            first = commit(root, {'config/lines/lines.json': table, 'shared.txt': 'one\n', 'README.md': 'a\n'}, 'main 1')
            release = commit(root, {'README.md': 'release notes pointer\n', 'docs/notes.md': 'n\n'}, 'docs only')
            run(root, 'tag', 'v0.22.0')
            run(root, 'checkout', '-q', '-b', 'support', first)
            sync = {'schema_version': 2, 'reference_commit': first, 'adapted': [], 'absent': []}
            commit(root, {'config/lines/sync.json': json.dumps(sync), 'config/lines/lines.json': None}, 'line')
            run(root, 'tag', 'v0.22.0+mc1.21.1')
            run(root, 'checkout', '-q', 'main')
            # Main moved on only in docs after the sync point: still in step.
            self.assertEqual(rc.unsynced('0.22.0', root), {})
            # A later release whose main changed a shared file the line never took.
            commit(root, {'shared.txt': 'two\n'}, 'main 2')
            run(root, 'tag', 'v0.23.0')
            run(root, 'tag', 'v0.23.0+mc1.21.1', 'support')
            problems = rc.unsynced('0.23.0', root)
            self.assertEqual(list(problems), ['1.21.1'])
            self.assertIn('shared.txt', problems['1.21.1'])


if __name__ == '__main__':
    unittest.main()
