package com.pakomo.vpn

import com.pakomo.core.model.NetworkRule
import com.pakomo.core.model.TargetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnRuntimeConfigStoreTest {

    @Test
    fun configurationIsConsumedOnlyOnceAndCopiesCollections() {
        val packages = mutableListOf("com.example")
        val domains = mutableListOf("example.com")
        val byPackage = mutableMapOf("com.example" to domains)
        val rule = NetworkRule(
            id = "test",
            name = "test",
            latencyMs = 0,
            jitterMs = 0,
            packetLossPercent = 0,
            downloadKbps = null,
            uploadKbps = null,
        )

        val id = VpnRuntimeConfigStore.publish(
            TargetScope.APPLICATIONS,
            packages,
            domains,
            byPackage,
            rule,
        )
        packages += "com.changed"
        domains += "changed.example"
        byPackage.clear()

        val stored = VpnRuntimeConfigStore.consume(id)
        assertEquals(listOf("com.example"), stored?.selectedPackages)
        assertEquals(listOf("example.com"), stored?.targetDomains)
        assertEquals(listOf("example.com"), stored?.domainsByPackage?.get("com.example"))
        assertNull(VpnRuntimeConfigStore.consume(id))
    }
}
