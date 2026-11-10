package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The root shell kept open between commands, run here on a plain `sh` (the
 * test is skipped on a machine without one): each command's output and exit
 * status come back on their own, one after another, and a command that runs
 * out of time takes the shell down.
 */
class RootShellSessionTest {

    private fun shell(): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, if (File.separatorChar == '\\') "sh.exe" else "sh") }
            .firstOrNull { it.isFile }?.path

    @Test
    fun commandsRunOneAfterAnotherWithTheirOwnOutputAndStatus() {
        val sh = shell() ?: run { assumeTrue("no sh on this machine", false); return }
        val session = RootShell.Session(sh)
        try {
            val first = session.run("echo one; echo two", 5)
            assertEquals(0, first.exit)
            assertEquals("one\ntwo\n", first.out)
            // The status is the command's own, and its stderr comes back with its stdout.
            val second = session.run("echo oops 1>&2; exit 3", 5)
            assertEquals(3, second.exit)
            assertEquals("oops\n", second.out)
            // A command without output.
            assertEquals("", session.run("true", 5).out)
            // An output that does not end its last line: the end mark is still found, the status still the command's.
            val open = session.run("printf abc; exit 2", 5)
            assertEquals(2, open.exit)
            assertEquals("abc\n", open.out)
            // An empty line the command printed itself is kept.
            assertEquals("a\n\n", session.run("echo a; echo", 5).out)
            assertTrue(session.alive)
        } finally {
            session.close()
        }
    }

    @Test
    fun aCommandOutOfTimeClosesTheShell() {
        val sh = shell() ?: run { assumeTrue("no sh on this machine", false); return }
        val session = RootShell.Session(sh)
        try {
            session.run("sleep 5", 1)
            fail("expected a timeout")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("timed out"))
        }
        // The shell goes down a moment after the timeout: waited for, up to two seconds.
        val until = System.currentTimeMillis() + 2_000
        while (session.alive && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(session.alive)
    }
}
