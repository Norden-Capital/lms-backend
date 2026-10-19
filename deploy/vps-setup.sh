#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu VPS for the LMS backend.
#
# Run as root on the server:
#     bash vps-setup.sh "<ci public key>"
#
# Safe to re-run: every step checks before it changes anything.
#
# What it deliberately does NOT do is turn off password or root login. Doing that
# before a key login has been proven to work is how people lock themselves out of
# a server, so it is a separate, manual step (see the end of this file).

set -euo pipefail

CI_PUBKEY="${1:-}"
APP_DIR=/opt/myapp          # Must match the target in .github/workflows/ci-cd.yml.
DEPLOY_USER=deploy

[ "$(id -u)" -eq 0 ] || { echo "Run as root."; exit 1; }

echo "==> System update"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get upgrade -y
apt-get install -y ca-certificates curl gnupg ufw fail2ban unattended-upgrades

# Security patches install themselves. A pre-launch box nobody logs into for
# weeks is exactly the one that falls behind.
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "==> Docker Engine + Compose plugin (official repository, not the distro's)"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

# Without rotation, container logs grow until the disk is full and Postgres stops.
if [ ! -f /etc/docker/daemon.json ]; then
  cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  systemctl restart docker
fi

echo "==> Deploy user for CI"
# CI logs in as this user, not root. It is in the docker group, which is
# root-equivalent in practice, but it keeps CI off the root account and lets the
# key be revoked without touching how you log in yourself.
id "$DEPLOY_USER" >/dev/null 2>&1 || useradd -m -s /bin/bash "$DEPLOY_USER"
usermod -aG docker "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" /home/$DEPLOY_USER/.ssh
touch /home/$DEPLOY_USER/.ssh/authorized_keys
if [ -n "$CI_PUBKEY" ] && ! grep -qxF "$CI_PUBKEY" /home/$DEPLOY_USER/.ssh/authorized_keys; then
  echo "$CI_PUBKEY" >> /home/$DEPLOY_USER/.ssh/authorized_keys
fi
chown "$DEPLOY_USER:$DEPLOY_USER" /home/$DEPLOY_USER/.ssh/authorized_keys
chmod 600 /home/$DEPLOY_USER/.ssh/authorized_keys

install -d -m 750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"

echo "==> Swap"
# Spring Boot + Postgres on a small VPS can hit the OOM killer during a deploy,
# when the old and new containers briefly overlap.
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "==> Firewall"
# NOTE: Docker writes its own iptables rules, so a port published in
# docker-compose.yml ("8080:8080") is reachable even though ufw does not list it.
# ufw protects the host; docker-compose.yml decides what the containers expose.
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> fail2ban (SSH brute-force protection)"
systemctl enable --now fail2ban

echo
echo "Done."
docker --version
docker compose version
echo
echo "Next, by hand, ONLY after confirming key login works in a second terminal:"
echo "  sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config"
echo "  sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config"
echo "  systemctl restart ssh"
