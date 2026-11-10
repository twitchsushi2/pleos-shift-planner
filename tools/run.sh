#!/bin/zsh
# 빌드해서 켜져 있는 에뮬레이터에 설치하고 실행한다. 속도 권한도 미리 준다.
#   tools/run.sh              # 차량 OS면 차량 API, 아니면 목 데이터
#   tools/run.sh mock         # Automotive 에뮬레이터에서도 시뮬레이션 주행으로
set -euo pipefail
cd "${0:A:h}/.."

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB="$SDK/platform-tools/adb"
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}

./gradlew -q :app:installDebug -Pvehicle="${1:-auto}"
for permission in android.car.permission.CAR_SPEED android.permission.POST_NOTIFICATIONS; do
  "$ADB" shell pm grant com.pleoscontest.app $permission 2>/dev/null || true
done
"$ADB" shell am start -n com.pleoscontest.app/.MainActivity
