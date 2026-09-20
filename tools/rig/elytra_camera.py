"""Read-only angular framing from the actual observer pose and submitted proxy position."""
import math

def framed(cameras,draws,after_ns,run_id,uuid,until_ns=float('inf')):
 def select(rows,role):return [r for r in rows if after_ns<=r.get('nano_time',-1)<=until_ns and r.get('run_id')==run_id and r.get('role')==role and r.get('connection_id')==run_id+'-'+role]
 cameras=select(cameras,'observer');draws=[r for r in select(draws,'observer') if r.get('uuid')==uuid and r.get('native_absent')is True and r.get('fall_flying')is True]
 if not cameras or not draws:return False
 c,d=cameras[-1],draws[-1]
 try:
  dx=d['x']-c['x'];dy=d['y']+.4-c['eye_y'];dz=d['z']-c['z'];yaw=math.degrees(math.atan2(-dx,dz));pitch=-math.degrees(math.atan2(dy,math.hypot(dx,dz)));delta=(yaw-c['yaw']+180)%360-180
  return all(math.isfinite(x) for x in (delta,pitch,c['pitch'])) and abs(delta)<8 and abs(pitch-c['pitch'])<8
 except (KeyError,TypeError):return False
