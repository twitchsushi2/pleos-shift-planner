#!/bin/zsh
# 에뮬레이터의 차량 신호(속도·기어)를 바꾼다. 켜져 있는 에뮬레이터 종류를 보고 알아서 고른다.
#   tools/vhal.sh speed 60     # km/h
#   tools/vhal.sh gear D       # P R N D
#   tools/vhal.sh drive        # 주차 → 출발 → 60km/h → 정차 → 주차 시나리오
#   tools/vhal.sh stop         # 뒤에서 유지하던 콘솔 세션 끝내기 (Pleos)
set -euo pipefail

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

if [[ $("$ADB" shell getprop ro.build.version.incremental | tr -d '\r') == *connect* ]]; then
  CONSOLE=http://127.0.0.1:8088
  "$ADB" forward tcp:8088 tcp:8088 >/dev/null
  # 콘솔은 브라우저 세션이 3초 안에 갱신되지 않으면 값을 503으로 거절한다. 브라우저 없이도 쓰도록 세션을 뒤에서 유지한다.
  # 멈추려면 tools/vhal.sh stop. 에뮬레이터가 꺼지면 스스로 끝난다.
  SESSION_PID=${TMPDIR:-/tmp}/pleos-console-session.pid
  session() { curl -sf -m 2 -X POST "$CONSOLE/browser/session?client=vhal-sh" >/dev/null; }
  if [[ ${1:-} == stop ]]; then
    [[ -f $SESSION_PID ]] && kill "$(cat "$SESSION_PID")" 2>/dev/null
    rm -f "$SESSION_PID"
    exit 0
  fi
  session || { echo "Virtual Console에 연결하지 못했습니다" >&2; exit 1; }
  if ! { [[ -f $SESSION_PID ]] && kill -0 "$(cat "$SESSION_PID")" 2>/dev/null; }; then
    nohup zsh -c "while curl -sf -m 2 -X POST '$CONSOLE/browser/session?client=vhal-sh' >/dev/null; do sleep 1; done" >/dev/null 2>&1 &
    echo $! > "$SESSION_PID"
    disown
    sleep 1
  fi
  speed() { curl -sf -X PUT "$CONSOLE/vehicle/controls?speed=$1&accelerator=0&brake=0" >/dev/null; }
  set_gear() {
    local name=(P Park R Reverse N Neutral D Drive)
    curl -sf -X PUT "$CONSOLE/vehicle/gear?gear=${name[${name[(i)$1]}+1]}" >/dev/null
  }
else
  SPEED=0x11600207   # PERF_VEHICLE_SPEED (m/s, float)
  GEAR=0x11400400    # GEAR_SELECTION (int)
  VHAL=android.hardware.automotive.vehicle.IVehicle/default
  "$ADB" root >/dev/null 2>&1 || true
  "$ADB" wait-for-device
  set_prop() { "$ADB" shell dumpsys $VHAL --set "$@" >/dev/null; }
  speed() { set_prop $SPEED -f $(printf '%.2f' $(( $1 / 3.6 ))); }
  set_gear() {
    local value=(P 4 R 2 N 1 D 8)
    set_prop $GEAR -i ${value[${value[(i)$1]}+1]}
  }
fi

gear() {
  [[ $1 == (P|R|N|D) ]] || { echo "기어는 P R N D 중 하나" >&2; exit 1; }
  set_gear "$1"
}

case ${1:-} in
  speed) speed "$2" ;;
  gear) gear "$2" ;;
  drive)
    gear P; speed 0; sleep 2
    gear D
    for v in {0..60..5}; do speed $v; sleep 0.5; done
    sleep 5
    for v in {60..0..10}; do speed $v; sleep 0.5; done
    gear P
    ;;
  *) sed -n 2,6p "$0"; exit 1 ;;
esac
