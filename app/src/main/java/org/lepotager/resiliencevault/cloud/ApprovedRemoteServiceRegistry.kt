package org.lepotager.resiliencevault.cloud

import java.net.IDN

internal enum class ApprovedTlsPolicy {
    SYSTEM_TRUST_STRICT_HOSTNAME,
}

internal data class ApprovedRemoteService(
    val serviceId: String,
    val host: String,
    val port: Int = 443,
    val deleteRoute: String,
    val tlsPolicy: ApprovedTlsPolicy = ApprovedTlsPolicy.SYSTEM_TRUST_STRICT_HOSTNAME,
) {
    init {
        require(serviceId.matches(Regex("[a-z0-9][a-z0-9._:@-]{0,63}")))
        require(port == 443) { "Only standard HTTPS is approved before backend review" }
        require(deleteRoute.startsWith("/") && !deleteRoute.startsWith("//"))
        require(!deleteRoute.contains("://"))
        require(!deleteRoute.contains("?") && !deleteRoute.contains("#"))
        require(deleteRoute.length in 1..128)

        val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        require(ascii == host)
        require(host == host.lowercase())
        require(host.length in 1..253)
        require(!host.startsWith(".") && !host.endsWith("."))
        require(host.split('.').all { label ->
            label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        })
    }

    fun origin(): String = "https://" + host + ":" + port
}

internal fun interface ApprovedRemoteServiceRegistry {
    fun resolve(serviceId: String): ApprovedRemoteService?
}

/**
 * Deliberately empty until a real backend origin/route/TLS contract is reviewed.
 * Test registries must never be reused as production configuration.
 */
internal object ProductionApprovedRemoteServiceRegistry : ApprovedRemoteServiceRegistry {
    override fun resolve(serviceId: String): ApprovedRemoteService? = null
}

internal class FixedApprovedRemoteServiceRegistry(
    services: List<ApprovedRemoteService>,
) : ApprovedRemoteServiceRegistry {
    private val services = services.associateBy { it.serviceId }.also {
        require(it.size == services.size) { "Duplicate approved service id" }
    }

    override fun resolve(serviceId: String): ApprovedRemoteService? =
        services[serviceId]
}

internal sealed interface ApprovedRemoteTarget {
    data class Ready(val service: ApprovedRemoteService) : ApprovedRemoteTarget
    data object Unavailable : ApprovedRemoteTarget
}

internal class ApprovedRemoteTargetResolver(
    private val registry: ApprovedRemoteServiceRegistry,
) {
    fun resolve(serviceId: String): ApprovedRemoteTarget =
        registry.resolve(serviceId)?.let { ApprovedRemoteTarget.Ready(it) }
            ?: ApprovedRemoteTarget.Unavailable
}
