package dev.vox.lssfixture.concurrent;
/** Deliberately equal native-looking payload values remain distinct receipt identities. */
public final class IdentityCapturesTest {
    private record Payload(int x,int z,long stamp){}
    public static void main(String[] args){
        var captures=new IdentityCaptures<String>(2);var a=new Payload(8,8,123);var b=new Payload(8,8,123);
        captures.put(a,"first");captures.put(b,"second");
        if(!"second".equals(captures.take(b))||!"first".equals(captures.take(a))||captures.take(a)!=null)throw new AssertionError("equal-value identity collision/reuse");
        captures.put(a,"again");try{captures.put(a,"duplicate");throw new AssertionError();}catch(IllegalStateException expected){}
        captures.put(b,"second");try{captures.put(new Object(),"overflow");throw new AssertionError();}catch(IllegalStateException expected){}
        captures.clear();if(captures.take(a)!=null)throw new AssertionError("retired capture survived");
        System.out.println("IdentityCaptures: equal stamps, distinct identities, exact-once take, duplicate and bounded overflow controls passed");
    }
}
