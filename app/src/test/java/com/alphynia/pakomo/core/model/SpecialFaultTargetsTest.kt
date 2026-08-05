package com.alphynia.pakomo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialFaultTargetsTest {

    private fun reset(
        enabled: Boolean = true,
        appTargets: Map<String, AppFaultTarget> = emptyMap(),
        addressTargets: List<String> = emptyList(),
    ) = SpecialFault(
        type = SpecialFaultType.CONNECTION_RESET,
        enabled = enabled,
        appTargets = appTargets,
        addressTargets = addressTargets,
    )

    @Test
    fun disabledFaultHasNoTargets() {
        val targets = SpecialFaultTargets.effectiveTargets(
            reset(enabled = false),
            TargetScope.GLOBAL,
            emptyMap(),
            emptyList(),
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun globalEnabledYieldsSingleGlobalTarget() {
        val targets = SpecialFaultTargets.effectiveTargets(
            reset(),
            TargetScope.GLOBAL,
            emptyMap(),
            emptyList(),
        )
        assertEquals(listOf(FaultTarget.Global), targets)
        // 全局模式入口不显示计数。
        assertEquals(0, SpecialFaultTargets.effectiveCount(reset(), TargetScope.GLOBAL, emptyMap(), emptyList()))
    }

    @Test
    fun enabledAppWithoutDomainConfigCoversWholeApp() {
        val fault = reset(appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true)))
        val targets = SpecialFaultTargets.effectiveTargets(
            fault,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to emptyList()),
            emptyList(),
        )
        assertEquals(listOf(FaultTarget.WholeApp("com.a")), targets)
    }

    @Test
    fun appWithDomainConfigButNoSelectionIsNotEffective() {
        val fault = reset(appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true, domains = emptyList())))
        val targets = SpecialFaultTargets.effectiveTargets(
            fault,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to listOf("x.example.com")),
            emptyList(),
        )
        assertTrue(targets.isEmpty())
        assertEquals(0, SpecialFaultTargets.effectiveCount(fault, TargetScope.APPLICATIONS, mapOf("com.a" to listOf("x.example.com")), emptyList()))
    }

    @Test
    fun appDomainSelectionOnlyCountsConfiguredDomains() {
        val fault = reset(
            appTargets = mapOf(
                "com.a" to AppFaultTarget("com.a", enabled = true, domains = listOf("x.example.com", "stale.example.com")),
            ),
        )
        val targets = SpecialFaultTargets.effectiveTargets(
            fault,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to listOf("x.example.com")),
            emptyList(),
        )
        assertEquals(listOf(FaultTarget.ApplicationDomain("com.a", "x.example.com")), targets)
    }

    @Test
    fun disabledAppParentSwitchYieldsNothing() {
        val fault = reset(appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = false, domains = listOf("x.example.com"))))
        val targets = SpecialFaultTargets.effectiveTargets(
            fault,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to listOf("x.example.com")),
            emptyList(),
        )
        assertTrue(targets.isEmpty())
    }

    @Test
    fun addressTargetsMustStillExistInScope() {
        val fault = reset(addressTargets = listOf("live.example.com", "removed.example.com"))
        val targets = SpecialFaultTargets.effectiveTargets(
            fault,
            TargetScope.ADDRESSES,
            emptyMap(),
            listOf("live.example.com"),
        )
        assertEquals(listOf(FaultTarget.AddressDomain("live.example.com")), targets)
    }

    @Test
    fun sameDomainInDifferentAppsIsNotAnOverlap() {
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(
                SpecialFaultType.CONNECTION_RESET,
                enabled = true,
                appTargets = mapOf("com.a" to AppFaultTarget("com.a", enabled = true, domains = listOf("x.example.com"))),
            ),
            networkBlackout = SpecialFault(
                SpecialFaultType.NETWORK_BLACKOUT,
                enabled = true,
                appTargets = mapOf("com.b" to AppFaultTarget("com.b", enabled = true, domains = listOf("x.example.com"))),
            ),
        )
        val overlaps = SpecialFaultTargets.overlaps(
            config,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to listOf("x.example.com"), "com.b" to listOf("x.example.com")),
            emptyList(),
        )
        assertTrue(overlaps.isEmpty())
    }

    @Test
    fun sameAppDomainSelectedByTwoFaultsOverlaps() {
        val appTarget = mapOf("com.a" to AppFaultTarget("com.a", enabled = true, domains = listOf("x.example.com")))
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(SpecialFaultType.CONNECTION_RESET, enabled = true, appTargets = appTarget),
            networkBlackout = SpecialFault(SpecialFaultType.NETWORK_BLACKOUT, enabled = true, appTargets = appTarget),
        )
        val overlaps = SpecialFaultTargets.overlaps(
            config,
            TargetScope.APPLICATIONS,
            mapOf("com.a" to listOf("x.example.com")),
            emptyList(),
        )
        assertEquals(
            listOf(SpecialFaultType.CONNECTION_RESET, SpecialFaultType.NETWORK_BLACKOUT),
            overlaps[FaultTarget.ApplicationDomain("com.a", "x.example.com")],
        )
    }

    @Test
    fun globalFaultsOverlapOnSharedScope() {
        val config = SpecialFaultConfig(
            connectionReset = SpecialFault(SpecialFaultType.CONNECTION_RESET, enabled = true),
            dnsFailure = SpecialFault(SpecialFaultType.DNS_FAILURE, enabled = true),
        )
        val overlaps = SpecialFaultTargets.overlaps(config, TargetScope.GLOBAL, emptyMap(), emptyList())
        assertEquals(
            listOf(SpecialFaultType.CONNECTION_RESET, SpecialFaultType.DNS_FAILURE),
            overlaps[FaultTarget.Global],
        )
    }
}
