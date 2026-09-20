"""Require real post-teleport elevated observations before measuring a falling interval."""
def elevated(native,draw,after_ns,run_id):
 for rows,role in ((native,'target'),(draw,'observer')):
  current=[r for r in rows if r.get('nano_time',0)>=after_ns and r.get('run_id')==run_id and r.get('role')==role and r.get('connection_id')==run_id+'-'+role]
  if len(current)<3:return False
  latest=current[-3:]
  if not all(r.get('elytra')is True and r.get('fall_flying')is False and r.get('y',0)>140 for r in latest):return False
  if role=='target' and not all(r.get('survival')is True and r.get('on_ground')is False for r in latest):return False
  if role=='observer' and not all(r.get('native_absent')is True for r in latest):return False
 return True
