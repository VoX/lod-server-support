#!/usr/bin/env python3
"""Decide whether a failed CI step may be retried.

The rule: a red is explained, not retried. A retry is allowed only when every failure in the
first attempt matches an open entry in config/test-flakes.json: a named cause that cannot be
fixed in this repository yet, with an expiry date. Anything else stays red.

  flake_gate.py --scope tier2 --log first-attempt.log

Exit 0: retry allowed (the matched entries are printed). Exit 1: not allowed (the reason is
printed). An entry past its `expires` date allows nothing: fix the cause or renew the entry
with a reason.

Entry: {"id", "scope", "signature" (regex), "test" (regex on the failed test id; omit for a
crash that has no test failure), "expires" (YYYY-MM-DD), "cause", "catalog"}.
"""
import argparse
import datetime
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANSI = re.compile(r'\x1b\[[0-9;]*m')
FAILED_HEADER = re.compile(r'\d+ required tests failed')
FAILED_ROW = re.compile(r'\s{3}- (\S+?): (.*)$')


def failed_tests(text):
    """{test id: message} from the game-test summary. The log repeats each line in several
    formats, so rows are merged by test id."""
    out = {}
    in_summary = False
    for raw in text.splitlines():
        line = ANSI.sub('', raw)
        if FAILED_HEADER.search(line):
            in_summary = True
            continue
        if not in_summary:
            continue
        row = FAILED_ROW.search(line)
        if row:
            out[row.group(1)] = row.group(2)
        else:
            in_summary = False
    return out


def decide(entries, scope, text, today):
    """(allowed, lines to print)."""
    scoped = [entry for entry in entries if entry['scope'] == scope]
    live = [entry for entry in scoped if datetime.date.fromisoformat(entry['expires']) >= today]
    expired = [entry['id'] + ' (expired ' + entry['expires'] + ')' for entry in scoped if entry not in live]
    failures = failed_tests(text)
    plain = ANSI.sub('', text)
    if failures:
        matched, unmatched = [], []
        for test, message in sorted(failures.items()):
            hit = next((entry for entry in live if entry.get('test')
                        and re.search(entry['test'], test) and re.search(entry['signature'], message)), None)
            (matched if hit else unmatched).append(test + (' -> ' + hit['id'] if hit else ': ' + message[:160]))
        if unmatched:
            return False, ['not a known flake:'] + ['  ' + row for row in unmatched] + \
                (['expired entries: ' + ', '.join(expired)] if expired else [])
        return True, ['known flake:'] + ['  ' + row for row in matched]
    hit = next((entry for entry in live if not entry.get('test') and re.search(entry['signature'], plain)), None)
    if hit:
        return True, ['known failure without a test result: ' + hit['id']]
    return False, ['no test failure summary and no known crash signature in the log'] + \
        (['expired entries: ' + ', '.join(expired)] if expired else [])


def load(path):
    data = json.loads(Path(path).read_text())
    if data.get('schema_version') != 1:
        raise SystemExit('unsupported test-flakes schema')
    for entry in data['entries']:
        for key in ('id', 'scope', 'signature', 'expires', 'cause', 'catalog'):
            if not entry.get(key):
                raise SystemExit('flake entry needs ' + key + ': ' + json.dumps(entry)[:120])
        re.compile(entry['signature'])
        re.compile(entry.get('test', ''))
        datetime.date.fromisoformat(entry['expires'])
    return data['entries']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--scope', required=True)
    parser.add_argument('--log', required=True, type=Path)
    parser.add_argument('--catalog', type=Path, default=ROOT / 'config/test-flakes.json')
    parser.add_argument('--today', help='YYYY-MM-DD (tests)')
    args = parser.parse_args()
    today = datetime.date.fromisoformat(args.today) if args.today else datetime.datetime.now(datetime.timezone.utc).date()
    allowed, lines = decide(load(args.catalog), args.scope, args.log.read_text(errors='replace'), today)
    print('\n'.join(lines))
    return 0 if allowed else 1


if __name__ == '__main__':
    sys.exit(main())
