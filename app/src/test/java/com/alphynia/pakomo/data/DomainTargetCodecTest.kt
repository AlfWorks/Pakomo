package com.alphynia.pakomo.data

import com.alphynia.pakomo.core.model.DomainTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainTargetCodecTest {

    @Test
    fun listRoundTripPreservesEnabledFlag() {
        val targets = listOf(
            DomainTarget("api.example.com", enabled = true),
            DomainTarget("cdn.example.com", enabled = false),
        )
        assertEquals(targets, DomainTargetCodec.decodeList(DomainTargetCodec.encodeList(targets)))
    }

    @Test
    fun legacyStringArrayDecodesAsEnabled() {
        // Stored by a build from before the enable/disable flag existed.
        val decoded = DomainTargetCodec.decodeList("""["a.example.com","b.example.com"]""")
        assertEquals(
            listOf(DomainTarget("a.example.com", true), DomainTarget("b.example.com", true)),
            decoded,
        )
    }

    @Test
    fun objectWithoutOnFieldDefaultsToEnabled() {
        assertEquals(
            listOf(DomainTarget("a.example.com", true)),
            DomainTargetCodec.decodeList("""[{"v":"a.example.com"}]"""),
        )
    }

    @Test
    fun disabledFlagSurvivesEncoding() {
        val encoded = DomainTargetCodec.encodeList(listOf(DomainTarget("x.example.com", enabled = false)))
        // Serialized as the object form carrying on=false, not a bare string.
        assertEquals(false, DomainTargetCodec.decodeList(encoded).single().enabled)
    }

    @Test
    fun nullEmptyAndMalformedDecodeToEmpty() {
        assertEquals(emptyList<DomainTarget>(), DomainTargetCodec.decodeList(null))
        assertEquals(emptyList<DomainTarget>(), DomainTargetCodec.decodeList(""))
        assertEquals(emptyList<DomainTarget>(), DomainTargetCodec.decodeList("}{ not json"))
    }

    @Test
    fun blankEntriesAreDropped() {
        val decoded = DomainTargetCodec.decodeList("""["", {"v":""}, {"v":"ok.example.com","on":true}]""")
        assertEquals(listOf(DomainTarget("ok.example.com", true)), decoded)
    }

    @Test
    fun mapRoundTripPreservesFlags() {
        val map = mapOf(
            "com.example.one" to listOf(DomainTarget("a.com", true), DomainTarget("b.com", false)),
            "com.example.two" to listOf(DomainTarget("c.com", true)),
        )
        assertEquals(map, DomainTargetCodec.decodeMap(DomainTargetCodec.encodeMap(map)))
    }

    @Test
    fun legacyPerPackageStringArrayDecodesAsEnabled() {
        val decoded = DomainTargetCodec.decodeMap("""{"com.example.one":["a.com","b.com"]}""")
        assertEquals(
            mapOf("com.example.one" to listOf(DomainTarget("a.com", true), DomainTarget("b.com", true))),
            decoded,
        )
    }

    @Test
    fun nullAndMalformedMapDecodeToEmpty() {
        assertEquals(emptyMap<String, List<DomainTarget>>(), DomainTargetCodec.decodeMap(null))
        assertEquals(emptyMap<String, List<DomainTarget>>(), DomainTargetCodec.decodeMap("nonsense"))
    }
}
