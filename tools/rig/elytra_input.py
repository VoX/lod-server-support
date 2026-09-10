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
