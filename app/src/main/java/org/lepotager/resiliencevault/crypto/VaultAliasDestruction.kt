package org.lepotager.resiliencevault.crypto

/** Inventory is the app's actual Keystore namespace, not only a possibly incomplete registry.
 * Call only after lifecycle admission is closed AND all mutations have drained.
 */
internal class VaultAliasDestruction(
    private val enumerate: () -> Set<String>,
    private val delete: (String) -> Unit,
    private val exists: (String) -> Boolean,
) {
    fun destroyAll() {
        val aliases = enumerate().filter { it.startsWith("rv.kek.v1.") }.sorted()
        var failure: Exception? = null
        for (alias in aliases) {
            try {
                delete(alias)
                check(!exists(alias)) { "KEK deletion unconfirmed" }
            } catch (error: Exception) {
                // Keep trying other aliases, but never turn partial destruction into success.
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        try {
            check(enumerate().none { it.startsWith("rv.kek.v1.") }) { "Vault aliases remain" }
        } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
