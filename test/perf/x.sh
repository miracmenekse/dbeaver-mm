#!/bin/bash
# kisa yardimcilar: x.sh shot <ad> | key <tus> | type <metin> | click <x> <y> | dclick x y | rclick x y
export DISPLAY=:99
case $1 in
 shot) import -window root ~/dbeaver-perf/$2.png ;;
 key) shift; xdotool key --delay 80 "$@" ;;
 type) xdotool type --delay 30 "$2" ;;
 click) xdotool mousemove $2 $3 click 1 ;;
 dclick) xdotool mousemove $2 $3 click --repeat 2 1 ;;
 rclick) xdotool mousemove $2 $3 click 3 ;;
esac
