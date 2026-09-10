#!/usr/bin/env python3
"""Real XTest input on an identity-verified private X display. Never host input."""
import argparse,ctypes,ctypes.util,json,os,time
from pathlib import Path
from rig import guard_window,guard_display,inside,read,alive

class XInput:
    def __init__(self,root,window,expected):
        self.root=Path(root);self.window=int(str(window),0);self.expected=expected
        env=guard_window(self.root,self.window,expected)
        os.environ['XAUTHORITY']=env['XAUTHORITY']
        self.x=ctypes.CDLL(ctypes.util.find_library('X11'));self.test=ctypes.CDLL(ctypes.util.find_library('Xtst'))
        ptr=ctypes.c_void_p;ul=ctypes.c_ulong;integer=ctypes.c_int
        self.x.XOpenDisplay.argtypes=[ctypes.c_char_p];self.x.XOpenDisplay.restype=ptr
        self.x.XCloseDisplay.argtypes=[ptr]
        self.x.XSetInputFocus.argtypes=[ptr,ul,integer,ul]
        self.x.XRaiseWindow.argtypes=[ptr,ul]
        self.x.XFlush.argtypes=[ptr]
        self.x.XStringToKeysym.argtypes=[ctypes.c_char_p];self.x.XStringToKeysym.restype=ul
        self.x.XKeysymToKeycode.argtypes=[ptr,ul];self.x.XKeysymToKeycode.restype=ctypes.c_ubyte
        self.test.XTestFakeKeyEvent.argtypes=[ptr,ctypes.c_uint,integer,ul]
        self.test.XTestFakeButtonEvent.argtypes=[ptr,ctypes.c_uint,integer,ul]
        self.test.XTestFakeMotionEvent.argtypes=[ptr,integer,integer,integer,ul]
        self.display=self.x.XOpenDisplay(env['DISPLAY'].encode())
        if not self.display:raise ValueError('cannot open authenticated private display')
        self.env=env
        self.display_identity=read(self.root/'display.json')
    def close(self):
        if self.display:self.x.XCloseDisplay(self.display);self.display=None
    def verify(self):
        env=guard_window(self.root,self.window,self.expected)
        if env['DISPLAY']!=self.env['DISPLAY'] or env['XAUTHORITY']!=self.env['XAUTHORITY']:
            raise ValueError('display identity changed during input')
    def focus(self):
        self.verify();self.x.XRaiseWindow(self.display,self.window);self.x.XSetInputFocus(self.display,self.window,2,0);self.x.XFlush(self.display)
    def verify_release(self):
        # A key can close its target window. Cleanup may release only on the
        # already opened, same owned display; it cannot focus or press anything.
        env=guard_display(self.root)
        if (env['DISPLAY']!=self.env['DISPLAY'] or env['XAUTHORITY']!=self.env['XAUTHORITY']
                or read(self.root/'display.json')!=self.display_identity):
            raise ValueError('private display identity changed before key release')
    def code(self,key):
        symbol=ord(key) if len(key)==1 else self.x.XStringToKeysym(key.encode('ascii'))
        code=self.x.XKeysymToKeycode(self.display,symbol)
        if not code:raise ValueError('unknown/unmapped key')
        return code
    def key(self,key,seconds=.06):
        if not 0<=seconds<=10:raise ValueError('key hold must be 0..10 seconds')
        code=self.code(key);self.focus();self.test.XTestFakeKeyEvent(self.display,code,1,0);self.x.XFlush(self.display)
        try:time.sleep(seconds)
        finally:
            self.verify_release();self.test.XTestFakeKeyEvent(self.display,code,0,0);self.x.XFlush(self.display)
    def text(self,text):
        validate_text(text)
        self.focus()
        shift=self.code('Shift_L')
        for character in text:
            self.verify();code=self.code(character);shifted=character.isupper() or character in '~!@#$%^&*()_+{}|:"<>?'
            if shifted:self.test.XTestFakeKeyEvent(self.display,shift,1,0)
            self.test.XTestFakeKeyEvent(self.display,code,1,0);self.test.XTestFakeKeyEvent(self.display,code,0,0)
            if shifted:self.test.XTestFakeKeyEvent(self.display,shift,0,0)
            self.x.XFlush(self.display);time.sleep(.012)
    def click(self,x,y,button=1):
        if not 0<=x<32768 or not 0<=y<32768 or button not in (1,2,3):raise ValueError('invalid private pointer action')
        self.focus();self.test.XTestFakeMotionEvent(self.display,-1,x,y,0)
        self.test.XTestFakeButtonEvent(self.display,button,1,0);self.test.XTestFakeButtonEvent(self.display,button,0,0);self.x.XFlush(self.display)
    def capture(self,name):
        self.verify();target=inside(self.root/'evidence',name)
        if target.suffix!='.png':raise ValueError('capture requires PNG under evidence')
        from PIL import ImageGrab
        frame=ImageGrab.grab(xdisplay=self.env['DISPLAY'])
        self.verify();frame.save(target)

def validate_text(text):
    if not isinstance(text,str) or len(text)>4096 or any(ord(c)<32 or ord(c)>126 for c in text):
        raise ValueError('private typing accepts at most 4096 printable ASCII characters')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('root',type=Path);p.add_argument('--window',required=True);p.add_argument('--identity',required=True,type=Path)
    sub=p.add_subparsers(dest='action',required=True)
    s=sub.add_parser('key');s.add_argument('key');s.add_argument('--seconds',type=float,default=.06)
    s=sub.add_parser('text');s.add_argument('text')
    s=sub.add_parser('click');s.add_argument('x',type=int);s.add_argument('y',type=int);s.add_argument('--button',type=int,default=1)
    s=sub.add_parser('capture');s.add_argument('name')
    a=p.parse_args();driver=None
    try:
        driver=XInput(a.root,a.window,read(a.identity))
        if a.action=='key':driver.key(a.key,a.seconds)
        elif a.action=='text':driver.text(a.text)
        elif a.action=='click':driver.click(a.x,a.y,a.button)
        else:driver.capture(a.name)
    except (ValueError,OSError) as e:p.exit(1,'private input: '+str(e)+'\n')
    finally:
        if driver:driver.close()
if __name__=='__main__':main()
