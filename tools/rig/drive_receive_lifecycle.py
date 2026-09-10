#!/usr/bin/env python3
"""Owned fixture controller; real menu SaveHook and native connection behavior stay in Java."""
import argparse,time
from pathlib import Path
from rig import read,inside,alive,write
from check_receive_run import make_proof

def run(root):
    manifest=read(root/'manifest.json')
    game=inside(root,'instances/lss-rig-client/minecraft');log=game/'logs/latest.log'
    stage=0;deadline=time.monotonic()+600
    def marker(relative):
        owner=read(root/'owner.json')
        if not alive(owner):raise ValueError('lifecycle supervisor no longer alive')
        path=inside(root,relative)
        with path.open('x'):pass
    while True:
        if time.monotonic()>=deadline:raise ValueError('lifecycle controller deadline')
        if log.is_file() and log.stat().st_size>32*1024*1024:raise ValueError('lifecycle log bound exceeded')
        text=log.read_text(errors='replace') if log.is_file() else ''
        if stage==0 and '[WI5-FIXTURE] READY ' in text:
            marker('instances/lss-rig-client/minecraft/lss-wi5-arm-off');stage=1
        if stage==1 and '[WI5-FIXTURE] AFTER_OFF ' in text:
            marker('instances/lss-rig-client/minecraft/lss-wi5-arm-on');stage=2
        if stage==2 and '[WI5-FIXTURE] PASS_SAME_WORLD_OFF_ON ' in text:
            marker('instances/lss-rig-client/minecraft/lss-wi5-arm-replacement');stage=3
        if stage==3 and '[WI5-REPLACEMENT] REAL_CALLBACK_HELD ' in text:
            marker('server/lss-rig-abrupt-close');stage=4
        failure=any(value in text for value in ('[WI5-FIXTURE] FAIL ','[WI5-FIXTURE] PRECONDITION_TIMEOUT ',
            '[WI5-FIXTURE] RESUME_TIMEOUT ','[WI5-REPLACEMENT] FAIL '))
        if failure or '[WI5-REPLACEMENT] PASS_REPLACEMENT ' in text:
            # Keep this owned launch alive until the supervisor observes proof and stops it.
            make_proof(root,manifest)
            while True:time.sleep(1)
        time.sleep(.05)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('run',type=Path);args=parser.parse_args()
    try:run(args.run)
    except Exception as error:
        write(args.run/'evidence/receive-driver-error.json',{'error':type(error).__name__})
        raise
