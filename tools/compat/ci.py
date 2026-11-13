#!/usr/bin/env python3
"""The cross-line check CI runs on every line.

Every line: its authored facts equal its build definitions and its role agrees with the files it
carries (catalog.validate). A follower also proves it is in step with main (tools/lines/lines.py):
every shared file equals main at the recorded sync point, and with --base the port itself is
checked. The reference line (main) and frozen lines have nothing further to prove here.

    ci.py [--fetch] [--base <ref>] [--report <file>]

--fetch may contact origin for main and the sync point. --base is the commit the change started
from: the target branch tip for a pull request, the previous tip for a push.
"""
import argparse
import json
import sys
from pathlib import Path

from catalog import ROOT, Invalid, load, validate

sys.path.insert(0, str(ROOT / 'tools/lines'))
import lines


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--fetch', action='store_true')
    parser.add_argument('--base')
    parser.add_argument('--report', type=Path, default=Path('compatibility-candidate-report.json'))
    args = parser.parse_args()
    try:
        validate(ROOT)
        role = load(ROOT / 'config/compatibility/line.json')['role']
        if role == 'follower':
            base = args.base
            if base and not lines.commit_exists(ROOT, base):
                raise Invalid('the base commit ' + base + ' is not in this clone')
            report = lines.check(ROOT, base, args.fetch)
        else:
            report = dict(issues=[], residue=[], passed=True)
        report['role'] = role
        args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        for item in report['residue']:
            print('hand-edited in this port: ' + item['path'] + ' (' + item['kind'] + ')')
            for row in item['diff']:
                print('    ' + row)
        lines.print_issues(report['issues'])
        if not report['passed']:
            raise Invalid(str(len(report['issues'])) + ' cross-line issue(s); see ' + str(args.report))
        print('line role ' + role + ': ok')
    except (Invalid, lines.Invalid, OSError, KeyError, ValueError) as error:
        parser.exit(1, 'compat CI: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
