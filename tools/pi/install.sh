#!/bin/bash
# Installs the Dashwheel second screen on a Raspberry Pi (3B / 3B+ or newer)
# running Raspberry Pi OS Lite, already set up to join the phone's hotspot.
#
#   On a computer:   ./gradlew :display:installDist
#                    scp -r display/build/install/dashwheel-display tools/pi pi@dashwheel-display.local:
#   On the Pi:       sudo ./pi/install.sh ./dashwheel-display [--composite] [--acc-sense] [--overlay]
#
#   --composite   drive an RCA (composite) monitor from the 3.5 mm jack instead of HDMI
#   --acc-sense   shut down cleanly when the ignition goes off (GPIO3 through an optocoupler, see README.md)
#   --overlay     make the SD card read-only (overlay file system). Pair the head
#                 unit FIRST: the pairing is kept on the boot partition.
#   --image       prepare a ready-made card image instead of this Pi (build-image.sh,
#                 in a chroot): no pairing made, nothing started, Wi-Fi from wifi.txt.
#
# Run it again to update; settings and the pairing are kept.
set -euo pipefail

usage() { sed -n '2,17p' "$0"; exit 2; }

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
BUNDLE="${1:-}"; shift || true
[ -n "$BUNDLE" ] && [ -x "$BUNDLE/bin/dashwheel-display" ] || usage
COMPOSITE=0; ACC=0; OVERLAY=0; IMAGE=0
for arg in "$@"; do
  case "$arg" in
    --composite) COMPOSITE=1 ;;
    --acc-sense) ACC=1 ;;
    --overlay) OVERLAY=1 ;;
    --image) IMAGE=1 ;;
    *) usage ;;
  esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"

# Bookworm and later mount the boot partition at /boot/firmware.
BOOT=/boot/firmware
[ -f "$BOOT/config.txt" ] || BOOT=/boot
CONFIG_DIR="$BOOT/dashwheel"

# Nothing installed on a read-only card would survive the next start.
if grep -q 'overlayroot=tmpfs' /proc/cmdline; then
  echo "The card is read-only (--overlay). Turn that off first, then run this again:"
  echo "  sudo raspi-config nonint do_overlayfs 1 && sudo reboot"
  exit 1
fi
# Turning the overlay off can leave the boot partition read-only.
sed -i "s|\(\s$BOOT\s\+vfat\s\+defaults\),ro\b|\1|" /etc/fstab
[ "$IMAGE" -eq 1 ] || mount -o remount,rw "$BOOT"

echo "== packages"
apt-get update
apt-get install -y --no-install-recommends \
  default-jre-headless fonts-dejavu-core avahi-daemon \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad \
  plymouth plymouth-themes overlayroot iw

echo "== program"
systemctl stop dashwheel-display 2>/dev/null || true
rm -rf /opt/dashwheel-display
cp -r "$BUNDLE" /opt/dashwheel-display
chmod +x /opt/dashwheel-display/bin/dashwheel-display

echo "== settings in $CONFIG_DIR"
mkdir -p "$CONFIG_DIR"
[ -f "$CONFIG_DIR/display.conf" ] || cp "$HERE/display.conf.example" "$CONFIG_DIR/display.conf"
if [ "$COMPOSITE" -eq 1 ] && ! grep -q '^overscan=' "$CONFIG_DIR/display.conf"; then
  echo "overscan=5" >> "$CONFIG_DIR/display.conf"
fi
if [ "$IMAGE" -eq 1 ]; then
  # Where the driver writes the phone's hotspot, from any computer.
  [ -f "$CONFIG_DIR/wifi.txt" ] || cp "$HERE/wifi.txt.example" "$CONFIG_DIR/wifi.txt"
  echo dashwheel-display > /etc/hostname
  sed -i 's/^127\.0\.1\.1\s.*/127.0.1.1\tdashwheel-display/' /etc/hosts
  # No login on the screen at the first start: the image has no user, and
  # Raspberry Pi OS would ask for one there. A userconf.txt on the boot
  # partition still makes one (with an ssh file, for SSH).
  mkdir -p /etc/systemd/system/userconfig.service.d
  printf '[Unit]\nConditionPathExists=|%s/userconf.txt\nConditionPathExists=|%s/userconf\n' "$BOOT" "$BOOT" \
    > /etc/systemd/system/userconfig.service.d/dashwheel.conf
else
  # Made now, while the card is still writable.
  # Not inside an echo: a display program that cannot start must stop the install here.
  pairing=$(/opt/dashwheel-display/bin/dashwheel-display --config "$CONFIG_DIR" --print-pairing)
  echo "pairing: $pairing"
fi

# Where the map the display draws itself keeps its tiles (in memory on a read-only card: see README.md).
mkdir -p /var/cache/dashwheel-display/tiles

echo "== services"
sed "s|@CONFIG_DIR@|$CONFIG_DIR|" "$HERE/dashwheel-display.service" > /etc/systemd/system/dashwheel-display.service
install -m 644 "$HERE/dashwheel-display.avahi.service" /etc/avahi/services/dashwheel-display.service
systemctl daemon-reload
systemctl enable avahi-daemon dashwheel-display
systemctl disable getty@tty1 2>/dev/null || true

echo "== boot picture"
# A logo from power-up until the display service shows its first picture: the
# driver's own (splash.png beside display.conf, e.g. the car maker's) or Dashwheel's.
THEME=/usr/share/plymouth/themes/dashwheel
mkdir -p "$THEME"
install -m 644 "$HERE/splash/dashwheel.plymouth" "$HERE/splash/dashwheel.script" "$THEME/"
if [ -f "$CONFIG_DIR/splash.png" ]; then
  install -m 644 "$CONFIG_DIR/splash.png" "$THEME/logo.png"
else
  install -m 644 "$HERE/splash/logo.png" "$THEME/logo.png"
fi
if [ "$IMAGE" -eq 1 ]; then
  # In a chroot, -R would only remake the start-up image of the computer's own kernel version.
  plymouth-set-default-theme dashwheel
  update-initramfs -u -k all
else
  plymouth-set-default-theme -R dashwheel
fi
# The display service ends the boot logo itself, at its first picture: systemd's
# own "quit" at the end of the start would leave the screen black in between.
systemctl mask plymouth-quit.service plymouth-quit-wait.service

echo "== faster start"
# Nothing here is needed by a screen that only answers the head unit: first-boot
# setup, waiting for the network, disk housekeeping, Bluetooth, timers.
# (Only where cloud-init is installed: Bookworm's Lite image has none, and a missing folder stopped the install here.)
[ -d /etc/cloud ] && touch /etc/cloud/cloud-init.disabled
for unit in NetworkManager-wait-online.service e2scrub_reap.service rpi-eeprom-update.service \
  bluetooth.service udisks2.service keyboard-setup.service console-setup.service \
  apt-daily.timer apt-daily-upgrade.timer man-db.timer e2scrub_all.timer dpkg-db-backup.timer; do
  systemctl disable "$unit" 2>/dev/null || true
done
# The Wi-Fi through wpa_supplicant and systemd-networkd, which join in a few
# seconds: NetworkManager runs netplan at every start, reloads systemd several
# times and can then stall for 10 s or more. Its saved networks are carried over.
WPA=/etc/wpa_supplicant/wpa_supplicant-wlan0.conf
if [ ! -f "$WPA" ]; then
  country=$(sed -n 's/.*cfg80211.ieee80211_regdom=\([A-Z][A-Z]\).*/\1/p' "$BOOT/cmdline.txt")
  {
    echo "ctrl_interface=DIR=/run/wpa_supplicant GROUP=netdev"
    echo "update_config=1"
    [ -n "$country" ] && echo "country=$country"
    seen=" "
    for f in /etc/NetworkManager/system-connections/*.nmconnection \
      /run/NetworkManager/system-connections/netplan-wlan0-*.nmconnection; do
      [ -f "$f" ] || continue
      ssid=$(sed -n 's/^ssid=//p' "$f" | head -1)
      psk=$(sed -n 's/^psk=//p' "$f" | head -1)
      [ -n "$ssid" ] || continue
      case "$seen" in *" $ssid "*) continue ;; esac
      seen="$seen$ssid "
      printf 'network={\n\tssid="%s"\n' "$ssid"
      if [ -z "$psk" ]; then printf '\tkey_mgmt=NONE\n'
      elif printf '%s' "$psk" | grep -qE '^[0-9a-fA-F]{64}$'; then printf '\tpsk=%s\n' "$psk"
      # A quote or a backslash inside psk="…" breaks the whole file (add-wifi.sh guards the same): such a network is left out.
      elif printf '%s' "$psk" | grep -q '["\\]'; then echo "skipping Wi-Fi network $ssid: its password has a quote or a backslash" >&2; continue
      else printf '\tpsk="%s"\n' "$psk"; fi
      printf '}\n'
    done
  } > "$WPA.new"
  chmod 600 "$WPA.new"
  # A card image knows no network yet: wifi.txt brings them at its first start.
  if grep -q '^network=' "$WPA.new" || [ "$IMAGE" -eq 1 ]; then mv "$WPA.new" "$WPA"; else rm -f "$WPA.new"; fi
fi
if [ -f "$WPA" ]; then
  apt-get install -y --no-install-recommends systemd-resolved
  mkdir -p /etc/systemd/resolved.conf.d
  # Avahi answers .local names; resolved stays out of the way.
  printf '[Resolve]\nMulticastDNS=no\nLLMNR=no\n' > /etc/systemd/resolved.conf.d/dashwheel.conf
  printf '[Match]\nName=wlan0\n\n[Network]\nDHCP=ipv4\nIPv6AcceptRA=no\n' > /etc/systemd/network/30-wlan0.network
  # eth0 is for a cable straight to the head unit (a USB Ethernet adapter on it): the Pi hands
  # the head unit its address, with no router between them and no gateway, so the head unit
  # keeps its Wi-Fi for the internet. Never plug it into a home network: it would hand out
  # addresses there too.
  printf '[Match]\nName=eth0\n\n[Link]\nRequiredForOnline=no\n\n[Network]\nAddress=10.47.0.1/24\nDHCPServer=yes\nIPv6AcceptRA=no\n\n[DHCPServer]\nPoolOffset=10\nPoolSize=100\nEmitDNS=no\nEmitRouter=no\nEmitNTP=no\n' \
    > /etc/systemd/network/20-eth0.network
  systemctl disable NetworkManager.service NetworkManager-dispatcher.service wpa_supplicant.service 2>/dev/null || true
  # Wi-Fi power saving holds packets back (the DHCP answer, video): off.
  mkdir -p /etc/systemd/system/wpa_supplicant@wlan0.service.d
  printf '[Service]\nExecStartPre=-/usr/sbin/iw dev wlan0 set power_save off\n' \
    > /etc/systemd/system/wpa_supplicant@wlan0.service.d/dashwheel.conf
  systemctl enable systemd-networkd.service systemd-resolved.service wpa_supplicant@wlan0.service
  # Enabling networkd brings its wait-online along; nothing here waits for the network.
  systemctl disable systemd-networkd-wait-online.service 2>/dev/null || true
  # Joined at start-up on another network (the house's) before the hotspot showed: moved over once it does.
  install -m 755 "$HERE/prefer-wifi.sh" /usr/local/sbin/dashwheel-prefer-wifi
  install -m 644 "$HERE/dashwheel-prefer-wifi.service" "$HERE/dashwheel-prefer-wifi.timer" /etc/systemd/system/
  # Networks written in wifi.txt on the boot partition, from any computer: joined at the next start.
  install -m 755 "$HERE/add-wifi.sh" /usr/local/sbin/dashwheel-add-wifi
  install -m 755 "$HERE/wifi-from-card.sh" /usr/local/sbin/dashwheel-wifi-from-card
  install -m 644 "$HERE/dashwheel-wifi.service" /etc/systemd/system/
  systemctl daemon-reload
  systemctl enable dashwheel-prefer-wifi.timer dashwheel-wifi.service
fi
# Swap in memory only: the swap file is resized at every start, and written to the card.
[ -f /etc/rpi/swap.conf ] && sed -i 's/^#\?Mechanism=.*/Mechanism=zram/' /etc/rpi/swap.conf
# The service's Java starts from a class archive made now (the card may be read-only later).
JSA=/opt/dashwheel-display/app.jsa
rm -f "$JSA"
# Java 19 and later make it by themselves; Bookworm's Java 17 refuses that
# option and writes one as it exits instead. Without any, the display only
# starts a little slower: no reason to stop the install half-way.
JAVA_LOG=$(mktemp)
JAVA_OPTS="-XX:TieredStopAtLevel=1 -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$JSA" \
  /opt/dashwheel-display/bin/dashwheel-display --config "$CONFIG_DIR" --print-pairing >"$JAVA_LOG" 2>&1 ||
JAVA_OPTS="-XX:TieredStopAtLevel=1 -XX:ArchiveClassesAtExit=$JSA" \
  /opt/dashwheel-display/bin/dashwheel-display --config "$CONFIG_DIR" --print-pairing >"$JAVA_LOG" 2>&1 ||
  { echo "no class archive made: the display starts a little slower"; grep -v '://' "$JAVA_LOG" | tail -5; }
[ -f "$JSA" ] || echo "no class archive file"
rm -f "$JAVA_LOG"
# Each card from an image makes its own pairing at its first start, never a shared one.
[ "$IMAGE" -eq 0 ] || rm -f "$CONFIG_DIR/pairing.txt" "$CONFIG_DIR/paired"

echo "== boot settings"
CFG="$BOOT/config.txt"
# Our block is rewritten each time, between these markers.
sed -i '/^# >>> dashwheel/,/^# <<< dashwheel/d' "$CFG"
{
  echo "# >>> dashwheel (tools/pi/install.sh)"
  echo "[all]"
  echo "disable_splash=1"
  # Faster start: no wait, a quicker CPU while it boots, no Bluetooth, no camera probe.
  echo "boot_delay=0"
  echo "initial_turbo=60"
  echo "dtoverlay=disable-bt"
  echo "camera_auto_detect=0"
  if [ "$COMPOSITE" -eq 1 ]; then
    echo "enable_tvout=1"
  else
    # Keep HDMI on even when the monitor powers up after the Pi (ACC).
    echo "hdmi_force_hotplug=1"
  fi
  [ "$ACC" -eq 1 ] && echo "dtoverlay=gpio-shutdown,gpio_pin=3,active_low=0,gpio_pull=up"
  echo "# <<< dashwheel"
} >> "$CFG"
if [ "$COMPOSITE" -eq 1 ]; then
  sed -i 's/^dtoverlay=vc4-kms-v3d$/dtoverlay=vc4-kms-v3d,composite/' "$CFG"
else
  sed -i 's/^dtoverlay=vc4-kms-v3d,composite$/dtoverlay=vc4-kms-v3d/' "$CFG"
fi
CMD="$BOOT/cmdline.txt"
# No console blanking, no boot text and no cursor on the car's screen.
grep -q 'consoleblank=0' "$CMD" || sed -i '1 s/$/ consoleblank=0/' "$CMD"
grep -q 'logo.nologo' "$CMD" || sed -i '1 s/$/ logo.nologo/' "$CMD"
grep -q 'vt.global_cursor_default=0' "$CMD" || sed -i '1 s/$/ quiet loglevel=3 vt.global_cursor_default=0/' "$CMD"
grep -q ' splash' "$CMD" || sed -i '1 s/$/ splash plymouth.ignore-serial-consoles/' "$CMD"

if [ "$OVERLAY" -eq 1 ]; then
  echo "== read-only SD card"
  raspi-config nonint do_overlayfs 0
fi

[ "$IMAGE" -eq 0 ] || { echo "Card image prepared."; exit 0; }

# Back on screen now; the boot settings above take effect at the next start.
systemctl restart dashwheel-display

echo
echo "Done. Reboot, then scan the code on the screen with the Dashwheel phone app."
echo "The head unit and this Pi must both be on the phone's hotspot."
