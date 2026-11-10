#!/bin/zsh
# 음성 인식 시험용 PCM(16kHz, 16bit, mono)을 macOS say로 만든다.
set -euo pipefail
cd "${0:A:h}/.."
OUT=app/src/debug/assets/stt
mkdir -p $OUT
TMP=$(mktemp -d)

i=0
for line in "응" "네" "좋아" "수락" "미뤄 줘" "다음 휴게소에서 바꿀래" "바꿔 줘" "그대로 갈게" "아니" "오늘 날씨 어때"; do
  i=$((i + 1))
  name=$(printf '%02d' $i)
  say -v Yuna -o $TMP/$name.aiff "$line"
  afconvert -f WAVE -d LEI16@16000 -c 1 $TMP/$name.aiff $TMP/$name.wav
  # WAVE 머리글을 떼고 앞뒤에 0.5초 무음을 붙인다.
  python3 -I - $TMP/$name.wav $OUT/$name.pcm <<'PY'
import sys, wave
with wave.open(sys.argv[1]) as w:
    pcm = w.readframes(w.getnframes())
silence = b"\0" * 16000
open(sys.argv[2], "wb").write(silence + pcm + silence)
PY
  echo "$name $line"
done
