#!/bin/bash
# Builds the ready-made SD card of the Dashwheel second screen: Raspberry Pi OS
# Lite (64-bit) with install.sh already run in it, to write with Raspberry Pi
# Imager ("Use custom"). Each card makes its own pairing at its first start.
#
#   ./gradlew :display:installDist
#   sudo tools/pi/build-image.sh display/build/install/dashwheel-display dashwheel-second-screen.img.xz [base.img.xz]
#
# Needs an arm64 Linux (the GitHub arm runner does it): the installer runs in a
# chroot of the image. Without a base image, the latest Raspberry Pi OS Lite is downloaded.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)"; exit 1; }
[ "$#" -ge 2 ] || { sed -n '2,10p' "$0"; exit 2; }
[ "$(uname -m)" = aarch64 ] || { echo "needs an arm64 Linux: the installer runs inside the image"; exit 1; }
BUNDLE=$(realpath "$1")
OUT=$(realpath -m "$2")
BASE=${3:-}
[ -x "$BUNDLE/bin/dashwheel-display" ] || { echo "no program in $BUNDLE (./gradlew :display:installDist)"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"

WORK=$(mktemp -d)
MNT=$WORK/root
LOOP=""
cleanup() {
  set +e
  mountpoint -q "$MNT" && umount -R "$MNT"
  [ -n "$LOOP" ] && losetup -d "$LOOP"
  rm -rf "$WORK"
}
trap cleanup EXIT

if [ -z "$BASE" ]; then
  BASE=$WORK/base.img.xz
  # The Foundation publishes a .sha256 beside every image: a download cut short or
  # altered on the way is caught here, not on a hundred cards.
  url=${BASE_URL:-$(curl -fsIL -o /dev/null -w '%{url_effective}' https://downloads.raspberrypi.com/raspios_lite_arm64_latest)}
  echo "== downloading Raspberry Pi OS Lite ($(basename "$url"))"
  curl -fL --retry 3 -o "$BASE" "$url"
  curl -fL --retry 3 -o "$BASE.sha256" "$url.sha256"
  echo "== checking the download"
  (cd "$WORK" && sed "s|[^ ]*\.img\.xz|base.img.xz|" base.img.xz.sha256 | sha256sum -c -)
fi
IMG=$WORK/card.img
xz -dc "$BASE" > "$IMG"

echo "== room for Java, GStreamer and the boot logo"
# The card grows to its full size at its first start anyway (the image's own "resize").
truncate -s +1G "$IMG"
echo ', +' | sfdisk -q -N 2 "$IMG"
LOOP=$(losetup -fP --show "$IMG")
e2fsck -fy "${LOOP}p2" || [ $? -le 1 ]
resize2fs "${LOOP}p2"

mkdir -p "$MNT"
mount "${LOOP}p2" "$MNT"
mount "${LOOP}p1" "$MNT/boot/firmware"
for m in dev dev/pts proc sys; do mount --bind "/$m" "$MNT/$m"; done

# The image starts "for the first time" (growing to the card, new SSH keys) as
# long as its machine id is unset: the packages installed here must not set it.
cp -a "$MNT/etc/machine-id" "$WORK/machine-id"
DBUS_ID=0; [ -e "$MNT/var/lib/dbus/machine-id" ] && DBUS_ID=1
# Names resolved like on this computer while installing.
mv "$MNT/etc/resolv.conf" "$WORK/resolv.conf"
cat /run/systemd/resolve/resolv.conf 2>/dev/null > "$MNT/etc/resolv.conf" || cp -L /etc/resolv.conf "$MNT/etc/resolv.conf"
# Nothing installed starts here.
printf '#!/bin/sh\nexit 101\n' > "$MNT/usr/sbin/policy-rc.d"
chmod +x "$MNT/usr/sbin/policy-rc.d"

echo "== installing"
mkdir -p "$MNT/root/dashwheel"
cp -r "$BUNDLE" "$MNT/root/dashwheel/dashwheel-display"
cp -r "$HERE" "$MNT/root/dashwheel/pi"
chroot "$MNT" bash /root/dashwheel/pi/install.sh /root/dashwheel/dashwheel-display --image

echo "== cleaning up"
chroot "$MNT" apt-get clean
rm -rf "$MNT/root/dashwheel" "$MNT/var/lib/apt/lists/"* "$MNT/usr/sbin/policy-rc.d"
find "$MNT/var/log" -type f -exec truncate -s 0 {} +
cp -a "$WORK/machine-id" "$MNT/etc/machine-id"
[ "$DBUS_ID" -eq 1 ] || rm -f "$MNT/var/lib/dbus/machine-id"
rm -f "$MNT/etc/resolv.conf"
if [ -e "$MNT/usr/lib/systemd/systemd-resolved" ]; then
  # The installer moved the Pi to systemd-resolved.
  ln -s ../run/systemd/resolve/stub-resolv.conf "$MNT/etc/resolv.conf"
else
  mv "$WORK/resolv.conf" "$MNT/etc/resolv.conf"
fi
# Unused blocks back to zeros: the image compresses much smaller.
fstrim "$MNT" || true
fstrim "$MNT/boot/firmware" || true
ls -l "$MNT/boot/firmware/"initramfs*
umount -R "$MNT"
e2fsck -fy "${LOOP}p2" || [ $? -le 1 ]
losetup -d "$LOOP"
LOOP=""

echo "== compressing"
xz -T0 -6 -c "$IMG" > "$OUT.part"
mv "$OUT.part" "$OUT"
ls -lh "$OUT"
