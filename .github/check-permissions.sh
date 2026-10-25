#!/usr/bin/env bash
# Every permission in the APK must be on this list; anything else (internet above all) fails.
set -euo pipefail
APK="$1"
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
"$AAPT" dump permissions "$APK" > permissions.txt
cat permissions.txt
grep -q '^package:' permissions.txt || { echo "::error::Could not read permissions"; exit 1; }
ALLOWED=" android.permission.ACTIVITY_RECOGNITION android.permission.POST_NOTIFICATIONS android.permission.RECEIVE_BOOT_COMPLETED \
android.permission.health.READ_STEPS android.permission.health.READ_SLEEP android.permission.health.READ_MENSTRUATION \
android.permission.health.WRITE_NUTRITION android.permission.health.WRITE_HYDRATION android.permission.health.WRITE_WEIGHT \
ua.tarilka.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION "
for p in $(grep -oE "name='[^']+'" permissions.txt | sed "s/name='//;s/'//"); do
  case "$ALLOWED" in
    *" $p "*) ;;
    *) echo "::error::Unexpected permission: $p"; exit 1 ;;
  esac
done
echo "Permissions OK"
