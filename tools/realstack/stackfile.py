"""The matrix file (stacks.json): loading, validation and the `--lss` selector. Pure, no I/O
beyond reading the file it is given."""
from __future__ import annotations

import json
import re
from pathlib import Path

SCHEMA = 1
SERVER_PLATFORMS = ('fabric', 'neoforge', 'paper')
CLIENT_LOADERS = ('fabric', 'neoforge')
STATUSES = ('ready', 'blocked')
KNOWN_CHECKS = ('server_started', 'handshake', 'columns_sent', 'client_clean', 'screenshot')
PIN_KEYS = ('slug', 'project_id', 'version_id', 'version_number', 'file', 'sha512')
ROW_ID = re.compile(r'^[a-z0-9][a-z0-9.+-]*$')
HEX = re.compile(r'^[0-9a-f]+$')
JAR_NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._+-]*\.jar$')
MODRINTH_ID = re.compile(r'^[A-Za-z0-9]{6,16}$')
DEFAULT_PATH = Path(__file__).resolve().parent / 'stacks.json'


class StackError(ValueError):
    """The matrix file or a selector is not usable."""


def load(path: Path = DEFAULT_PATH) -> dict:
    """Read and validate the matrix file. Raises StackError naming every problem found."""
    try:
        document = json.loads(Path(path).read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise StackError(f'cannot read {path}: {error}') from error
    problems = validate(document)
    if problems:
        raise StackError(f'{path}:\n  ' + '\n  '.join(problems))
    return document


def find_row(document: dict, row_id: str) -> dict:
    """The row with this id."""
    for row in document['rows']:
        if row['id'] == row_id:
            return row
    known = ', '.join(row['id'] for row in document['rows'])
    raise StackError(f'no row {row_id!r}; rows are: {known}')


def validate(document) -> list[str]:
    """Every problem in a matrix document, as readable sentences. Empty means valid."""
    problems: list[str] = []
    if not isinstance(document, dict):
        return ['the document must be a JSON object']
    if document.get('schema') != SCHEMA:
        problems.append(f'schema must be {SCHEMA}')
    lss = document.get('lss')
    if not isinstance(lss, dict):
        problems.append('lss must be an object')
    else:
        if not isinstance(lss.get('modrinth_project'), str) or not lss.get('modrinth_project'):
            problems.append('lss.modrinth_project must name the Modrinth project')
        if not isinstance(lss.get('protocol'), int) or isinstance(lss.get('protocol'), bool):
            problems.append('lss.protocol must be the protocol number a current session negotiates')
    rows = document.get('rows')
    if not isinstance(rows, list) or not rows:
        problems.append('rows must be a non-empty list')
        return problems
    seen: set[str] = set()
    for index, row in enumerate(rows):
        label = row.get('id', f'#{index}') if isinstance(row, dict) else f'#{index}'
        for problem in validate_row(row):
            problems.append(f'row {label}: {problem}')
        if isinstance(row, dict) and row.get('id') in seen:
            problems.append(f'row {label}: duplicate id')
        if isinstance(row, dict):
            seen.add(row.get('id'))
    return problems


def validate_row(row) -> list[str]:
    """Problems in one row."""
    if not isinstance(row, dict):
        return ['must be an object']
    problems: list[str] = []
    if not isinstance(row.get('id'), str) or not ROW_ID.match(row.get('id', '')):
        problems.append('id must be lower-case letters, digits, dots, plus and hyphens')
    if row.get('status') not in STATUSES:
        problems.append(f'status must be one of {", ".join(STATUSES)}')
    if row.get('status') == 'blocked' and not row.get('blocked_reason'):
        problems.append('a blocked row needs blocked_reason with the exact reason')
    if not isinstance(row.get('minecraft'), str) or not row.get('minecraft'):
        problems.append('minecraft must be the Minecraft version')
    checks = row.get('checks')
    if not isinstance(checks, list) or not checks:
        problems.append('checks must list at least one check')
    else:
        for name in checks:
            if name not in KNOWN_CHECKS:
                problems.append(f'unknown check {name!r} (known: {", ".join(KNOWN_CHECKS)})')
        if len(set(checks)) != len(checks):
            problems.append('checks lists a check twice')
    problems += validate_side(row.get('server'), 'server', 'platform', SERVER_PLATFORMS)
    problems += validate_side(row.get('client'), 'client', 'loader', CLIENT_LOADERS)
    return problems


def validate_side(side, label: str, kind_key: str, kinds: tuple[str, ...]) -> list[str]:
    """Problems in a row's server or client block."""
    if not isinstance(side, dict):
        return [f'{label} must be an object']
    problems: list[str] = []
    kind = side.get(kind_key)
    if kind not in kinds:
        problems.append(f'{label}.{kind_key} must be one of {", ".join(kinds)}')
    if kind == 'fabric' and not isinstance(side.get('loader_version'), str):
        problems.append(f'{label}.loader_version must pin the Fabric loader')
    if kind == 'neoforge':
        neoforge = side.get('neoforge')
        if not isinstance(neoforge, dict) or not isinstance(neoforge.get('version'), str):
            problems.append(f'{label}.neoforge.version must pin the NeoForge build')
        elif not is_hex(neoforge.get('installer_sha256'), 64):
            problems.append(f'{label}.neoforge.installer_sha256 must be the installer sha256')
    if kind == 'paper':
        paper = side.get('paper')
        if not isinstance(paper, dict) or not isinstance(paper.get('build'), int) \
                or isinstance(paper.get('build'), bool):
            problems.append(f'{label}.paper.build must pin the Paper build number')
        elif not isinstance(paper.get('file'), str) or not JAR_NAME.match(paper['file']):
            problems.append(f'{label}.paper.file must be the plain .jar file name of that build')
        elif not is_hex(paper.get('sha256'), 64):
            problems.append(f'{label}.paper.sha256 must be the server jar sha256')
    selector = side.get('lss')
    if not isinstance(selector, dict) or not isinstance(selector.get('loader'), str) \
            or not isinstance(selector.get('jar_prefix'), str):
        problems.append(f'{label}.lss must give loader (the Modrinth loader of the published jar) '
                        'and jar_prefix (the file name prefix in a directory of built jars)')
    jvm_args = side.get('jvm_args', [])
    if not isinstance(jvm_args, list) or not all(isinstance(item, str) for item in jvm_args):
        problems.append(f'{label}.jvm_args must be a list of strings')
    mods = side.get('mods')
    if not isinstance(mods, list):
        problems.append(f'{label}.mods must be a list')
        return problems
    files: set[str] = set()
    for index, pin in enumerate(mods):
        name = pin.get('slug', f'#{index}') if isinstance(pin, dict) else f'#{index}'
        for problem in validate_pin(pin):
            problems.append(f'{label}.mods {name}: {problem}')
        if isinstance(pin, dict):
            if pin.get('file') in files:
                problems.append(f'{label}.mods {name}: file name used twice')
            files.add(pin.get('file'))
    return problems


def validate_pin(pin) -> list[str]:
    """Problems in one pinned Modrinth file."""
    if not isinstance(pin, dict):
        return ['must be an object']
    problems = [f'{key} is missing' for key in PIN_KEYS
                if not isinstance(pin.get(key), str) or not pin.get(key)]
    if isinstance(pin.get('file'), str) and not JAR_NAME.match(pin['file']):
        problems.append('file must be a plain .jar file name')
    for key in ('project_id', 'version_id'):
        if isinstance(pin.get(key), str) and pin[key] and not MODRINTH_ID.match(pin[key]):
            problems.append(f'{key} must be a Modrinth id (letters and digits)')
    if isinstance(pin.get('sha512'), str) and not is_hex(pin['sha512'], 128):
        problems.append('sha512 must be 128 lower-case hex digits')
    return problems


def is_hex(value, length: int) -> bool:
    """True for a lower-case hex string of exactly `length` digits."""
    return isinstance(value, str) and len(value) == length and bool(HEX.match(value))


def parse_lss_selector(text: str) -> tuple[str, str]:
    """Split `published:<version>` or `dir:<path>` into (kind, value)."""
    kind, separator, value = text.partition(':')
    if not separator or kind not in ('published', 'dir') or not value:
        raise StackError(f'--lss must be published:<version> or dir:<path>, not {text!r}')
    if kind == 'published' and not re.match(r'^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$', value):
        raise StackError(f'not an LSS version: {value!r}')
    return kind, value


def choose_built_jar(names: list[str], jar_prefix: str, minecraft: str) -> str:
    """Pick this row's jar out of a directory of locally built jars (a release gate's `jars/`).

    Built jars are named <prefix>.jar or <prefix>-<version>+<minecraft>.jar. A jar tagged with a
    different Minecraft version is never used; exactly one candidate must remain.
    """
    pattern = re.compile(re.escape(jar_prefix) + r'(-[0-9][^+]*)?(\+(?P<mc>[0-9][0-9.]*))?\.jar$')
    candidates = []
    for name in sorted(names):
        match = pattern.match(name)
        if match and match.group('mc') in (None, minecraft):
            candidates.append(name)
    if len(candidates) != 1:
        found = ', '.join(candidates) or 'none'
        raise StackError(f'expected exactly one {jar_prefix}*.jar for Minecraft {minecraft}, '
                         f'found: {found}')
    return candidates[0]
