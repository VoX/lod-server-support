"""The checks of a real-stack run. Every function here is pure: it reads log text and facts that
the runner collected and returns a result with the lines it relied on, so a saved run can be
judged again (`realstack.py replay`) and the parsing can be tested without starting anything.

A check passes on positive evidence only. "No error was logged" is never enough on its own: each
check names the line or counter that proves the thing happened.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
ENTRY = re.compile(r'^\[\d\d:\d\d:\d\d(?:\.\d+)?\] '
                   r'\[(?P<thread>.*?)/(?P<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\]'
                   r'(?: \[(?P<logger>[^\]]*)\])?: ?(?P<message>.*)$')
# Paper's console names no thread and puts a plugin's logger in front of the plugin's message:
# "[12:00:00 INFO]: [LSS] Starting LSS LOD request processing service".
PAPER_ENTRY = re.compile(r'^\[\d\d:\d\d:\d\d (?P<level>TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\]: ?'
                         r'(?P<message>.*)$')
PAPER_LOGGER = re.compile(r'^\[(?P<logger>[A-Za-z0-9_.$-]+)\] (?P<message>.*)$')
LSS_MENTION = re.compile(r'(?<![a-z0-9])[lv]ss(?![a-z0-9])|lod[-_ ]?server[-_ ]?support'
                         r'|voxy[-_ ]?server[-_ ]?side|dev\.vox\.lss', re.IGNORECASE)
UNCAUGHT = re.compile(r'^Exception in thread "(?P<thread>[^"]*)"')
RIG_PATH = '<rig>'
SERVER_READY = re.compile(r'^Done \([0-9.]+s\)! For help, type "help"')
SERVICE_START = re.compile(r'^Starting \w+ LOD request processing service')
DIAG_MARKER = re.compile(r'^=== \w+ LOD Diagnostics ===$')
DIAG_FIELD = re.compile(r'^(?P<key>[A-Z][A-Za-z ()]*): (?P<value>.*)$')
DIAG_PLAYER = re.compile(r'^  (?P<name>\S+): (?P<value>.*\bsq=.*)$')
SERVER_HANDSHAKE = re.compile(r'^\w+ handshake received from (?P<name>\S+) '
                              r'\(protocol v(?P<protocol>\d+), capabilities=(?P<caps>\d+)\)')
SERVER_REGISTERED = re.compile(r'^Player (?P<name>\S+) registered for \w+ LOD request processing')
CLIENT_SESSION = re.compile(r'^Server session config received \(protocol v(?P<protocol>\d+), '
                            r'LOD distance: (?P<distance>\d+) chunks, enabled: (?P<enabled>\w+)\)')
CLIENT_SAVED = re.compile(r'^Saved (?P<count>\d+) cached column entries')
CLIENT_CONSUMER = re.compile(r'^Registered voxel column consumer: (?P<name>\S+)')
DIAG_LAST_FIELD = 'Bandwidth'
PLAYER_JOINED = '{player} joined the game'
# The client's own chat line, as the game logs it: "[System] [CHAT] [LSS] ...". The prefix is the
# build's short name. The sentence says "this mod" and names no brand: a Voxy Server Side client
# on a LOD Server Support server must not name the server's copy by its own brand.
NOTICE = re.compile(r'^(?:\[System\] )?\[CHAT\] \[(?P<short>LSS|VSS)\] (?P<text>.*)$')
BRANDS = {'LSS': 'LOD Server Support', 'VSS': 'Voxy Server Side'}
NOTICE_TEXTS = {
    'no_support': "This server doesn't seem to have this mod installed, so there is nothing for "
                  "it to show here. Nothing is wrong with your game.",
}
CRASH_MARKERS = ('---- Minecraft Crash Report ----', '#@!@# Game crashed!',
                 'A fatal error has been detected by the Java Runtime Environment')
MAX_EVIDENCE_CHARS = 400


@dataclass
class Entry:
    """One log record: its first line split into fields, plus any continuation lines."""
    thread: str
    level: str
    logger: str
    message: str
    lines: list[str] = field(default_factory=list)

    def text(self) -> str:
        """The whole record, stack trace included."""
        return '\n'.join(self.lines)

    def first_line(self) -> str:
        """The record's first line, shortened for a report."""
        return self.lines[0][:MAX_EVIDENCE_CHARS]


@dataclass
class CheckResult:
    """The outcome of one check: what it concluded and the lines it read."""
    name: str
    passed: bool
    message: str
    evidence: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        """JSON form for verdict.json."""
        return {'name': self.name, 'passed': self.passed, 'message': self.message,
                'evidence': self.evidence, 'notes': self.notes}


def strip_ansi(text: str) -> str:
    """Remove terminal colour codes (NeoForge colours its console)."""
    return ANSI.sub('', text)


def parse_entries(log_text: str) -> list[Entry]:
    """Split a Minecraft log into records. A line that does not start a record (a stack trace,
    wrapped output) belongs to the record before it; lines before the first record are dropped.
    An uncaught exception printed straight to the output counts as an ERROR record of its own."""
    entries: list[Entry] = []
    for raw in strip_ansi(log_text).splitlines():
        match = ENTRY.match(raw)
        paper = None if match else PAPER_ENTRY.match(raw)
        uncaught = UNCAUGHT.match(raw)
        if match or paper:
            fields = (match or paper).groupdict()
            message = fields['message']
            if message.startswith('System chat: '):
                message = message[len('System chat: '):]
            named = PAPER_LOGGER.match(message) if paper else None
            if named:
                fields['logger'], message = named.group('logger'), named.group('message')
            entries.append(Entry(fields.get('thread') or '', fields['level'],
                                 fields.get('logger') or '', message, [raw]))
        elif uncaught:
            entries.append(Entry(uncaught.group('thread'), 'ERROR', '', raw, [raw]))
        elif entries:
            entries[-1].lines.append(raw)
    return entries


def without_rig_paths(text: str, own_paths: tuple[str, ...]) -> str:
    """Replace the rig's own directories (its cache and run directory) with a neutral word.
    Their names contain "lss", so an unrelated error that prints a class path or a game
    directory would otherwise look as if it mentioned the mod."""
    for path in sorted((item for item in own_paths if item), key=len, reverse=True):
        text = text.replace(path, RIG_PATH)
    return text


def lss_entries(entries: list[Entry], levels: tuple[str, ...],
                own_paths: tuple[str, ...] = ()) -> list[Entry]:
    """Records at one of `levels` that mention LSS anywhere: message, logger or stack trace.
    The rig's own directory names do not count as a mention."""
    return [entry for entry in entries
            if entry.level in levels
            and LSS_MENTION.search(without_rig_paths(entry.text(), own_paths))]


def first_match(entries: list[Entry], pattern: re.Pattern) -> tuple[Entry, re.Match] | None:
    """The first record whose message matches."""
    for entry in entries:
        match = pattern.match(entry.message)
        if match:
            return entry, match
    return None


def all_matches(entries: list[Entry], pattern: re.Pattern) -> list[tuple[Entry, re.Match]]:
    """Every record whose message matches."""
    found = []
    for entry in entries:
        match = pattern.match(entry.message)
        if match:
            found.append((entry, match))
    return found


def parse_diag(diag_text: str) -> dict:
    """Read the last `lsslod diag` block in a piece of server console output.

    Returns {'present', 'fields', 'players', 'lines'}: `fields` maps a line's name ('Throughput',
    'Dialects', ...) to the rest of the line, `players` maps a player name to that player's line.

    The block's lines come from one thread, so lines of other threads between them are skipped.
    A Paper console names no thread: there, a line in the middle of the block that is not a
    diag line is taken for another thread's and skipped, up to the block's last field.
    """
    entries = parse_entries(diag_text)
    start = None
    for index, entry in enumerate(entries):
        if DIAG_MARKER.match(entry.message):
            start = index
    result = {'present': start is not None, 'fields': {}, 'players': {}, 'lines': []}
    if start is None:
        return result
    thread = entries[start].thread
    result['lines'].append(entries[start].first_line())
    for entry in entries[start + 1:]:
        if entry.thread != thread:
            continue
        player = DIAG_PLAYER.match(entry.message)
        named = DIAG_FIELD.match(entry.message)
        if player:
            result['players'][player.group('name')] = player.group('value')
        elif named and not result['players']:
            result['fields'].setdefault(named.group('key'), named.group('value'))
        elif not thread and not result['players'] and DIAG_LAST_FIELD not in result['fields']:
            continue
        else:
            break
        result['lines'].append(entry.first_line())
    return result


def lss_warnings(entries: list[Entry], own_paths: tuple[str, ...]) -> list[str]:
    """WARN records that mention LSS, for the report (they never fail a check)."""
    return ['WARN: ' + entry.first_line() for entry in lss_entries(entries, ('WARN',), own_paths)][:20]


def counter(text: str | None, name: str) -> int | None:
    """The integer after `name=` in a diag line, or None."""
    if text is None:
        return None
    match = re.search(r'(?<![A-Za-z0-9_])' + re.escape(name) + r'=(\d+)', text)
    return int(match.group(1)) if match else None


def check_server_started(server_log: str, diag_text: str,
                         own_paths: tuple[str, ...] = ()) -> CheckResult:
    """The server finished starting, LSS started its service and reports itself enabled, and no
    ERROR record in the server log mentions LSS."""
    entries = parse_entries(server_log)
    diag = parse_diag(diag_text)
    ready = first_match(entries, SERVER_READY)
    service = first_match(entries, SERVICE_START)
    config = diag['fields'].get('Config')
    errors = lss_entries(entries, ('ERROR', 'FATAL'), own_paths)
    evidence = [found[0].first_line() for found in (ready, service) if found]
    if config is not None:
        evidence.append('diag Config: ' + config)
    notes = lss_warnings(entries, own_paths)
    name = 'server_started'
    if errors:
        return CheckResult(name, False, f'{len(errors)} ERROR record(s) in the server log mention LSS',
                           evidence + [entry.text()[:2000] for entry in errors[:5]], notes)
    if not ready:
        return CheckResult(name, False, 'the server never logged its "Done" line', evidence, notes)
    if not service:
        return CheckResult(name, False, 'LSS did not log that its request service started',
                           evidence, notes)
    if config is None or 'enabled=true' not in config:
        return CheckResult(name, False, 'lsslod diag did not report Config: enabled=true',
                           evidence, notes)
    return CheckResult(name, True, 'server started, LSS service running and enabled, '
                       'no LSS ERROR in the server log', evidence, notes)


def check_handshake(server_log: str, client_log: str, diag_text: str, player: str,
                    protocol: int) -> CheckResult:
    """The client joined and both ends agree that an LSS session on the current protocol exists:
    the server logged the handshake and the registration, the client logged the session config,
    and the diag's dialect gauge counts the session."""
    server = parse_entries(server_log)
    client = parse_entries(client_log)
    diag = parse_diag(diag_text)
    name = 'handshake'
    evidence: list[str] = []
    joined = [entry for entry in server if entry.message == f'{player} joined the game']
    if not joined:
        return CheckResult(name, False, f'the server never logged "{player} joined the game"')
    evidence.append(joined[0].first_line())
    handshakes = [(entry, match) for entry, match in all_matches(server, SERVER_HANDSHAKE)
                  if match.group('name') == player]
    if not handshakes:
        return CheckResult(name, False, f'the server received no LSS handshake from {player}',
                           evidence)
    evidence.append(handshakes[-1][0].first_line())
    announced = int(handshakes[-1][1].group('protocol'))
    if announced != protocol:
        return CheckResult(name, False, f'the client announced protocol v{announced}, '
                           f'expected v{protocol}', evidence)
    registered = [entry for entry, match in all_matches(server, SERVER_REGISTERED)
                  if match.group('name') == player]
    if not registered:
        return CheckResult(name, False, f'the server did not register {player} for LOD requests',
                           evidence)
    evidence.append(registered[-1].first_line())
    sessions = all_matches(client, CLIENT_SESSION)
    if not sessions:
        return CheckResult(name, False, 'the client log has no "Server session config received" '
                           'line', evidence)
    session_entry, session = sessions[-1]
    evidence.append(session_entry.first_line())
    if int(session.group('protocol')) != protocol or session.group('enabled') != 'true':
        return CheckResult(name, False, f'the client received protocol '
                           f'v{session.group("protocol")}, enabled={session.group("enabled")}; '
                           f'expected v{protocol}, enabled=true', evidence)
    dialects = diag['fields'].get('Dialects')
    live = counter(dialects, f'v{protocol}')
    if dialects is not None:
        evidence.append('diag Dialects: ' + dialects)
    if not live:
        return CheckResult(name, False, f'lsslod diag counts no live v{protocol} session', evidence)
    return CheckResult(name, True, f'{player} joined and negotiated LSS protocol v{protocol} '
                       f'(capabilities={handshakes[-1][1].group("caps")})', evidence)


def check_columns_sent(diag_text: str, client_log: str, player: str) -> CheckResult:
    """Terrain was actually sent: the server-wide and the per-player `sent=` counters in the
    final `lsslod diag` are both above zero."""
    diag = parse_diag(diag_text)
    name = 'columns_sent'
    if not diag['present']:
        return CheckResult(name, False, 'no lsslod diag output was captured')
    throughput = diag['fields'].get('Throughput')
    player_line = diag['players'].get(player)
    evidence = []
    if throughput is not None:
        evidence.append('diag Throughput: ' + throughput)
    for key in ('Sources (total)', 'Bandwidth'):
        if key in diag['fields']:
            evidence.append(f'diag {key}: ' + diag['fields'][key])
    if player_line is not None:
        evidence.append(f'diag {player}: ' + player_line)
    notes = []
    client = parse_entries(client_log)
    consumers = [match.group('name') for _, match in all_matches(client, CLIENT_CONSUMER)]
    if consumers:
        notes.append('client column consumers: ' + ', '.join(consumers))
    saved = all_matches(client, CLIENT_SAVED)
    if saved:
        notes.append('client: ' + saved[-1][0].message)
    total = counter(throughput, 'sent')
    for_player = counter(player_line, 'sent')
    if not total:
        return CheckResult(name, False, 'the server sent no columns (Throughput: sent=0 or missing)',
                           evidence, notes)
    if not for_player:
        return CheckResult(name, False, f'the server sent no columns to {player}', evidence, notes)
    return CheckResult(name, True, f'the server sent {for_player} columns to {player} '
                       f'({total} in total)', evidence, notes)


def check_client_clean(client_log: str, crash_files: list[str], client_alive_at_end: bool,
                       player: str, observed_server_log: str,
                       own_paths: tuple[str, ...] = ()) -> CheckResult:
    """The client did not crash and logged no LSS ERROR. The witness that it really ran to the
    end is that its process was still alive at the end of the observation window and that the
    server never logged a lost connection for it in that window. `observed_server_log` is the
    server log up to the end of the window, before the runner's own kick and shutdown."""
    entries = parse_entries(client_log)
    name = 'client_clean'
    errors = lss_entries(entries, ('ERROR', 'FATAL'), own_paths)
    notes = lss_warnings(entries, own_paths)
    plain = strip_ansi(client_log)
    markers = [marker for marker in CRASH_MARKERS if marker in plain]
    evidence = [f'{len(entries)} client log records read', f'crash files: {crash_files or "none"}',
                f'client process alive at the end of the observation window: {client_alive_at_end}']
    if crash_files or markers:
        return CheckResult(name, False, 'the client crashed', evidence + markers, notes)
    if errors:
        return CheckResult(name, False, f'{len(errors)} ERROR record(s) in the client log mention LSS',
                           evidence + [entry.text()[:2000] for entry in errors[:5]], notes)
    if not entries:
        return CheckResult(name, False, 'the client wrote no log', evidence, notes)
    if not client_alive_at_end:
        return CheckResult(name, False, 'the client process ended during the observation window',
                           evidence, notes)
    lost = [entry.first_line() for entry in parse_entries(observed_server_log)
            if entry.message.startswith(f'{player} lost connection')]
    if lost:
        return CheckResult(name, False, 'the client lost its connection during the observation '
                           'window', evidence + lost, notes)
    return CheckResult(name, True, 'no crash report, no LSS ERROR in the client log, and the client '
                       'stayed connected for the whole observation window', evidence, notes)


def check_server_ready(server_log: str) -> CheckResult:
    """The server finished starting. For scenarios whose server has no LSS to ask."""
    name = 'server_ready'
    found = first_match(parse_entries(server_log), SERVER_READY)
    if not found:
        return CheckResult(name, False, 'the server never logged its "Done" line')
    return CheckResult(name, True, 'the server started', [found[0].first_line()])


def check_client_joined(observed_server_log: str, player: str) -> CheckResult:
    """The server logged the player's join during the observation window."""
    name = 'client_joined'
    wanted = PLAYER_JOINED.format(player=player)
    joined = [entry.first_line() for entry in parse_entries(observed_server_log)
              if entry.message.startswith(wanted)]
    if not joined:
        return CheckResult(name, False, f'the server never logged "{wanted}"')
    return CheckResult(name, True, 'the player joined', joined[:1])


def check_client_notice(client_log: str, kind: str, brand: str) -> CheckResult:
    """The client told the player, in chat and exactly once, why no distant terrain is arriving.
    The line must start with the expected build's short name and read exactly as written, with no
    brand name in the sentence."""
    name = 'client_notice'
    notices = [(entry, match) for entry, match in all_matches(parse_entries(client_log), NOTICE)]
    evidence = [entry.first_line() for entry, _match in notices]
    if not notices:
        return CheckResult(name, False, 'the client logged no chat notice of its own', evidence)
    if len(notices) > 1:
        return CheckResult(name, False, f'{len(notices)} notices on one connection; the rule is '
                           'at most one', evidence)
    match = notices[0][1]
    expected = NOTICE_TEXTS[kind]
    if match.group('short') != brand:
        return CheckResult(name, False, f'the notice is prefixed [{match.group("short")}], '
                           f'expected [{brand}]', evidence)
    if match.group('text').strip() != expected:
        return CheckResult(name, False, f'the notice does not read: {expected}', evidence)
    named = [full for full in BRANDS.values() if full in match.group('text')]
    if named:
        return CheckResult(name, False, f'the notice names a brand ({named[0]})', evidence)
    return CheckResult(name, True, f'one notice, from the {brand} build', evidence)


def check_screenshot(shot: dict | None) -> CheckResult:
    """A screenshot of the private display was saved. The picture is not judged in this version;
    the check only proves that a real image of the right size was captured while a Minecraft
    window existed."""
    name = 'screenshot'
    if not shot or not shot.get('path'):
        return CheckResult(name, False, 'no screenshot was saved')
    evidence = [f'{shot["path"]}: {shot.get("width")}x{shot.get("height")}, {shot.get("bytes")} bytes',
                f'windows on the display: {shot.get("windows") or "none"}']
    notes = [f'distinct colours in the picture: {shot.get("distinct_colours")} (not judged)']
    if not shot.get('bytes') or not shot.get('width') or not shot.get('height'):
        return CheckResult(name, False, 'the screenshot file is empty', evidence, notes)
    if not any(title.startswith('Minecraft') for title in shot.get('windows') or []):
        return CheckResult(name, False, 'no Minecraft window existed when the screenshot was taken',
                           evidence, notes)
    return CheckResult(name, True, 'screenshot saved', evidence, notes)


def run_checks(names: list[str], material: dict) -> list[CheckResult]:
    """Run the named checks over collected material: server_log, observed_server_log (the part
    written before the runner began to shut things down), client_log, diag, player, protocol,
    crash_files, client_alive_at_end, screenshot, own_paths."""
    own_paths = tuple(material.get('own_paths', ()))
    server_log = material.get('server_log', '')
    client_log = material.get('client_log', '')
    diag = material.get('diag', '')
    player = material['player']
    table = {
        'server_started': lambda: check_server_started(server_log, diag, own_paths),
        'handshake': lambda: check_handshake(server_log, client_log, diag, player,
                                             material['protocol']),
        'columns_sent': lambda: check_columns_sent(diag, client_log, player),
        'client_clean': lambda: check_client_clean(client_log, material.get('crash_files', []),
                                                   bool(material.get('client_alive_at_end')),
                                                   player,
                                                   material.get('observed_server_log', ''),
                                                   own_paths),
        'screenshot': lambda: check_screenshot(material.get('screenshot')),
        'server_ready': lambda: check_server_ready(server_log),
        'client_joined': lambda: check_client_joined(material.get('observed_server_log', ''), player),
        'client_notice': lambda: check_client_notice(client_log, material.get('notice', 'no_support'),
                                                     material.get('brand', 'LSS')),
    }
    return [table[name]() for name in names]


def assemble_verdict(row: dict, results: list[CheckResult], context: dict) -> dict:
    """Build verdict.json. A run passes only when every check the row lists was run and passed and
    the run itself did not abort; `context` carries the run's identity, timing and pins."""
    by_name = {result.name: result for result in results}
    missing = [name for name in row['checks'] if name not in by_name]
    failed = [result.name for result in results if not result.passed]
    aborted = context.get('aborted')
    passed = not missing and not failed and not aborted
    verdict = {'row': row['id'], 'passed': passed, 'failed_checks': failed,
               'checks_not_run': missing, 'aborted': aborted,
               'checks': [by_name[name].as_dict() for name in row['checks'] if name in by_name]}
    verdict.update({key: value for key, value in context.items() if key != 'aborted'})
    return verdict
