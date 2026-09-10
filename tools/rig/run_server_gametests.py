#!/usr/bin/env python3
"""Own a native server test process and publish proof only from its exact XML."""
import json
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    root = Path(sys.argv[1]).resolve()
    sys.path.insert(0, sys.argv[2])
    from rig import read, write, sha
    from proof import check_server_gametest_report
    manifest, scenario = read(root / 'manifest.json'), read(root / 'scenario.json')
    command = read(root / 'gametest-command.json')
    argv = [part.replace('{run}', str(root)) for part in command['argv']]
    report = root / 'evidence' / 'gametests.xml'
    if report.exists():
        raise ValueError('native report already exists before this run')
    log = root / 'evidence' / 'gametest-server.log'
    with log.open('x') as output:
        process = subprocess.Popen(argv, cwd=root / 'server', stdout=output, stderr=subprocess.STDOUT)
        code = process.wait()
    value = {key: manifest[key] for key in ('run_id', 'profile_hash', 'scenario_hash', 'run_hash')}
    text = log.read_text(errors='replace')
    value.update(ready='Starting test server' in text, handshake=False, test_count=0,
                 assertions={key: False for key in scenario['assertions']}, failures=[])
    if code:
        value['failures'].append('native game-test process exit: ' + str(code))
    if report.is_file():
        value['gametest_report'] = {'artifact': 'gametests.xml', 'artifact_sha256': sha(report)}
        try:
            value['test_count'] = len(ET.parse(report).getroot().findall('.//testcase'))
        except ET.ParseError as error:
            value['failures'].append('native XML malformed: ' + str(error))
    value['failures'].extend(check_server_gametest_report(value, scenario, root))
    expected = command['c2me_version']
    if 'c2me ' + expected not in text:
        value['failures'].append('exact C2ME version absent from native loader log')
    if not value['failures']:
        # Each named test's successful completion includes the actual save, fresh
        # disk read and byte comparison; the fixture source is a staged run input.
        value['assertions'] = {key: True for key in scenario['assertions']}
    write(root / 'proof.json', value)
    # The rig observes proof before it shuts down this owned wrapper. A normal
    # Java exit must not race the rig's fail-closed premature-process-exit check.
    while True:
        time.sleep(1)


if __name__ == '__main__':
    main()
