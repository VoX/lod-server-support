#!/usr/bin/env python3
"""Reject test/fixture classes in every local LSS/VSS shipping-family jar, including nested jars."""
import argparse
import io
from pathlib import Path
import re
import zipfile


def fixture_classes(root):
    forbidden = set()
    paths = list((root / 'common/src/test').rglob('*.java'))
    paths += list((root / 'common/src/testFixtures').rglob('*.java'))
    paths += list((root / 'test-fixtures').rglob('*.java'))
    for source in paths:
        package = re.search(r'^package\s+([\w.]+)\s*;', source.read_text(), re.M)
        if package:
            forbidden.add(package[1].replace('.', '/') + '/' + source.stem)
    return forbidden


def violations(data, forbidden, prefix='', depth=0):
    if depth > 8:
        raise ValueError('Nested jar depth exceeds inspection bound')
    found = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for info in jar.infolist():
            name = info.filename
            if name.endswith('.class'):
                outer = name[:-6].split('$', 1)[0]
                if outer in forbidden:
                    found.append(prefix + name)
            elif name.endswith('.jar'):
                if info.file_size > 256 * 1024 * 1024:
                    raise ValueError('Nested jar exceeds inspection bound: ' + name)
                found += violations(jar.read(info), forbidden, prefix + name + '!/', depth + 1)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    forbidden = fixture_classes(args.root)
    if not forbidden:
        raise ValueError('No fixture/test inventory: cannot establish packaging exclusion')
    failures = []
    inspected = []
    for module in ('fabric', 'paper', 'neoforge'):
        for brand in ('lod-server-support', 'voxy-server-side'):
            jars = sorted((args.root / module / 'build/libs').glob(brand + '*.jar'))
            jars = [p for p in jars if not p.stem.endswith(('-sources', '-javadoc'))]
            if not jars:
                failures.append('Missing maintained artifact family: ' + module + '/' + brand)
            for path in jars:
                inspected.append(str(path.relative_to(args.root)))
                failures.extend(str(path.relative_to(args.root)) + '!/' + p
                                for p in violations(path.read_bytes(), forbidden))
    for path in inspected:
        print('inspected ' + path)
    for failure in failures:
        print('ERROR ' + failure)
    return bool(failures)


if __name__ == '__main__':
    raise SystemExit(main())
