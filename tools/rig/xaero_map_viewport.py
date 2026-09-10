"""Native Xaero 1.45 framebuffer projection and observed native button framing."""
import math

def rectangle(view):
 values=[view.get(k) for k in ('camera_x','camera_z','scale','width','height')]
 if any(type(v) not in (int,float) or not math.isfinite(v) for v in values):raise ValueError('invalid native viewport')
 cx,cz,scale,w,h=values
 if not 0<scale<=100 or not 0<w<=16384 or not 0<h<=16384:raise ValueError('native viewport outside bounds')
 # GuiMap mouseBlockPos=(framebufferMouse-framebufferSize/2)/scale+camera.
 return [w/2+(496-cx)*scale,h/2+(256-cz)*scale,w/2+(528-cx)*scale,h/2+(272-cz)*scale]

def framed(view):
 x0,z0,x1,z1=rectangle(view)
 return 48<=x0<x1<=view['width']-48 and 48<=z0<z1<=view['height']-48 and x1-x0>=24 and z1-z0>=12

def zoom_button(view):
 if view.get('zoom_active') is not True or view.get('zoom_visible') is not True:raise ValueError('native zoom button unavailable')
 x,y,w,h=view['zoom_out'];gw,gh=view['gui_width'],view['gui_height']
 if min(w,h,gw,gh)<=0 or not(0<=x<x+w<=gw and 0<=y<y+h<=gh):raise ValueError('native zoom button bounds invalid')
 return (round(view['window_x']+(x+w/2)*view['screen_width']/gw),round(view['window_y']+(y+h/2)*view['screen_height']/gh))


def capture_stable(rows,receipt):
 before=receipt.get('native_viewport',{});after=receipt.get('confirmed_viewport',{})
 start,finish=receipt.get('capture_started_ns'),receipt.get('capture_finished_ns')
 if type(start)is not int or type(finish)is not int or not before.get('time_ns',2**63)<start<=finish<after.get('time_ns',0):return False
 if before not in rows or after not in rows:return False
 samples=[r for r in rows if r.get('event')=='map_viewport' and before['time_ns']<=r['time_ns']<=after['time_ns']]
 if len([r for r in samples if r['time_ns']>finish])<2:return False
 frames=[r.get('viewport_frame')for r in samples]
 if any(type(v)is not int for v in frames)or frames!=list(range(frames[0],frames[-1]+1)):return False
 keys=('camera_x','camera_z','scale','width','height','window_x','window_y','screen_width','screen_height','gui_width','gui_height')
 return all(all(r.get(k)==before.get(k)for k in keys)and framed(r)for r in samples)
