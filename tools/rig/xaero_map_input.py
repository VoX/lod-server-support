"""Native Xaero zoom is a held button consumed by rendered frames, not a click."""
import time

def zoom_out(driver,x,y,observed_lower,check_time):
 if type(x)is not int or type(y)is not int or not 0<=x<32768 or not 0<=y<32768:raise ValueError('invalid private map pointer action')
 driver.focus()
 driver.test.XTestFakeMotionEvent(driver.display,-1,x,y,0)
 if not driver.test.XTestFakeButtonEvent(driver.display,1,1,0):raise ValueError('private map button press rejected')
 try:
  driver.x.XFlush(driver.display)
  deadline=time.monotonic()+1
  while time.monotonic()<deadline:
   check_time();driver.verify()
   if observed_lower():return
   time.sleep(.02)
  raise ValueError('native map zoom did not change while held')
 finally:
  driver.verify_release()
  if not driver.test.XTestFakeButtonEvent(driver.display,1,0,0):raise ValueError('private map button release rejected')
  driver.x.XFlush(driver.display)
