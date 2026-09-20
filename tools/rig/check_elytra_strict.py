"""Six phases must exist in actual native state and completed proxy observations."""
import math
from elytra_contract import PHASES

def check(submissions,native,phases,run_id,uuid):
 errors=[];summary={};last=-1
 if [p.get('id') for p in phases]!=list(PHASES):return {'passed':False,'errors':['exact ordered six phases required'],'phases':{}}
 for role,rows in [('observer',submissions),('target',native)]:
  for row in rows:
   if row.get('run_id')!=run_id or row.get('role')!=role or row.get('connection_id')!=run_id+'-'+role:errors.append('stale/foreign native session: '+role);break
 for p in phases:
  name=p['id'];start=p.get('start_ns');end=p.get('end_ns')
  if type(start)is not int or type(end)is not int or start<=last or end<=start:errors.append('invalid phase boundaries');continue
  last=end;ground=name not in ('falling','gliding');flying=name=='gliding';crouch=name=='crouched'
  def inside(r):return r.get('uuid')==uuid and start<=r.get('nano_time',-1)<=end
  def finite(r):return all(type(r.get(k))in (int,float) and math.isfinite(r[k]) for k in ('x','y','z'))
  states=[r for r in native if inside(r) and finite(r) and r.get('survival')is True and r.get('elytra')is True and r.get('on_ground')is ground and r.get('fall_flying')is flying and r.get('crouching')is crouch]
  draws=[r for r in submissions if inside(r) and finite(r) and r.get('native_absent')is True and r.get('elytra')is True and r.get('fall_flying')is flying and r.get('crouching')is crouch and type(r.get('distance'))in(int,float) and math.isfinite(r['distance']) and 32<r['distance']<512]
  if len({r.get('sequence') for r in states})<3:errors.append(name+': fewer than three actual native states')
  if len({r.get('sequence') for r in draws})<3:errors.append(name+': fewer than three actual proxy submissions absent from vanilla')
  if name in ('falling','gliding'):
   for kind,rows in [('native',states),('proxy',draws)]:
    if len(rows)<3:continue
    if name=='falling' and rows[-1]['y']>=rows[0]['y']-.1:errors.append('falling: '+kind+' did not descend')
    if name=='gliding' and sum((rows[-1][k]-rows[0][k])**2 for k in ('x','y','z'))<1:errors.append('gliding: '+kind+' did not move')
  summary[name]={'native_count':len(states),'submission_count':len(draws),'native_sequences':[r['sequence'] for r in states],'submission_sequences':[r['sequence'] for r in draws]}
 return dict(passed=not errors,errors=errors,phases=summary)
