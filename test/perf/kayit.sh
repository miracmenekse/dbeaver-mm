#!/bin/bash
# kayit.sh start <ad> | stop <ad>  -> ~/dbeaver-perf/<ad>.jfr + UI thread summary (olc.py)
# JFR "profile" settings with jdk.SocketRead/Write threshold 0, so every DB round trip is recorded.
J=$(dirname "$(readlink -f "$(command -v java)")")
OUT=~/dbeaver-perf; mkdir -p $OUT
PID=$(pgrep -x java | head -1)
if [ ! -f $OUT/perf.jfc ]; then
  python3 - "$J/../lib/jfr/profile.jfc" $OUT/perf.jfc <<'PY'
import re, sys
s = open(sys.argv[1]).read()
for ev in ('jdk.SocketRead', 'jdk.SocketWrite'):
    s = re.sub(r'(<event name="%s">.*?<setting name="threshold"[^>]*>)[^<]*' % ev, r'\g<1>0 ms', s, flags=re.S)
open(sys.argv[2], 'w').write(s)
PY
fi
if [ "$1" = start ]; then $J/jcmd $PID JFR.start name=$2 settings=$OUT/perf.jfc >/dev/null
else $J/jcmd $PID JFR.stop name=$2 filename=$OUT/$2.jfr >/dev/null; python3 "$(dirname "$0")/olc.py" $OUT/$2.jfr | tee $OUT/$2.txt; fi
