package dev.vox.lssfixture.xaeromap;
/** Early group updates may precede the target; completed native scans may not. */
final class NativeTargetEligibility {
 private NativeTargetEligibility(){}
 static boolean capture(boolean targetPresent,boolean nativeScanCompleted){
  if(targetPresent)return true;
  if(nativeScanCompleted)throw new IllegalStateException("completed native scan lost its target tile");
  return false;
 }
}
