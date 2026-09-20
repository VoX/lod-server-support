"""Bounded relative look on an already authenticated, identity-guarded private window."""
import ctypes,time

def look_down(driver):
 driver.focus() # Revalidates exact native process/window/display ownership.
 driver.test.XTestFakeRelativeMotionEvent.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_ulong]
 for step in range(4):
  driver.verify()
  if driver.test.XTestFakeRelativeMotionEvent(driver.display,0,100,0)==0:raise ValueError('private relative look input rejected')
  driver.x.XFlush(driver.display)
  time.sleep(.06)
  driver.verify()


def recapture_mouse(driver,observed,record,wait,now=time.monotonic_ns,settle=time.sleep):
 """Retry real capture clicks; caller retains its owner and scenario deadline."""
 attempts=0;after=0;retry_at=0
 def ready():
  nonlocal attempts,after,retry_at
  if attempts and observed(after):return True
  if now()<retry_at:return False
  if attempts==6:raise ValueError('native mouse capture absent after six verified clicks')
  start=now();driver.focus();settle(.2);driver.click(480,270)
  after=now();attempts+=1;record(start,after);retry_at=after+300_000_000
  return False
 wait(ready)
