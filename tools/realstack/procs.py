"""Processes, displays, ports and the shared harness lock.

Ownership rule: a run only ever signals processes it started. They are recognised by the process
name (java, timeout or Xvfb, never a shell) together with a marker that is unique to the run in
the command line: the `-Dlssrig.id=<run id>` property, or the run's own framebuffer directory for
Xvfb. Nothing is ever selected by port, by pid file alone, or by a pattern match over all
processes.
"""
from __future__ import annotations

import ctypes
import fcntl
import os
import signal
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

OWNED_NAMES = ('java', 'timeout', 'Xvfb')
X_SOCKETS = Path('/tmp/.X11-unix')
PR_SET_PDEATHSIG = 1


@dataclass
class ProcessInfo:
    """What /proc says about one process."""
    pid: int
    name: str
    command_line: str


def lock_path() -> Path:
    """The lock every local harness (Gradle gates, soaks, rigs) takes before heavy work."""
    return Path(f'/tmp/lss-harness-{os.getuid()}') / 'port-25565.lock'


class HarnessLock:
    """The shared harness lock, held for the whole of a run. The lock file is never removed
    (the lock belongs to its inode) and the descriptor is not inherited by child processes."""

    def __init__(self) -> None:
        self.descriptor: int | None = None

    def acquire(self, wait_seconds: float, poll: float = 2.0) -> float:
        """Wait up to `wait_seconds` for the lock. Returns how long the wait took; raises
        TimeoutError when another harness still holds it at the deadline."""
        path = lock_path()
        path.parent.mkdir(mode=0o700, exist_ok=True)
        descriptor = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
        started = time.monotonic()
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.descriptor = descriptor
                return time.monotonic() - started
            except BlockingIOError:
                if time.monotonic() - started >= wait_seconds:
                    os.close(descriptor)
                    raise TimeoutError(f'the harness lock {path} was still held after '
                                       f'{wait_seconds:.0f} s') from None
                time.sleep(poll)

    def release(self) -> None:
        """Give the lock back."""
        if self.descriptor is not None:
            fcntl.flock(self.descriptor, fcntl.LOCK_UN)
            os.close(self.descriptor)
            self.descriptor = None

    def __enter__(self) -> 'HarnessLock':
        return self

    def __exit__(self, *_exc) -> None:
        self.release()


def lock_is_free() -> bool:
    """True when nobody holds the harness lock right now (takes and releases it at once)."""
    lock = HarnessLock()
    try:
        lock.acquire(0)
    except TimeoutError:
        return False
    lock.release()
    return True


def list_processes(proc_root: Path = Path('/proc')) -> list[ProcessInfo]:
    """Every process this user can read, with its name and command line."""
    found = []
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            name = (entry / 'comm').read_text().strip()
            command_line = (entry / 'cmdline').read_bytes().replace(b'\0', b' ').decode(
                'utf-8', 'replace').strip()
        except OSError:
            continue
        found.append(ProcessInfo(int(entry.name), name, command_line))
    return found


def owned(processes: list[ProcessInfo], markers: list[str]) -> list[ProcessInfo]:
    """The processes that belong to a run: an allowed process name and one of the run's unique
    markers in the command line. An empty marker never matches."""
    wanted = [marker for marker in markers if marker]
    return [process for process in processes
            if process.name in OWNED_NAMES
            and any(marker in process.command_line for marker in wanted)]


def stop_owned(markers: list[str], grace: float = 20.0) -> dict:
    """Stop this run's processes: SIGTERM, a bounded wait, then SIGKILL for what is left.
    Returns which pids were asked, which had to be killed and which survived."""
    report = {'terminated': [], 'killed': [], 'left': []}
    for process in owned(list_processes(), markers):
        if send_signal(process.pid, signal.SIGTERM):
            report['terminated'].append(f'{process.pid} {process.name}')
    deadline = time.monotonic() + grace
    while owned(list_processes(), markers) and time.monotonic() < deadline:
        time.sleep(0.5)
    for process in owned(list_processes(), markers):
        if send_signal(process.pid, signal.SIGKILL):
            report['killed'].append(f'{process.pid} {process.name}')
    time.sleep(0.5)
    report['left'] = [f'{process.pid} {process.name}'
                      for process in owned(list_processes(), markers)]
    return report


def send_signal(pid: int, number: int) -> bool:
    """Signal one process; False when it is already gone."""
    try:
        os.kill(pid, number)
        return True
    except (ProcessLookupError, PermissionError):
        return False


def port_in_use(port: int) -> bool:
    """True when something listens on the port on any local address."""
    for family, address in ((socket.AF_INET, '0.0.0.0'), (socket.AF_INET6, '::')):
        try:
            with socket.socket(family, socket.SOCK_STREAM) as probe:
                probe.bind((address, port))
        except OSError:
            return True
    return False


def displays_in_use() -> set[int]:
    """X display numbers that already exist on this machine."""
    taken = set()
    if X_SOCKETS.is_dir():
        for entry in X_SOCKETS.iterdir():
            if entry.name.startswith('X') and entry.name[1:].isdigit():
                taken.add(int(entry.name[1:]))
    for entry in Path('/tmp').glob('.X*-lock'):
        number = entry.name[2:-5]
        if number.isdigit():
            taken.add(int(number))
    return taken


def die_with_parent() -> None:
    """Runs in the child before exec: ask the kernel to send SIGTERM when the runner dies, so a
    killed runner cannot leave its server, client or display behind."""
    ctypes.CDLL(None, use_errno=True).prctl(PR_SET_PDEATHSIG, signal.SIGTERM)


def spawn(command: list[str], cwd: Path, environment: dict, output: Path,
          with_console: bool = False) -> subprocess.Popen:
    """Start a process in its own session with its output in a file. `with_console` keeps a pipe
    to its standard input (the server console); otherwise it reads nothing. The command should
    be wrapped in `timeout` (launch.bounded), which passes the parent-death signal on."""
    log = open(output, 'ab')
    try:
        return subprocess.Popen(command, cwd=cwd, env=environment, stdout=log,
                                stderr=subprocess.STDOUT, start_new_session=True,
                                preexec_fn=die_with_parent,
                                stdin=subprocess.PIPE if with_console else subprocess.DEVNULL)
    finally:
        log.close()


def display_ready(display: int, environment: dict) -> bool:
    """True when the display answers a client."""
    result = subprocess.run(['xdotool', 'getdisplaygeometry'], capture_output=True, timeout=10,
                            env={**environment, 'DISPLAY': f':{display}'}, check=False)
    return result.returncode == 0


def window_titles(display: int, environment: dict) -> list[str]:
    """Titles of the visible windows on a private display."""
    if display == 0:
        raise ValueError('refusing display :0')
    env = {**environment, 'DISPLAY': f':{display}'}
    try:
        listing = subprocess.run(['xdotool', 'search', '--onlyvisible', '--class', ''],
                                 capture_output=True, text=True, timeout=15, env=env, check=False)
        titles = []
        for window in listing.stdout.split():
            name = subprocess.run(['xdotool', 'getwindowname', window], capture_output=True,
                                  text=True, timeout=10, env=env, check=False)
            if name.returncode == 0 and name.stdout.strip():
                titles.append(name.stdout.strip())
        return sorted(set(titles))
    except (OSError, subprocess.TimeoutExpired):
        return []


def wait_until(predicate, deadline: float, interval: float = 0.5) -> bool:
    """Poll `predicate` until it is true or the monotonic `deadline` passes."""
    while True:
        if predicate():
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(interval)
