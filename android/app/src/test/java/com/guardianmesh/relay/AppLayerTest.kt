package com.guardianmesh.relay

import com.guardianmesh.relay.data.AppSettings
import com.guardianmesh.relay.data.TrustedContact
import com.guardianmesh.relay.duress.PinVault
import com.guardianmesh.relay.net.api.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the v0.3 app layer. Only the pure parts are covered here — PIN hashing,
 * URL normalisation, contact validation — which is deliberate: everything else in the UI
 * layer needs a real Android runtime, and a test that mocks the framework into submission
 * would prove nothing about whether the SOS gets out.
 *
 * The SOS core's own 26 tests are in CoreLogicTest and are untouched by v0.3.
 */
class AppLayerTest {

    // ───────────────────────────── duress PIN ─────────────────────────────

    @Test
    fun `hashing is deterministic for the same salt`() {
        val salt = PinVault.newSalt()
        assertEquals(PinVault.hash("1234", salt), PinVault.hash("1234", salt))
    }

    @Test
    fun `the same pin under a different salt gives a different hash`() {
        assertNotEquals(
            PinVault.hash("1234", PinVault.newSalt()),
            PinVault.hash("1234", PinVault.newSalt())
        )
    }

    @Test
    fun `salts are not reused`() {
        val salts = (1..50).map { PinVault.newSalt() }.toSet()
        assertEquals(50, salts.size)
    }

    @Test
    fun `the real pin unlocks and the duress pin is reported separately`() {
        val salt = PinVault.newSalt()
        val settings = AppSettings(
            pinSalt = salt,
            realPinHash = PinVault.hash("4071", salt),
            duressPinHash = PinVault.hash("9999", salt)
        )

        assertEquals(PinVault.Outcome.REAL, PinVault.verify("4071", settings))
        assertEquals(PinVault.Outcome.DURESS, PinVault.verify("9999", settings))
        assertEquals(PinVault.Outcome.WRONG, PinVault.verify("1111", settings))
    }

    @Test
    fun `a pin check against an unconfigured vault is never a match`() {
        assertEquals(PinVault.Outcome.WRONG, PinVault.verify("4071", AppSettings()))
    }

    @Test
    fun `without a duress pin only the real pin matches`() {
        val salt = PinVault.newSalt()
        val settings = AppSettings(pinSalt = salt, realPinHash = PinVault.hash("4071", salt))
        assertEquals(PinVault.Outcome.REAL, PinVault.verify("4071", settings))
        assertEquals(PinVault.Outcome.WRONG, PinVault.verify("9999", settings))
    }

    @Test
    fun `pin shape is enforced`() {
        assertTrue(PinVault.isPlausible("1234"))
        assertTrue(PinVault.isPlausible("12345678"))
        assertFalse(PinVault.isPlausible("123"))          // too short
        assertFalse(PinVault.isPlausible("123456789"))    // too long
        assertFalse(PinVault.isPlausible("12a4"))         // not digits
        assertFalse(PinVault.isPlausible(""))
    }

    @Test
    fun `setup refuses a duress pin equal to the real one`() {
        assertNull(PinVault.validationError("4071", null))
        assertNotEquals(null, PinVault.validationError("4071", "4071"))
        assertNull(PinVault.validationError("9999", "4071"))
    }

    /**
     * The calculator must not treat a long arithmetic result as a PIN attempt. Anything
     * outside 4–8 digits is ignored before the vault is ever consulted.
     */
    @Test
    fun `arithmetic results are not treated as pin attempts`() {
        listOf("56", "3.5", "-42", "123456789012").forEach {
            assertFalse("'$it' should not look like a PIN", PinVault.isPlausible(it))
        }
    }

    // ───────────────────────────── backend URL ─────────────────────────────

    @Test
    fun `base url normalisation matches what people actually type`() {
        assertEquals("http://192.168.1.5:5000/", ApiConfig.normalize("192.168.1.5:5000"))
        assertEquals("http://192.168.1.5:5000/", ApiConfig.normalize("http://192.168.1.5:5000"))
        assertEquals("http://192.168.1.5:5000/", ApiConfig.normalize("http://192.168.1.5:5000/"))
        assertEquals("https://api.example.com/", ApiConfig.normalize("https://api.example.com"))
        assertEquals("http://trimmed/", ApiConfig.normalize("  trimmed  "))
    }

    /** v0.1 of the app stored the URL with /relay on the end. Both forms must still work. */
    @Test
    fun `a trailing relay path from the old app is stripped`() {
        assertEquals("http://192.168.1.5:5000/", ApiConfig.normalize("http://192.168.1.5:5000/relay"))
    }

    @Test
    fun `an empty url falls back to the compiled-in constant`() {
        assertEquals(ApiConfig.BASE_URL, ApiConfig.normalize(""))
        assertEquals(ApiConfig.BASE_URL, ApiConfig.normalize("   "))
    }

    @Test
    fun `the compiled-in base url is retrofit-shaped`() {
        assertTrue("Retrofit requires a trailing slash", ApiConfig.BASE_URL.endsWith("/"))
        assertTrue(ApiConfig.BASE_URL.startsWith("http"))
    }

    // ───────────────────────────── contacts ─────────────────────────────

    @Test
    fun `a contact number is reduced to something the radio accepts`() {
        assertEquals("+919876543210", TrustedContact(name = "A", phone = "+91 98765 43210").dialable())
        assertEquals("+919876543210", TrustedContact(name = "A", phone = "+91-98765-43210").dialable())
        assertEquals("9876543210", TrustedContact(name = "A", phone = "(98765) 43210").dialable())
    }

    @Test
    fun `too-short numbers are rejected before they reach the sms channel`() {
        assertTrue(TrustedContact(name = "A", phone = "+919876543210").isUsable())
        assertTrue(TrustedContact(name = "A", phone = "9876543210").isUsable())
        assertFalse(TrustedContact(name = "A", phone = "12345").isUsable())
        assertFalse(TrustedContact(name = "A", phone = "").isUsable())
        assertFalse(TrustedContact(name = "A", phone = "not a number").isUsable())
    }

    // ───────────────────────────── settings defaults ─────────────────────────────

    @Test
    fun `defaults are the safe ones`() {
        val s = AppSettings()
        assertFalse("no PIN until the user sets one", s.isPinConfigured)
        assertFalse(s.hasDuressPin)
        assertTrue("panic gestures on by default", s.powerButtonTriggerEnabled)
        assertTrue(s.shakeTriggerEnabled)
        assertTrue(s.volumeKeyTriggerEnabled)
        assertFalse("the loud alarm is opt-in", s.deterrentWithTrigger)
        assertEquals(ApiConfig.BASE_URL, s.backendBaseUrl)
    }

    @Test
    fun `a configured pin is only complete with a salt`() {
        assertFalse(AppSettings(realPinHash = "abc").isPinConfigured)
        assertFalse(AppSettings(pinSalt = "abc").isPinConfigured)
        assertTrue(AppSettings(realPinHash = "abc", pinSalt = "def").isPinConfigured)
    }
}
