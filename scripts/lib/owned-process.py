#!/usr/bin/env python3
"""Own a harness launch and its descendants, even if the launcher exits first.

Linux subreaping keeps orphaned single-use Gradle/game children attached here.
Only this supervisor inherits the harness lock; Popen closes it in the launched
process. Signals clean up the owned session/children before this process exits.
"""
import ctypes
import os
import signal
import subprocess
import sys
import time
from pathlib import Path


def children():
    try:
        return [int(p) for p in Path(f"/proc/{os.getpid()}/task/{os.getpid()}/children").read_text().split()]
    except FileNotFoundError:
        return []


# The runner's own bounded stop protocol: 20 s stdin stop, 5 s per owned
# process, post-cleanup checkers and the journal's terminal write.
RUNNER_STOP_GRACE_SECONDS = 60


def group_members(pgid):
    """PIDs currently in process group ``pgid`` (field 5 of /proc/<pid>/stat)."""
    members = []
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / 'stat').read_text().rsplit(')', 1)[1].split()
        except (OSError, IndexError):
            continue
        if len(fields) > 2 and fields[2] == str(pgid):
            members.append(int(entry.name))
    return members


def signal_group(pgid, sig):
    """Signal the owned group only while it still has members.

    A group id is only the reaped leader's PID once its members are gone;
    signalling it by number alone could reach a foreign process after PID reuse.
    """
    if not group_members(pgid):
        return False
    try:
        os.killpg(pgid, sig)
    except ProcessLookupError:
        return False
    return True


def request_runner_stop(rig_root, proc, stopped, deadline):
    """Hand a stop signal to the rig runner through its own designed path.

    Touches ``<run>/stop`` (consumed by the owning runner), signals only the
    direct child, and waits for the runner's bounded shutdown protocol so the
    launch journal reaches its terminal write and the cleanup receipt can be
    issued. A second stop signal, or the grace deadline, ends the wait.
    """
    try:
        (rig_root / 'stop').touch()
    except OSError:
        pass
    try:
        os.kill(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    signals_seen = len(stopped)
    while proc.poll() is None and len(stopped) == signals_seen and time.monotonic() < deadline:
        time.sleep(0.05)
    return proc.poll()


def main():
    args = sys.argv[1:]
    inherit = args[:1] == ["--inherit-lock"]
    if inherit:
        args = args[1:]
    rig_root = None
    if args[:1] == ['--rig-run-root']:
        rig_root, args = Path(args[1]).resolve(), args[2:]
    directory = None
    if args[:1] == ['--cwd']:
        directory, args = args[1], args[2:]
    if len(args) < 2 or args[0] != "--":
        return 2
    # Fail closed: without subreaping we cannot certify that launcher exit means
    # all scratch-world users exited. This tooling already depends on Linux /proc.
    if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
        raise OSError(ctypes.get_errno(), "cannot enable child subreaping")
    stopped = []
    def stop(signum, _frame):
        stopped.append(signum)
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, stop)
    # A killed controller cannot run its EXIT trap. Request a kernel signal on
    # parent death too, and close the registration race using its explicit PID.
    expected_parent = int(os.environ.get('LSS_HARNESS_OWNER_PID', os.getppid()))
    if ctypes.CDLL(None, use_errno=True).prctl(1, signal.SIGTERM, 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
        raise OSError(ctypes.get_errno(), 'cannot enable parent-death cleanup')
    if os.getppid() != expected_parent:
        return 143
    env = os.environ.copy()
    env.pop('LSS_HARNESS_OWNER_PID', None)
    descriptor = env.pop("LSS_HARNESS_LOCK_FD", None)
    passed = ()
    if inherit and descriptor is not None:
        passed = (int(descriptor),)
        env["LSS_HARNESS_LOCK_FD"] = descriptor
    # Historical benchmark wrappers may invoke an older gradlew without the
    # explicit flag. The Gradle system property disables daemon reuse there too.
    env["GRADLE_OPTS"] = env.get("GRADLE_OPTS", "") + " -Dorg.gradle.daemon=false"
    proc = subprocess.Popen(args[1:], start_new_session=True, close_fds=True,
                            pass_fds=passed, env=env, cwd=directory)
    controller = None
    if rig_root is not None:
        sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools/rig'))
        from rig import identity
        controller=identity(proc.pid)
    def finish(code):
        if rig_root is not None:
            try:
                from launch_journal import supervisor_complete
                supervisor_complete(rig_root,controller)
            except (ValueError,OSError,KeyError,TypeError) as error:
                print('[harness] Rig cleanup receipt unavailable: '+str(error),file=sys.stderr)
                return code if code else 1
        return code
    while proc.poll() is None and not stopped:
        time.sleep(0.05)
    result = proc.poll()
    # A single-use Gradle daemon can finish shutdown shortly after its client
    # returns. Keep ownership while it exits naturally; do not call that tail a
    # failed game run. A child that remains live beyond the grace is incomplete.
    grace = time.monotonic() + 5
    while result is not None and children() and not stopped and time.monotonic() < grace:
        try:
            while os.waitpid(-1, os.WNOHANG)[0]:
                pass
        except ChildProcessError:
            pass
        time.sleep(0.05)
    orphaned = bool(children()) if result is not None else False
    if stopped and result is None and rig_root is not None:
        # A signal-initiated stop must not outrun the runner's graceful protocol:
        # the journal's terminal write and the cleanup receipt are what make an
        # interrupted attempt collectable (recorded failed, slot reclaimable).
        result = request_runner_stop(rig_root, proc, stopped, time.monotonic() + RUNNER_STOP_GRACE_SECONDS)
        orphaned = bool(children()) if result is not None else False
    if stopped or orphaned:
        # The original process group covers grandchildren; adopted children cover
        # a child that started another session. Reap until none remain, retaining
        # the lock even after TERM escalation instead of releasing a live world.
        started = time.monotonic()
        sent = {}
        group_signal = None
        while True:
            sig = signal.SIGKILL if time.monotonic() - started >= 3 else signal.SIGTERM
            if group_signal != sig:
                signal_group(proc.pid, sig)
                group_signal = sig
            for pid in children():
                if sent.get(pid) != sig:
                    try:
                        os.kill(pid, sig)
                    except ProcessLookupError:
                        pass
                    sent[pid] = sig
            proc.poll()
            while True:
                try:
                    pid, _ = os.waitpid(-1, os.WNOHANG)
                    if pid == 0:
                        break
                except ChildProcessError:
                    break
            if not children():
                break
            time.sleep(0.05)
        if stopped:
            return finish(128 + stopped[0])
        print("[harness] Launcher exited while owned children remained; cleaned up incomplete run", file=sys.stderr)
        return finish(result if result and result > 0 else 1)
    return finish(result if result >= 0 else 128 - result)


if __name__ == "__main__":
    sys.exit(main())
