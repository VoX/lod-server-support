"""The mod's settings files, as text. Pure: no I/O.

The files are annotated YAML: every key carries a comment that explains it. A scenario that
needs another value therefore changes the one line that holds it and nothing else. The file is
never parsed and written back, which would drop the comments and could reorder the keys.

A path is a tuple of keys from the top of the file, written without quotes:
('lod', 'distance', 'by_dimension', 'minecraft:overworld').
"""
from __future__ import annotations

import re

KEY_LINE = re.compile(r'^(?P<indent> *)(?P<key>"[^"]*"|\'[^\']*\'|[A-Za-z0-9_.-]+):(?P<rest>(?: .*)?)$')
PLAIN = re.compile(r'^[A-Za-z0-9_][A-Za-z0-9_.-]*$')


class SettingsError(ValueError):
    """A settings file does not have the shape an edit needs."""


def dotted(path: tuple[str, ...]) -> str:
    """A path as people write it: lod.distance.by_dimension."minecraft:overworld"."""
    return '.'.join(key if PLAIN.match(key) else f'"{key}"' for key in path)


def key_lines(text: str) -> list[tuple[int, tuple[str, ...], re.Match]]:
    """Every line that holds a key, with its line number and the key's full path. Comment
    lines are not keys, so a setting that is only shown commented out is not found."""
    found = []
    stack: list[tuple[int, str]] = []
    for number, line in enumerate(text.splitlines()):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = KEY_LINE.match(line)
        if not match:
            continue
        indent = len(match.group('indent'))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, match.group('key').strip('"\'')))
        found.append((number, tuple(key for _, key in stack), match))
    return found


def split_rest(rest: str) -> tuple[str, str, str]:
    """What follows a key's colon, as (gap, value, tail): the spaces before the value, the
    value itself ('' when the key opens a block) and the trailing comment with its spacing."""
    body = rest.lstrip(' ')
    gap = rest[:len(rest) - len(body)]
    if body.startswith('#'):
        return gap, '', body
    if body[:1] in ('"', "'"):
        end = body.find(body[0], 1)
        if end < 0:
            raise SettingsError(f'unclosed quote in {rest!r}')
        value = body[:end + 1]
    else:
        comment = re.search(r'\s+#', body)
        value = body[:comment.start() if comment else len(body)].rstrip()
    tail = body[len(value):]
    if tail.strip() and not tail.lstrip().startswith('#'):
        raise SettingsError(f'cannot read the value in {rest!r}')
    return gap, value, tail


def locate(text: str, path: tuple[str, ...]) -> tuple[int, re.Match, tuple[str, str, str]] | None:
    """The line that holds `path`: its number, the key match and (gap, value, tail). None
    when the file has no such key; an error when it has it twice."""
    hits = [(number, match) for number, found, match in key_lines(text) if found == tuple(path)]
    if not hits:
        return None
    if len(hits) > 1:
        raise SettingsError(f'{dotted(path)} is in the file {len(hits)} times')
    number, match = hits[0]
    return number, match, split_rest(match.group('rest'))


def value_of(text: str, path: tuple[str, ...]) -> str | None:
    """The value written on a key's own line, as text ('512', 'true', '{}'). None when the
    file has no such key; '' when the key opens a block of nested keys."""
    found = locate(text, path)
    return None if found is None else found[2][1]


def line_of(text: str, path: tuple[str, ...]) -> str:
    """The whole line that holds a key, for a report."""
    found = locate(text, path)
    return '' if found is None else text.splitlines()[found[0]].strip()


def set_value(text: str, path: tuple[str, ...], value: str) -> str:
    """The file with one key's value replaced, and every other character as it was. The key
    must be there with a value on its own line; the result is read back before it is returned."""
    found = locate(text, path)
    if found is None:
        raise SettingsError(f'the settings file has no key {dotted(path)} '
                            '(a key that is only shown as a comment cannot be set)')
    number, key, (gap, old, tail) = found
    if not old:
        raise SettingsError(f'{dotted(path)} is a block of keys, not a value')
    if not value or value != value.strip() or '\n' in value or ' #' in value:
        raise SettingsError(f'not a value for one line: {value!r}')
    lines = text.splitlines(keepends=True)
    ending = lines[number][len(lines[number].rstrip('\r\n')):]
    lines[number] = f'{key.group("indent")}{key.group("key")}:{gap}{value}{tail}{ending}'
    changed = ''.join(lines)
    if value_of(changed, path) != value:
        raise SettingsError(f'{dotted(path)} did not read back as {value} after the edit')
    return changed


def is_empty_or_absent(text: str, path: tuple[str, ...]) -> bool:
    """True when a map setting has no entries: the key is missing, or it is written `{}`."""
    return value_of(text, path) in (None, '{}')
