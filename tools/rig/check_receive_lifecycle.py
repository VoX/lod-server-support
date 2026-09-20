"""Validate ordered maintained WI5 observations; logs are bound by the owning run."""
import re

SAME_WORLD = ('PRECONDITION', 'AFTER_OFF', 'OFF_NATIVE_REBUILDS_DRAINED',
              'BEFORE_ON', 'AFTER_ON', 'PASS_SAME_WORLD_OFF_ON')
REPLACEMENT = ('ARMED', 'REAL_CALLBACK_HELD', 'NATIVE_RETIRE_PREMISE',
               'NATIVE_RETIRED', 'REPLACEMENT_READY', 'PASS_REPLACEMENT')

def check(text):
    errors=[]
    def events(marker):
        rows=[]
        for line in text.splitlines():
            match=re.search(r'\['+re.escape(marker)+r'\] ([A-Z_]+)(?: |$)',line)
            if match:
                event=match.group(1)
                if 'FAIL' in event or 'TIMEOUT' in event:errors.append(marker+' failed: '+event)
                rows.append((event,line))
        return rows
    def ordered(rows,names,marker):
        positions=[]
        for name in names:
            found=[i for i,(event,_) in enumerate(rows) if event==name]
            if len(found)!=1: errors.append(marker+' requires exactly one '+name)
            else:positions.append(found[0])
        if positions!=sorted(positions): errors.append(marker+' events out of order')
    same=events('WI5-FIXTURE'); replacement=events('WI5-REPLACEMENT')
    transport=events('LSS-ABRUPT-CLOSE')
    ordered(transport,('CLOSED',),'actual transport')
    ordered(same,SAME_WORLD,'same-world');ordered(replacement,REPLACEMENT,'replacement')
    required={
        'CLOSED':('realTransport=true','disconnectPacket=false'),
        'PASS_SAME_WORLD_OFF_ON':('sameNativeWorld=true','sameXaeroWorld=true','sameConnection=true','freshManager=true','nativeRebuildsDrained=true'),
        'REAL_CALLBACK_HELD':('preparedTile=true','originOpen=true'),
        'NATIVE_RETIRE_PREMISE':('pendingRebuildsPositive=true','realCallbackHeld=true'),
        'NATIVE_RETIRED':('pendingRebuilds=0','nativeWorldCleared=true','generationChanged=true'),
        'REPLACEMENT_READY':('newNativeWorld=true','newConnection=true','newManager=true','sameDimension=true'),
        'PASS_REPLACEMENT':('realOldCallbackReturned=true','oldReceiptClosed=true','oldTileAbsent=true','nativeWorldRetired=true')}
    for event,line in same+replacement+transport:
        for value in required.get(event,()):
            if value not in line.split():errors.append(event+' missing '+value)
    return {'passed':not errors,'errors':errors,'assertions':{
        name:not errors for name in ('same_world_off_retires_acceptance','off_preserves_committed_rebuilds',
            'on_resumes_fresh_bodies','replacement_rejects_old_callbacks','replacement_retires_native_world')}}
