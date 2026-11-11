#!/bin/sh
# Atualiza o pacote de CA raiz da Mozilla incluído no APK (usado só em Android < 10, ver LegacyTls.kt).
# Fonte: https://curl.se/ca/cacert.pem — confere o SHA-256 publicado pelo curl antes de substituir.
# Uso (na raiz do repositório): sh ferramentas/atualizar-ca.sh
set -eu
DEST="app/src/main/res/raw/cacert.pem"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/cacert.pem" https://curl.se/ca/cacert.pem
curl -fsSL -o "$TMP/cacert.pem.sha256" https://curl.se/ca/cacert.pem.sha256
(cd "$TMP" && sha256sum -c cacert.pem.sha256) >/dev/null || { echo "SHA-256 não confere — nada alterado" >&2; exit 1; }
N=$(grep -c "BEGIN CERTIFICATE" "$TMP/cacert.pem")
[ "$N" -ge 100 ] || { echo "Só $N certificados — nada alterado" >&2; exit 1; }
if cmp -s "$TMP/cacert.pem" "$DEST"; then echo "Já está atualizado ($N CA)"; exit 0; fi
cp "$TMP/cacert.pem" "$DEST"
echo "Atualizado: $N CA, $(grep -m1 'as of:' "$DEST" | sed 's/.*as of: //')"
