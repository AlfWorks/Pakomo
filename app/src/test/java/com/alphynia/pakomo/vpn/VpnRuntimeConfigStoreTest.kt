package com.alphynia.pakomo.vpn

import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.TargetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnRuntimeConfigStoreTest {

    @Test
    fun configurationIsConsumedOnlyOnce() {
        val packages = listOf("com.example")
        val domains = listOf("example.com")
        val byPackage = mapOf("com.example" to domains)
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
        val stored = VpnRuntimeConfigStore.consume(id)
        assertEquals(listOf("com.example"), stored?.selectedPackages)
        assertEquals(listOf("example.com"), stored?.targetDomains)
        assertEquals(listOf("example.com"), stored?.domainsByPackage?.get("com.example"))
        assertNull(VpnRuntimeConfigStore.consume(id))
    }
}
