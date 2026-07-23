package com.pakomo.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainInputValidatorTest {
    @Test
    fun normalizesProtocolPathPortAndCase() {
        assertEquals(
            "api.example.com",
            DomainInputValidator.normalizeOrNull(" HTTPS://API.Example.com:443/v1/login "),
        )
    }

    @Test
    fun rejectsInvalidOrSingleLabelInput() {
        assertNull(DomainInputValidator.normalizeOrNull("localhost"))
        assertNull(DomainInputValidator.normalizeOrNull("-api.example.com"))
        assertNull(DomainInputValidator.normalizeOrNull("api example.com"))
    }

    @Test
    fun convertsInternationalDomainToAscii() {
        assertEquals(
            "xn--fsqu00a.xn--0zwm56d",
            DomainInputValidator.normalizeOrNull("例子.测试"),
        )
    }
}
