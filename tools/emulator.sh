#!/bin/zsh
# 에뮬레이터(AVD)를 처음 한 번 만들고, 띄운다.
#   tools/emulator.sh            # Pleos Connect v2.1.0 (기본)
#   tools/emulator.sh aaos       # 구글 표준 Android Automotive 14 (Pleos와 비교할 때)
#   tools/emulator.sh pleos --wipe   # 데이터 지우고 새로 부팅
set -euo pipefail

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}

case ${1:-pleos} in
  pleos)
    AVD=Pleos_Connect_v2_1
    IMAGE="system-images;android-34;pleos_car_emulator;arm64-v8a"
    DEVICE=Pleos_Connect_v2_0   # 이미지에 들어 있는 기기 프로필(devices.xml) 이름
    ;;
  aaos)
    AVD=Pleos_AAOS_34
    IMAGE="system-images;android-34-ext9;android-automotive;arm64-v8a"
    DEVICE=automotive_1080p_landscape
    ;;
  *) sed -n 2,5p "$0"; exit 1 ;;
esac

if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
  echo "AVD $AVD 만드는 중"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d "$DEVICE" --force
  # Pleos 문서의 권장 설정: RAM 4GB, 콜드 부팅
  sed -i '' -e 's/^hw.ramSize=.*/hw.ramSize=4096/' "$HOME/.android/avd/$AVD.avd/config.ini"
  grep -q '^hw.ramSize=' "$HOME/.android/avd/$AVD.avd/config.ini" || echo 'hw.ramSize=4096' >> "$HOME/.android/avd/$AVD.avd/config.ini"
fi

args=(-avd "$AVD" -no-snapshot -netdelay none -netspeed full)
[[ ${2:-} == --wipe ]] && args+=(-wipe-data)

"$SDK/emulator/emulator" "${args[@]}" >/dev/null 2>&1 &
echo "부팅 기다리는 중..."
"$SDK/platform-tools/adb" wait-for-device
until [[ $("$SDK/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do sleep 2; done
echo "부팅 완료"
