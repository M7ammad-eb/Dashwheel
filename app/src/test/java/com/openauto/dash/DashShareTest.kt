package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dashboard and its look as a "DW1:" code, and back. */
class DashShareTest {

    private val page = listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVMAP, 0, 0, 7, 5),
        DashboardItem.BuiltinWidget(BuiltinKind.CLOCK, 7, 0, 5, 2, design = WidgetDesign.STANDARD),
        DashboardItem.AppShortcut("com.spotify.music", 7, 2, 2, 2),
        DashboardItem.AppWindow("com.waze", 9, 2, 3, 3, inside = true),
        DashboardItem.SystemWidget(42, 0, 5, 5, 2)
    )

    private val look = SharedLook(
        mode = DashThemeMode.PANORAMA, appearance = DashAppearance.DARK, effects = DashEffects.REDUCED,
        rail = true, tabs = false, barItems = listOf(BarItem.CLOCK, BarItem.OUTSIDE), barLook = BarLook.MINIMAL
    )

    @Test
    fun roundTripsWithItsLook() {
        val code = DashShare.encode(SharedDashboard("Road trip", upright = false, items = page, look = look))
        assertTrue(code.startsWith(DashShare.PREFIX))
        val back = DashShare.decode(code)!!
        assertEquals("Road trip", back.name)
        assertEquals(false, back.upright)
        assertEquals(look, back.look)
        // The Android widget is bound to the unit it was made on: it never travels.
        assertEquals(page.filterNot { it is DashboardItem.SystemWidget }, back.items)
    }

    @Test
    fun withoutALookThereIsNone() {
        val back = DashShare.decode(DashShare.encode(SharedDashboard(null, upright = true, items = page)))!!
        assertNull(back.look)
        assertNull(back.name)
        assertTrue(back.upright)
    }

    @Test
    fun isShortEnoughForAQrCode() {
        val code = DashShare.encode(SharedDashboard("Road trip", upright = false, items = page, look = look))
        assertTrue("code is ${code.length} long", code.length < 600)
    }

    @Test
    fun findsTheCodeInsideAMessage() {
        val code = DashShare.encode(SharedDashboard("Mine", upright = false, items = page))
        assertEquals(SharedDashboard("Mine", upright = false, items = DashShare.shareable(page)), DashShare.decode("Here is my dashboard: $code enjoy!"))
    }

    @Test
    fun aStrangerInACodeReadDoesNotLeakIntoTheNextCodeMade() {
        // A code from a newer build, with a tile this build does not know.
        val stranger = """{"t":"builtin","k":"FROM_THE_FUTURE","gx":0,"gy":0,"gw":4,"gh":4}"""
        val known = """{"t":"builtin","k":"CLOCK","gx":4,"gy":0,"gw":4,"gh":4}"""
        val doc = """{"schema":${DashboardStore.SCHEMA_VERSION},"pages":[[$stranger,$known]]}"""
        val read = DashboardStore.parsePagesPlain(doc)!!.first()
        assertEquals(listOf(BuiltinKind.CLOCK), read.filterIsInstance<DashboardItem.BuiltinWidget>().map { it.kind })
        // What this unit shares afterwards is what it shows: the stranger is not appended.
        val made = DashboardStore.serializePagesPlain(listOf(page))
        assertTrue("FROM_THE_FUTURE" !in made)
        val back = DashShare.decode(DashShare.encode(SharedDashboard(null, upright = false, items = page)))!!
        assertEquals(DashShare.shareable(page), back.items)
    }

    @Test
    fun anythingElseIsNoDashboard() {
        assertNull(DashShare.decode(""))
        assertNull(DashShare.decode("hello"))
        assertNull(DashShare.decode("DW1:not-really-a-code"))
        assertNull(DashShare.decode("DW1:" + "A".repeat(200)))
    }

    @Test
    fun saysWhichAppsAreMissing() {
        val missing = DashShare.missingApps(page, setOf("com.waze"))
        assertEquals(listOf("com.spotify.music"), missing)
    }
}
