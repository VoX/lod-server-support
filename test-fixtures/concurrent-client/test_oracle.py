#!/usr/bin/env python3
"""Run the independent incremental parser controls with an explicit JDK/Gson jar."""
import argparse
from pathlib import Path
import subprocess
import tempfile
p=argparse.ArgumentParser();p.add_argument('--java-home',required=True);p.add_argument('--gson-jar',required=True);a=p.parse_args()
root=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='lss-oracle-tests-') as temp:
    subprocess.run([str(Path(a.java_home)/'bin/javac'),'--release','25','-cp',a.gson_jar,'-d',temp,
                    str(root/'src/dev/vox/lssfixture/concurrent/OracleJournal.java'),
                    str(root/'tests/dev/vox/lssfixture/concurrent/OracleJournalSelfTest.java')],check=True)
    subprocess.run([str(Path(a.java_home)/'bin/java'),'-ea','-cp',temp+':'+a.gson_jar,
                    'dev.vox.lssfixture.concurrent.OracleJournalSelfTest'],check=True)
