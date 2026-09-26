package org.lepotager.resiliencevault.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedRemoteServiceRegistryTest {
    @Test
    fun production_registry_has_zero_entries() {
        assertNull(ProductionApprovedRemoteServiceRegistry.resolve("primary"))
        assertNull(ProductionApprovedRemoteServiceRegistry.resolve("anything"))
    }

    @Test
    fun test_registry_resolves_only_exact_approved_service_id() {
        val approved = ApprovedRemoteService(
            serviceId = "test-primary",
            host = "delete.test.invalid",
            deleteRoute = "/v1/delete-generation",
        )
        val registry = FixedApprovedRemoteServiceRegistry(listOf(approved))

        assertEquals(approved, registry.resolve("test-primary"))
        assertNull(registry.resolve("TEST-PRIMARY"))
        assertNull(registry.resolve("other"))
    }

    @Test
    fun target_never_comes_from_unapproved_identifier() {
        val approved = ApprovedRemoteService(
            serviceId = "test-primary",
            host = "delete.test.invalid",
            deleteRoute = "/v1/delete-generation",
        )
        val resolver = ApprovedRemoteTargetResolver(
            FixedApprovedRemoteServiceRegistry(listOf(approved))
        )

        assertEquals(
            ApprovedRemoteTarget.Ready(approved),
            resolver.resolve("test-primary"),
        )
        assertTrue(resolver.resolve("evil.example") is ApprovedRemoteTarget.Unavailable)
    }

    @Test
    fun unsafe_origin_or_route_is_rejected() {
        for (builder in listOf<() -> Unit>(
            {
                ApprovedRemoteService(
                    serviceId = "x",
                    host = "Example.COM",
                    deleteRoute = "/v1/delete-generation",
                )
            },
            {
                ApprovedRemoteService(
                    serviceId = "x",
                    host = "delete.test.invalid",
                    port = 8443,
                    deleteRoute = "/v1/delete-generation",
                )
            },
            {
                ApprovedRemoteService(
                    serviceId = "x",
                    host = "delete.test.invalid",
                    deleteRoute = "https://evil.example/delete",
                )
            },
            {
                ApprovedRemoteService(
                    serviceId = "x",
                    host = "delete.test.invalid",
                    deleteRoute = "//evil.example/delete",
                )
            },
        )) {
            var failed = false
            try {
                builder()
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        }
    }
}
