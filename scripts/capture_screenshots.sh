#!/usr/bin/env bash
# Captures genuine screenshots of the running app from a connected device/emulator via adb.
#
# Usage:
#   1. ./gradlew installDebug, open Daysemble, finish onboarding.
#   2. Settings › About › "Load sample data (debug)" (optional, for realistic content).
#   3. Run: scripts/capture_screenshots.sh
#   The script asks you to navigate to each screen, then captures it with `adb exec-out screencap`.
#   Images are saved unmodified to docs/screenshots/ (PNG, device resolution).
#
# Tip: enable a clean status bar first (Android demo mode):
#   adb shell settings put global sysui_demo_allowed 1
#   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
#   adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
#   adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=docs/screenshots
mkdir -p "$OUT"
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)"; exit 1; }
adb get-state >/dev/null || { echo "No device connected"; exit 1; }

shots=(
  "01-today-light:Today tab, light theme"
  "02-calendar-month:Calendar › Month"
  "03-calendar-week:Calendar › Week"
  "04-tasks:Tasks tab"
  "05-event-detail:An event's detail screen"
  "06-task-editor:A task with subtasks"
  "07-settings-appearance:Settings › Appearance (logo picker)"
  "08-today-dark:Today tab, dark theme"
)
for entry in "${shots[@]}"; do
  name="${entry%%:*}"; hint="${entry#*:}"
  read -r -p "Show: ${hint}. Press Enter to capture (s to skip)… " ans
  [[ "$ans" == "s" ]] && continue
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "  saved $OUT/$name.png"
done
echo "Done. Review the images; only commit screens that look right."
