"""One-second RSS observations tied to process creation identity, never newest Java."""
import time
from pathlib import Path
from rig import alive

def rss(owner):
    if not alive(owner):return {'missing':True,'reason':'process_identity_not_live'}
    try:
        status=Path(f"/proc/{owner['pid']}/status").read_text()
        value=next(int(line.split()[1])*1024 for line in status.splitlines() if line.startswith('VmRSS:'))
    except (OSError,StopIteration,ValueError):return {'missing':True,'reason':'rss_unavailable'}
    if not alive(owner):return {'missing':True,'reason':'process_identity_changed'}
    return {'missing':False,'rss_bytes':value}

class Sampler:
    def __init__(self,subjects,origin_ns=None):
        self.subjects=subjects
        self.next_ns=time.monotonic_ns() if origin_ns is None else origin_ns
    def sample(self,now_ns=None):
        now=time.monotonic_ns() if now_ns is None else now_ns
        rows=[]
        while self.next_ns<=now:
            late=now-self.next_ns>=1_000_000_000
            for subject,owner in self.subjects.items():
                value={'missing':True,'reason':'missed_scheduled_observation'} if late else rss(owner)
                rows.append(dict(value,subject=subject,process_identity=owner,scheduled_ns=self.next_ns,observed_ns=now,units='bytes'))
            self.next_ns+=1_000_000_000
        return rows
