#!/usr/bin/env python3
"""Capture actual JUnit cases and compare a placement change without double counting."""
import argparse
import collections
import json
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
    for item, count in (old - new).items():
        problems.append(f'Lost case/outcome {item}: {count}')
    for item, count in (new - old).items():
        problems.append(f'Unexpected case/outcome {item}: {count}')
    return {'before_cases': sum(old.values()), 'after_cases': sum(new.values()), 'problems': problems}


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
