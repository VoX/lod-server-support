"""Pure native-console fixture coordinator; the owned rig adapter executes intents."""
class Controller:
    phases=('fresh','seeded','unloaded','empty-store','first-handshake','first-body','persisted','retired','second-handshake','second-body','clean')
    def __init__(self,origin_ns,timeout_ns=360_000_000_000):self.origin=origin_ns;self.timeout=timeout_ns;self.index=0
    def advance(self,phase,now,verified):
        if now<self.origin or now-self.origin>self.timeout:raise ValueError('native smoke deadline expired')
        if phase!=self.phases[self.index] or verified is not True:raise ValueError('native smoke premise absent or out of order')
        self.index+=1
        return ('complete' if self.index==len(self.phases) else self.phases[self.index])
