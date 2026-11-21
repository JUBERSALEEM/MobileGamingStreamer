package com.jubersaleem.mobilegamingstreamer

/**
 * GCM nonce length in bytes, per NIST SP 800-38D. Single source for the
 * vault framing below and the Keystore-backed vault itself; pinned by unit
 * tests so it can never drift silently.
 */
internal const val VAULT_GCM_IV_BYTES = 12

/**
 * Split vault payload: the GCM nonce plus the ciphertext. Compares by
 * content — use [contentEquals] on the byte arrays, never `==`.
 */
internal data class VaultPayload(val iv: ByteArray, val encrypted: ByteArray)

/**
 * Pack a vault payload: the IV followed by the ciphertext. Pure framing;
 * encryption itself stays in [StreamKeyVault] with the Android Keystore
 * key. Produces byte-identical output to the previous ByteBuffer packing.
 */
internal fun packVaultPayload(iv: ByteArray, encrypted: ByteArray): ByteArray = iv + encrypted

/**
 * Split a vault payload into its IV prefix and ciphertext. Returns null
 * when the payload cannot hold a full IV plus at least one ciphertext byte
 * (empty, undersized, or IV-only); never throws, so callers map null to
 * their existing invalid-payload path.
 */
internal fun unpackVaultPayload(payload: ByteArray): VaultPayload? {
    if (payload.size <= VAULT_GCM_IV_BYTES) return null
    return VaultPayload(
        iv = payload.copyOfRange(0, VAULT_GCM_IV_BYTES),
        encrypted = payload.copyOfRange(VAULT_GCM_IV_BYTES, payload.size)
    )
}
