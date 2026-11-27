#!/usr/bin/env bash
# Restricted root-owned atomic updater for moji-cloud-server.
# Install path: /usr/local/sbin/moji-atomic-update (0755 root:root)
#
# Security boundaries:
# - Accepts NO user-supplied file path arguments (prevents path traversal).
# - Reads ONLY /home/moji-deploy/moji-cloud-server.new (must be a regular non-symlink
#   single-link ELF64 binary owned by moji-deploy).
# - NEVER reads, writes, or modifies /opt/moji-cloud/data, /opt/moji-cloud/.env,
#   or any reverse-proxy / unrelated services.
# - Stages inside /opt/moji-cloud/ for atomic same-filesystem rename(2), restarts
#   moji-cloud.service, verifies local /api/v1/health, and auto-rolls back on failure.

set -euo pipefail
umask 022

if [[ $# -ne 0 ]]; then
  echo "error: arguments are not permitted" >&2
  exit 2
fi

readonly DEPLOY_USER="moji-deploy"
readonly STAGED_UPLOAD="/home/${DEPLOY_USER}/moji-cloud-server.new"
readonly INSTALL_DIR="/opt/moji-cloud"
readonly TARGET_BIN="${INSTALL_DIR}/moji-cloud-server"
readonly PREV_BIN="${INSTALL_DIR}/moji-cloud-server.prev"
readonly TMP_BIN="${INSTALL_DIR}/.moji-cloud-server.tmp.$$"
readonly SERVICE_NAME="moji-cloud"
readonly HEALTH_URL="http://127.0.0.1:8787/api/v1/health"
readonly MIN_BYTES=524288
readonly MAX_BYTES=67108864

cleanup() {
  rm -f "${TMP_BIN}"
}
trap cleanup EXIT

if [[ -L "${STAGED_UPLOAD}" || ! -f "${STAGED_UPLOAD}" ]]; then
  echo "error: staged binary missing or not a regular file: ${STAGED_UPLOAD}" >&2
  exit 1
fi

owner_and_links="$(stat -c '%U:%h' "${STAGED_UPLOAD}")"
if [[ "${owner_and_links}" != "${DEPLOY_USER}:1" ]]; then
  echo "error: invalid staged file ownership or hardlink count (${owner_and_links})" >&2
  exit 1
fi

file_size="$(stat -c '%s' "${STAGED_UPLOAD}")"
if (( file_size < MIN_BYTES || file_size > MAX_BYTES )); then
  echo "error: staged binary size out of bounds (${file_size} bytes)" >&2
  exit 1
fi

elf_magic="$(head -c 4 "${STAGED_UPLOAD}" | od -An -tx1 | tr -d ' \n')"
if [[ "${elf_magic}" != "7f454c46" ]]; then
  echo "error: staged file is not a valid ELF binary" >&2
  exit 1
fi

install -o root -g root -m 0755 "${STAGED_UPLOAD}" "${TMP_BIN}"
rm -f "${STAGED_UPLOAD}"
sync "${TMP_BIN}"

new_sha256="$(sha256sum "${TMP_BIN}" | awk '{print substr($1, 1, 12)}')"

had_prev=0
if [[ -f "${TARGET_BIN}" && ! -L "${TARGET_BIN}" ]]; then
  cp -pf "${TARGET_BIN}" "${PREV_BIN}"
  had_prev=1
fi

mv -f "${TMP_BIN}" "${TARGET_BIN}"
systemctl restart "${SERVICE_NAME}"

healthy=0
for _ in $(seq 1 15); do
  if systemctl is-active --quiet "${SERVICE_NAME}" && curl -fsS --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 1
done

if [[ "${healthy}" -ne 1 ]]; then
  echo "error: ${SERVICE_NAME} failed local health check after update; rolling back" >&2
  if [[ "${had_prev}" -eq 1 && -f "${PREV_BIN}" ]]; then
    mv -f "${PREV_BIN}" "${TARGET_BIN}"
    systemctl restart "${SERVICE_NAME}" || true
  fi
  exit 1
fi

echo "ok: ${SERVICE_NAME} updated atomically (sha256=${new_sha256}) and passed local health check"
