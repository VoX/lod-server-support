package dev.vox.lssfixture.xaeromap;
/** Pure Java control, executed explicitly after fixture testClasses compilation. */
public final class NativeTargetEligibilityTest {
 public static void main(String[] args){
  if(NativeTargetEligibility.capture(false,false))throw new AssertionError("early incomplete group must produce no target observation");
  if(!NativeTargetEligibility.capture(true,false))throw new AssertionError("present bridge target is eligible");
  if(!NativeTargetEligibility.capture(true,true))throw new AssertionError("present native target is eligible");
  try{NativeTargetEligibility.capture(false,true);throw new AssertionError("missing target after native scan must fail");}
  catch(IllegalStateException expected){}
  System.out.println("Native target eligibility: four cases passed");
 }
}
