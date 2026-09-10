#!/usr/bin/env python3
"""Capture actual JUnit cases and compare a placement change without double counting."""
import argparse
import collections
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def capture(root):
    cases = []
    for module in ('common', 'fabric', 'paper', 'neoforge'):
        for path in sorted((root / module / 'build/test-results').glob('*/TEST-*.xml')):
            suite = ET.parse(path).getroot()
            for case in suite.findall('testcase'):
                cases.append(dict(task=f':{module}:{path.parent.name}',
                                  identity=case.attrib['classname'] + '#' + case.attrib['name'],
                                  outcome='failed' if case.find('failure') is not None or case.find('error') is not None
                                  else 'skipped' if case.find('skipped') is not None else 'passed',
                                  seconds=float(case.attrib.get('time', 0)),
                                  xml=str(path.relative_to(root))))
    if not cases:
        raise ValueError('No executed test XML found; source annotations are not test cases')
    return {'schema': 1, 'cases': cases}


def compare(before, after, moves):
    moved = {row['class']: row for row in moves['moves']}
    def selected(data, new):
        result = collections.Counter()
        for case in data['cases']:
            cls = case['identity'].split('#', 1)[0]
            if cls in moved and case['task'] == moved[cls]['after_task' if new else 'before_task']:
                result[(case['identity'], case['outcome'])] += 1
        return result
    old, new = selected(before, False), selected(after, True)
    problems = []
    for cls, move in moved.items():
        if not any(identity.startswith(cls + '#') for identity, _ in old):
            problems.append('Missing baseline execution: ' + cls)
        if any(case['identity'].startswith(cls + '#') and case['task'] == move['before_task']
               for case in after['cases']):
            problems.append('Old task XML still contains moved class (clean its stale reports): ' + cls)
    # Original migration evidence remains immutable; only exact reviewed later tests
    # extend the expected post-move inventory.
    additions = collections.Counter()
    declared = set()
    rows = moves.get('post_migration_additions', [])
    if not isinstance(rows, list):
        problems.append('post_migration_additions must be a list')
        rows = []
    for row in rows:
        if not isinstance(row, dict):
            problems.append('Invalid post-migration addition declaration')
            continue
        identity = row.get('identity')
        if not isinstance(identity, str) or '#' not in identity:
            problems.append('Invalid addition identity')
            continue
        cls = identity.split('#', 1)[0]
        if identity in declared:
            problems.append('Duplicate addition declaration: ' + identity)
            continue
        declared.add(identity)
        if (cls not in moved or row.get('task') != moved[cls]['after_task']
                or row.get('outcome') != 'passed' or type(row.get('count')) is not int
                or row['count'] != 1 or not isinstance(row.get('rationale'), str)
                or not row['rationale'].strip()
                or not isinstance(row.get('introducing_commit'), str)
                or re.fullmatch(r'[0-9a-f]{40}', row['introducing_commit']) is None):
            problems.append('Invalid addition contract: ' + identity)
            continue
        if any(case['identity'] == identity for case in before['cases']):
            problems.append('Addition already exists in baseline: ' + identity)
            continue
        actual = [case for case in after['cases'] if case['identity'] == identity]
        if (len(actual) != 1 or actual[0]['task'] != row['task']
                or actual[0]['outcome'] != row['outcome']):
            problems.append('Addition missing, duplicated, or wrong task/outcome: ' + identity)
        additions[(identity, row['outcome'])] += row['count']
    expected = old + additions
    for item, count in (expected - new).items():
        problems.append(f'Lost case/outcome {item}: {count}')
    for item, count in (new - expected).items():
        problems.append(f'Unexpected case/outcome {item}: {count}')
    return {'before_cases': sum(old.values()), 'after_cases': sum(new.values()), 'declared_addition_cases': sum(additions.values()), 'problems': problems}


def validate_moves(root, moves):
    problems = []
    seen = set()
    for move in moves['moves'] + moves.get('helpers', []):
        if move['class'] in seen:
            problems.append('Duplicate move identity: ' + move['class'])
        seen.add(move['class'])
        old, new = root / move['before'], root / move['after']
        if not old.resolve().is_relative_to(root.resolve()) or not new.resolve().is_relative_to(root.resolve()):
            problems.append('Move path escapes repository: ' + move['class'])
            continue
        if old.exists():
            problems.append('Migrated source reappeared at old owner: ' + move['before'])
        if not new.is_file():
            problems.append('Migrated source missing at new owner: ' + move['after'])
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    cap = commands.add_parser('capture')
    cap.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    cap.add_argument('--output', type=Path, required=True)
    diff = commands.add_parser('compare')
    for name in ('before', 'after', 'moves'):
        diff.add_argument('--' + name, type=Path, required=True)
    valid = commands.add_parser('validate-moves')
    valid.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    valid.add_argument('--moves', type=Path)
    args = parser.parse_args()
    if args.command == 'capture':
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(capture(args.root), indent=2) + '\n')
    elif args.command == 'validate-moves':
        manifest = args.moves or args.root / 'docs/implementation/test-moves.json'
        problems = validate_moves(args.root, json.loads(manifest.read_text()))
        print(json.dumps({'problems': problems}, indent=2))
        return bool(problems)
    else:
        result = compare(*(json.loads(getattr(args, name).read_text()) for name in ('before','after','moves')))
        print(json.dumps(result, indent=2))
        return bool(result['problems'])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
