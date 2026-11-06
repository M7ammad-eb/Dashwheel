package com.openauto.dash

import com.openauto.dash.PrivilegedShell.Access
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each kind of access opens up: root does everything, Shizuku and ADB the shell-only features, nothing else. */
class PrivilegedShellTest {

    @Test
    fun rootShizukuAndAdbAreShells_rootAloneIsRoot() {
        assertTrue(Access.ROOT.shell); assertTrue(Access.ROOT.root)
        assertTrue(Access.SHIZUKU.shell); assertFalse(Access.SHIZUKU.root)
        assertTrue(Access.ADB.shell); assertFalse(Access.ADB.root)
        assertFalse(Access.NONE.shell); assertFalse(Access.NONE.root)
        assertFalse(Access.UNKNOWN.shell); assertFalse(Access.UNKNOWN.root)
    }

    @Test
    fun onlyRootAndTheUnitsAdbWriteTheSystem() {
        // Shizuku's shell user cannot write /system: the installers and the boot logo stay locked under it.
        assertEquals(setOf(Access.ROOT, Access.ADB), Access.entries.filter { it.system }.toSet())
    }

    @Test
    fun theCanMonitorNeedsRoot_theCarDataAndMapsWindowAShell_theRestNothing() {
        assertEquals(listOf(Access.ROOT), Access.entries.filter { it.allows(BuiltinKind.CAN_MON) })
        // The Maps window, and the car box's data (doors included), shared once registered through the shell.
        for (kind in listOf(BuiltinKind.PIP_ANCHOR, BuiltinKind.DOORS, BuiltinKind.CAR_STATUS)) {
            assertEquals(kind.name, setOf(Access.ROOT, Access.SHIZUKU, Access.ADB), Access.entries.filter { it.allows(kind) }.toSet())
        }
        // Doors taught in Car signals need no shell.
        assertTrue(Access.entries.all { it.allows(BuiltinKind.DOORS, learnedDoors = true) })
        val plain = BuiltinKind.entries - setOf(BuiltinKind.DOORS, BuiltinKind.CAN_MON, BuiltinKind.PIP_ANCHOR, BuiltinKind.CAR_STATUS)
        for (kind in plain) for (access in Access.entries) assertTrue("$kind under $access", access.allows(kind))
    }

    @Test
    fun theCarAppAlertsNeedItsSettings_callsAndTyresNothing() {
        // No settings permission in a unit test: a shell is the only way.
        assertFalse(PrivilegedShell.settingsGranted)
        for (kind in listOf(RomPopups.Kind.DOORS, RomPopups.Kind.RADAR, RomPopups.Kind.AC, RomPopups.Kind.BELT)) {
            assertEquals(kind.name, setOf(Access.ROOT, Access.SHIZUKU, Access.ADB), Access.entries.filter { RomPopups.canWork(kind, it) }.toSet())
        }
        for (kind in listOf(RomPopups.Kind.CALL, RomPopups.Kind.TYRES)) {
            for (access in Access.entries) assertTrue("$kind under $access", RomPopups.canWork(kind, access))
        }
    }

    @Test
    fun thePlayEdition_findsNoShellWithoutLooking() {
        // Neither su nor a socket is tried: the answer comes before either probe.
        val was = PrivilegedShell.editionPlay
        try {
            PrivilegedShell.editionPlay = true
            assertEquals(Access.NONE, PrivilegedShell.find())
            assertFalse(PrivilegedShell.settingsGranted)
        } finally {
            PrivilegedShell.editionPlay = was
        }
    }

    @Test
    fun theShizukuRow_isForUnitsWithoutRoot_andNotForOnesWithTheirOwnAdbUnlessInUse() {
        if (Edition.play) {
            // Never in the Play edition, which has no Shizuku.
            for (state in ShizukuState.entries) for (access in Access.entries) assertFalse("$access, $state", shizukuOffered(access, state))
            return
        }
        for (state in ShizukuState.entries) {
            assertFalse("root, $state", shizukuOffered(Access.ROOT, state))
            assertTrue("none, $state", shizukuOffered(Access.NONE, state))
            assertTrue("unknown, $state", shizukuOffered(Access.UNKNOWN, state))
            assertTrue("shizuku, $state", shizukuOffered(Access.SHIZUKU, state))
            assertEquals("adb, $state", state == ShizukuState.READY, shizukuOffered(Access.ADB, state))
        }
    }

    @Test
    fun untilProbed_nothingIsOffered() {
        assertEquals(Access.UNKNOWN, PrivilegedShell.access.value)
        assertFalse(PrivilegedShell.access.value.shell)
    }
}
