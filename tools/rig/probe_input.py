#!/usr/bin/env python3
"""Opt-in real XTest capability probe. Run through scripts/lib/harness-lock.sh."""
import sys,os,ctypes,tempfile,json
from pathlib import Path
from rig import private_display,identity,write,require_lock
from private_input import XInput
require_lock()
children=[]
with tempfile.TemporaryDirectory(prefix='lss-private-input-') as tmp:
 root=Path(tmp);(root/'evidence').mkdir();env=os.environ.copy()
 try:
  private_display(root,env,children);write(root/'owner.json',identity(os.getpid()));os.environ['XAUTHORITY']=env['XAUTHORITY']
  x=ctypes.CDLL('libX11.so.6');ptr=ctypes.c_void_p;ul=ctypes.c_ulong
  x.XOpenDisplay.argtypes=[ctypes.c_char_p];x.XOpenDisplay.restype=ptr
  x.XDefaultRootWindow.argtypes=[ptr];x.XDefaultRootWindow.restype=ul
  x.XCreateSimpleWindow.argtypes=[ptr,ul,ctypes.c_int,ctypes.c_int,ctypes.c_uint,ctypes.c_uint,ctypes.c_uint,ul,ul];x.XCreateSimpleWindow.restype=ul
  x.XInternAtom.argtypes=[ptr,ctypes.c_char_p,ctypes.c_int];x.XInternAtom.restype=ul
  x.XChangeProperty.argtypes=[ptr,ul,ul,ul,ctypes.c_int,ctypes.c_int,ptr,ctypes.c_int]
  x.XSelectInput.argtypes=[ptr,ul,ctypes.c_long];x.XMapWindow.argtypes=[ptr,ul];x.XSync.argtypes=[ptr,ctypes.c_int]
  x.XPending.argtypes=[ptr];x.XNextEvent.argtypes=[ptr,ptr];x.XDestroyWindow.argtypes=[ptr,ul];x.XCloseDisplay.argtypes=[ptr]
  display=x.XOpenDisplay(env['DISPLAY'].encode());window=x.XCreateSimpleWindow(display,x.XDefaultRootWindow(display),20,20,200,100,0,0,0xffffff)
  pid=ul(os.getpid());atom=x.XInternAtom(display,b'_NET_WM_PID',0);x.XChangeProperty(display,window,atom,6,32,0,ctypes.byref(pid),1)
  x.XSelectInput(display,window,3);x.XMapWindow(display,window);x.XSync(display,0)
  driver=XInput(root,hex(window),identity(os.getpid()))
  driver.key('w',.02);driver.text('A:/lss');driver.click(30,30);driver.capture('positive.png');driver.close();x.XSync(display,0)
  events=[]
  while x.XPending(display):
   event=(ctypes.c_long*24)();x.XNextEvent(display,ctypes.byref(event));events.append(ctypes.cast(event,ctypes.POINTER(ctypes.c_int))[0])
  assert events.count(2)>=7 and events.count(2)==events.count(3),events
  assert (root/'evidence/positive.png').stat().st_size>0
  # Reproduce a wizard that destroys itself in response to its activation key.
  driver=XInput(root,hex(window),identity(os.getpid()));keycode=driver.code('Return')
  from unittest.mock import patch
  def destroy_during_hold(seconds):
   x.XDestroyWindow(display,window);x.XSync(display,0)
  with patch('private_input.time.sleep',side_effect=destroy_during_hold):driver.key('Return',.02)
  driver.close();x.XSync(display,0)
  x.XQueryKeymap.argtypes=[ptr,ptr];keymap=(ctypes.c_ubyte*32)();x.XQueryKeymap(display,keymap)
  assert not keymap[keycode//8] & (1<<(keycode%8)), 'activation key remained held after window destruction'
  x.XCloseDisplay(display)
  print(json.dumps({'real_private_XTest_key_presses':events.count(2),'matching_releases':events.count(3),'destroyed_window_release':'passed','capture':'passed','display':'private','scope':'XTest capability, not Minecraft UI acceptance'}))
 finally:
  for child in children:
   child.terminate();child.wait(timeout=10)
