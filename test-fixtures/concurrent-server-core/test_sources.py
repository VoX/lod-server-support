#!/usr/bin/env python3
import argparse,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True);p.add_argument('--gson-jar',required=True);a=p.parse_args();root=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='lss-source-controls-') as temp:
 subprocess.run([str(Path(a.java_home)/'bin/javac'),'--release','25','-cp',a.gson_jar,'-d',temp,*map(str,(root/'src').rglob('*.java')),*map(str,(root/'test').rglob('*.java'))],check=True)
 for name in ('MeasuredScheduleTest','SourcePreparationTest','OwnerRevisionsTest','SourceReconnectTest','OwnerObservationTest','SourceEditHandoffTest','NativeSaveSetupTest'):
  subprocess.run([str(Path(a.java_home)/'bin/java'),'-ea','-cp',temp+':'+a.gson_jar,'dev.vox.lssfixture.concurrent.'+name],check=True)
