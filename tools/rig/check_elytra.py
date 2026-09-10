#!/usr/bin/env python3
"""Check native proxy observations around explicitly recorded, real-input phases."""
import argparse,json,math
from pathlib import Path

def check(rows,phases,subject):
 failures=[];evidence={}
 if len({p['id'] for p in phases})!=len(phases):raise ValueError('duplicate phase')
 required=['equipped','crouched','standing_recovered','falling','gliding','landed']
 byid={p['id']:p for p in phases}
 if set(byid)!=set(required):raise ValueError('all six real-input phases required')
 previous_end=-1
 for name in required:
  if byid[name]['start_ns']<=previous_end:raise ValueError('phases must be ordered and non-overlapping')
  previous_end=byid[name]['end_ns']
 for name in required:
  phase=byid[name]
  if not isinstance(phase['start_ns'],int) or not isinstance(phase['end_ns'],int) or phase['end_ns']<=phase['start_ns']:raise ValueError('invalid phase clock')
  selected=[r for r in rows if r['uuid']==subject and phase['start_ns']<=r['nano_time']<=phase['end_ns']]
  safe=[r for r in selected if r['native_absent'] is True and r['elytra'] is True and 32<r['distance']<512 and all(math.isfinite(r[k]) for k in ('x','y','z','distance'))]
  if len(safe)<3:failures.append(name+': fewer than three completed equipped proxy submissions beyond native tracking')
  expected_glide=name=='gliding';expected_crouch=name=='crouched'
  matching=[r for r in safe if r['fall_flying'] is expected_glide and r['crouching'] is expected_crouch]
  if len(matching)<3:failures.append(name+': pose did not persist across three completed submissions')
  if name=='falling' and matching and matching[-1]['y']>=matching[0]['y']-.1:failures.append('falling: no observed descending movement')
  if name=='gliding' and matching and sum((matching[-1][k]-matching[0][k])**2 for k in ('x','y','z'))<1:failures.append('gliding: no observed movement')
  evidence[name]={'count':len(matching),'first':matching[0] if matching else None,'last':matching[-1] if matching else None}
 return {'passed':not failures,'failures':failures,'phases':evidence,'scope':'completed actual LSS proxy submissions; native survival/equipment/FallFlying readback and visual review remain separately required'}

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('log',type=Path);p.add_argument('phases',type=Path);p.add_argument('--subject',required=True);a=p.parse_args()
 rows=[]
 for line in a.log.read_text(errors='replace').splitlines():
  if 'LSS_ELYTRA_SUBMIT ' in line:rows.append(json.loads(line.split('LSS_ELYTRA_SUBMIT ',1)[1]))
 result=check(rows,json.loads(a.phases.read_text()),a.subject);print(json.dumps(result,indent=2));raise SystemExit(0 if result['passed'] else 1)
