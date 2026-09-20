"""Recompute lifecycle evidence from real run-owned client/server observations."""
import json,re
from pathlib import Path
from check_receive_lifecycle import check

TOKENS=frozenset('sameNativeWorld=true sameXaeroWorld=true sameConnection=true freshManager=true nativeRebuildsDrained=true preparedTile=true originOpen=true pendingRebuildsPositive=true realCallbackHeld=true pendingRebuilds=0 nativeWorldCleared=true generationChanged=true newNativeWorld=true newConnection=true newManager=true sameDimension=true realOldCallbackReturned=true oldReceiptClosed=true oldTileAbsent=true nativeWorldRetired=true realTransport=true disconnectPacket=false enabled=true sessionConfig=true version=20'.split())

def observations(client,server):
    output=[]
    for marker,text in [('WI5-FIXTURE',client),('WI5-REPLACEMENT',client),('LSS-ABRUPT-CLOSE',server)]:
        for line in text.splitlines():
            match=re.search(r'\['+re.escape(marker)+r'\] ([A-Z_]+)(?: |$)',line)
            if match:
                output.append('['+marker+'] '+match.group(1)+' '+' '.join(word for word in line[match.end():].split() if word in TOKENS))
    return output

def inspect(run,manifest):
    from rig import read,inside,regular,digest
    run=Path(run);runtime=read(run/'runtime.json');scenario=read(run/'scenario.json')
    if digest(runtime)!=manifest['runtime_hash'] or digest(scenario)!=manifest['scenario_hash']:raise ValueError('changed lifecycle inputs')
    if scenario.get('checker')!='receive-lifecycle':raise ValueError('lifecycle checker not selected')
    client=regular(inside(run,'instances/lss-rig-client/minecraft/logs/latest.log'))
    server=regular(inside(run,'server.private.log'))
    if max(client.stat().st_size,server.stat().st_size)>32*1024*1024:raise ValueError('lifecycle log bound exceeded')
    rows=observations(client.read_text(errors='replace'),server.read_text(errors='replace'))
    result=check('\n'.join(rows))
    handshake=any('[WI5-FIXTURE] PRECONDITION ' in row and all(value in row.split() for value in ('enabled=true','sessionConfig=true','version=20')) for row in rows)
    if not handshake:result['errors'].append('actual initial v20 negotiation missing')
    result['passed']=not result['errors']
    result['assertions']={key:result['passed'] for key in result['assertions']}
    return dict(result,run_id=manifest['run_id'],run_hash=manifest['run_hash'],handshake=handshake,observations=rows,observations_sha256=digest(rows))

def make_proof(run,manifest):
    from rig import write,sha
    run=Path(run);report=inspect(run,manifest)
    destination=run/'evidence/receive-lifecycle.json';write(destination,report)
    proof={key:manifest[key] for key in ('run_id','run_hash','profile_hash','scenario_hash')}
    proof.update(ready=report['handshake'],handshake=report['handshake'],test_count=5 if report['passed'] else 0,
        assertions=report['assertions'],failures=report['errors'],receive_report=report,
        evidence={'receive-lifecycle.json':sha(destination)})
    write(run/'proof.json',proof)
    return proof

def check_report(proof,manifest,scenario,root):
    try:
        actual=inspect(root,manifest)
        if proof.get('receive_report')!=actual:return ['lifecycle raw report changed or missing']
        return actual['errors']
    except (ValueError,OSError,KeyError,TypeError) as error:return ['lifecycle report invalid: '+str(error)]
