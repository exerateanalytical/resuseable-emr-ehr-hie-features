#!/usr/bin/env bash
# =============================================================================
# Opes HealthCore Platform — Release SBOM, Checksum & Artifact Signing Script
# =============================================================================
# Generates SHA-256 release manifests for packaged JAR artifacts and optionally
# signs artifacts with cosign/gpg in release CI pipelines.

set -euo pipefail

OUT_DIR="${1:-./target/release-attestation}"
mkdir -p "${OUT_DIR}"

echo "[release] Scanning built HealthCore JAR artifacts..."
JAR_LIST=()
while IFS= read -r -d '' jar; do
  JAR_LIST+=("${jar}")
done < <(find . -path '*/target/*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print0)

if [[ "${#JAR_LIST[@]}" -eq 0 ]]; then
  echo "[release] No built JARs found under */target/. Run 'mvn -B clean package' first." >&2
  exit 1
fi

CHECKSUMS="${OUT_DIR}/SHA256SUMS.txt"
: > "${CHECKSUMS}"
for jar in "${JAR_LIST[@]}"; do
  sha256sum "${jar}" >> "${CHECKSUMS}"
done

if [[ -n "${RELEASE_GPG_KEY_ID:-}" ]] && command -v gpg >/dev/null 2>&1; then
  gpg --batch --yes --armor --detach-sign --local-user "${RELEASE_GPG_KEY_ID}" \
    --output "${CHECKSUMS}.asc" "${CHECKSUMS}"
  echo "[release] Signed ${CHECKSUMS} -> ${CHECKSUMS}.asc"
fi

echo "[release] Generated release checksum attestation for ${#JAR_LIST[@]} artifacts at ${CHECKSUMS}"
