#!/usr/bin/env bash
# Install the Night license server on a fresh Ubuntu/Debian VPS.
# Usage: sudo bash install.sh [domain]
set -euo pipefail

DOMAIN="${1:-}"
APP_DIR=/opt/night-license
ENV_FILE=/etc/night-license.env
SERVICE=/etc/systemd/system/night-license.service

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi

if [ -z "$DOMAIN" ]; then
  IP="$(curl -fsS --max-time 10 https://api.ipify.org || hostname -I | awk '{print $1}')"
  DOMAIN="night.${IP}.nip.io"
fi
echo "domain: $DOMAIN"

# --- node 20+ -------------------------------------------------------------
NEED_NODE=1
if command -v node >/dev/null 2>&1; then
  MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
  [ "$MAJOR" -ge 20 ] && NEED_NODE=0
fi
if [ "$NEED_NODE" -eq 1 ]; then
  echo "installing Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
node --version

# --- app ------------------------------------------------------------------
mkdir -p "$APP_DIR"
install -m 0644 "$(dirname "$0")/server.mjs" "$APP_DIR/server.mjs"

if [ ! -f "$ENV_FILE" ]; then
  ADMIN_TOKEN="$(openssl rand -hex 32)"
  MEMORY_SECRET="$(openssl rand -hex 32)"
  cat >"$ENV_FILE" <<EOF
NIGHT_PORT=8080
NIGHT_HOST=127.0.0.1
NIGHT_DATA=/var/lib/night-license/night.json
NIGHT_ADMIN_TOKEN=$ADMIN_TOKEN
NIGHT_MEMORY_SECRET=$MEMORY_SECRET
EOF
  chmod 0600 "$ENV_FILE"
  echo "generated admin token -> $ENV_FILE"
else
  echo "keeping existing $ENV_FILE"
fi

# --- systemd --------------------------------------------------------------
cat >"$SERVICE" <<'EOF'
[Unit]
Description=Night License Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
DynamicUser=yes
StateDirectory=night-license
EnvironmentFile=/etc/night-license.env
ExecStart=/usr/bin/node /opt/night-license/server.mjs
Restart=always
RestartSec=3
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/night-license

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now night-license
sleep 2
systemctl is-active --quiet night-license || { journalctl -u night-license -n 40 --no-pager; exit 1; }
curl -fsS http://127.0.0.1:8080/api/health && echo

# --- nginx + tls ----------------------------------------------------------
if ! command -v nginx >/dev/null 2>&1; then
  apt-get install -y nginx
fi

cat >"/etc/nginx/sites-available/night-license" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    client_max_body_size 1m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 30s;
    }

    location = /api/health {
        proxy_pass http://127.0.0.1:8080/api/health;
    }
}
EOF

ln -sf /etc/nginx/sites-available/night-license /etc/nginx/sites-enabled/night-license
nginx -t
systemctl reload nginx

if ! command -v certbot >/dev/null 2>&1; then
  apt-get install -y certbot python3-certbot-nginx
fi

certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos \
  --register-unsafely-without-email --redirect || {
    echo "certbot failed; HTTP only for now" >&2
  }

echo
echo "health:  https://$DOMAIN/api/health"
echo "token:   grep NIGHT_ADMIN_TOKEN $ENV_FILE"
echo "service: systemctl status night-license"
