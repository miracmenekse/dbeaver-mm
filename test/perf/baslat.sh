#!/bin/bash
# DBeaver'i Xvfb'de JFR ile baslatir; $1 = jfr adi
P=$(dirname "$(readlink -f "$0")")/../../product/community/target/products/org.jkiss.dbeaver.core.product/linux/gtk/x86_64/dbeaver
pgrep -x Xvfb >/dev/null || { Xvfb :99 -screen 0 1920x1080x24 >/dev/null 2>&1 & sleep 1; DISPLAY=:99 openbox >/dev/null 2>&1 & }
pkill -x dbeaver; pkill -x java; sleep 2
cd $P && DISPLAY=:99 GTK_THEME=Adwaita:light setsid nohup ./dbeaver -data ~/dbeaver-perf-ws -vmargs -Xmx4g \
  -XX:StartFlightRecording=name=perf,filename=$HOME/dbeaver-perf/${1:-run}.jfr,settings=profile,dumponexit=true \
  > ~/dbeaver-perf/${1:-run}.log 2>&1 &
