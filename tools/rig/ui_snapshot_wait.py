"""Bounded observation of cached client status after a completed native UI action."""
import json,time

def observe_after_action(request_export, action_completed_ms, expected_reception, retain,
                         *, timeout=5.0, clock=time.monotonic):
    """Retain every actual export; only pre-action cached snapshots may be retried."""
    deadline=clock()+timeout
    attempt=0
    while clock()<deadline:
        raw=request_export(deadline)
        attempt+=1
        value=json.loads(raw)
        retain(attempt,raw)
        if clock()>=deadline:
            raise ValueError('post-action snapshot did not arrive within bounded deadline')
        captured=value.get('capturedAtMillis')
        if type(captured) is not int:
            raise ValueError('typed snapshot capture time required')
        if captured<=action_completed_ms:
            continue
        if value.get('receptionEnabled') is not expected_reception:
            raise ValueError('fresh post-action snapshot has wrong reception value')
        return raw,{'action_completed_ms':action_completed_ms,'captured_at_ms':captured,
                    'export_attempts':attempt,'stale_exports':attempt-1}
    raise ValueError('post-action snapshot did not arrive within bounded deadline')
