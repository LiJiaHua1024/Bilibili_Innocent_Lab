package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import fixtures.privacy.nativelist.AmbiguousCall
import fixtures.privacy.nativelist.NewCall
import fixtures.privacy.nativelist.OldCall
import fixtures.privacy.nativelist.Resp
import fixtures.privacy.nativelist.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Type

class GuestNativeListHttpHooksTest {
    @Test
    fun theConverterKeepsItsStableNameWhereTheBuildStillHasIt() {
        // 8.84 to 8.96: `p` and a same-shaped private `q`; the name decides, the shape is never consulted.
        val method = GuestNativeListHttpHooks.locateConverter(OldCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("p", method?.name)
    }

    @Test
    fun theConverterIsFoundByShapeWhenTheNameIsObfuscated() {
        // 8.97+: the single public instance (Response) -> retrofit2.Response method, whatever it is called.
        val method = GuestNativeListHttpHooks.locateConverter(NewCall::class.java, Resp::class.java, Result::class.java)
        assertEquals("a", method?.name)
    }

    @Test
    fun theConverterIsNotGuessedWhenTwoPublicMethodsShareTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(AmbiguousCall::class.java, Resp::class.java, Result::class.java))
    }

    @Test
    fun theConverterIsAbsentWhenNoMethodHasTheShape() {
        assertNull(GuestNativeListHttpHooks.locateConverter(String::class.java, Resp::class.java, Result::class.java))
    }

    @Test
    fun theResponseTypeFieldIsTheOnlyFieldOfThatType() {
        val field = GuestNativeListHttpHooks.locateResponseType(WithType::class.java)
        assertEquals("c", field?.name)
    }

    @Test
    fun theResponseTypeFieldIsNotGuessedWhenTwoFieldsShareTheType() {
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithTwoTypes::class.java))
        assertNull(GuestNativeListHttpHooks.locateResponseType(WithoutType::class.java))
    }

    @Suppress("unused")
    private class WithType {
        // 9.10+ renamed the field and moved the boolean flags: the type field is found by type, not by `b`.
        val m = false
        val c: Type = String::class.java
    }

    @Suppress("unused")
    private class WithTwoTypes {
        val b: Type = String::class.java
        val c: Type = Int::class.java
    }

    @Suppress("unused")
    private class WithoutType {
        val b = 1
    }
}
