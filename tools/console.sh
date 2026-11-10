#!/bin/zsh
# Pleos Connect 에뮬레이터의 Virtual Console(차량 신호 조작 화면)을 브라우저로 연다. 에뮬레이터 v2.1.0 이상.
# 기어·브레이크·조향각·주행거리는 왼쪽 Domain 목록의 Motion에서 바꾼다.
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" forward tcp:8088 tcp:8088
open http://127.0.0.1:8088/
