package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shell text that keeps `npm install` working on shared storage, run for real.
 *
 * Shared storage cannot hold a symbolic link, which a JVM test has no way to
 * produce, so a missing `ln` stands in for it: the decision is made by asking the
 * folder to make a link, and a function named `ln` that fails is a folder that
 * cannot. What is asserted is the decision and what the fallback then does, not
 * the text.
 */
class NpmBinLinksTest {

    @TempDir
    lateinit var tmp: File

    private lateinit var nolinks: File
    private lateinit var fallback: File

    @BeforeEach
    fun writeScripts() {
        nolinks = File(tmp, "nolinks.sh").apply { writeText(NpmBinLinks.NOLINKS_FUNCTION) }
        fallback = File(tmp, "fallback.sh").apply { writeText(NpmBinLinks.BIN_FALLBACK_FUNCTIONS) }
    }

    private fun run(shell: String, workDir: File, script: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(shell, "-c", script))
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the script did not finish: $out")
        return process.exitValue() to out.trim()
    }

    private fun decision(args: String, linksWork: Boolean, keep: Boolean = false): Int {
        val shell = if (File("/bin/sh").canExecute()) "/bin/sh" else {
            assumeTrue(false, "no /bin/sh on this host"); ""
        }
        val ln = if (linksWork) "" else "ln() { return 1; }; "
        val keepVar = if (keep) "VSCODROID_KEEP_BIN_LINKS=1; " else ""
        return run(shell, tmp, "$ln$keepVar. '${nolinks.path}'; __vscodroid_npm_nolinks $args").first
    }

    @Test
    fun `no text is left carrying the placeholder for the dollar sign`() {
        assertFalse(NpmBinLinks.NOLINKS_FUNCTION.contains('§'))
        assertFalse(NpmBinLinks.BIN_FALLBACK_FUNCTIONS.contains('§'))
        assertTrue(NpmBinLinks.NOLINKS_FUNCTION.contains(NpmBinLinks.BLOCK_MARKER))
    }

    @Test
    fun `a folder that can hold links keeps them`() {
        assertEquals(1, decision("install", linksWork = true))
    }

    @Test
    fun `a folder that cannot hold links gets no bin links for install and ci`() {
        assertEquals(0, decision("install", linksWork = false))
        assertEquals(0, decision("ci", linksWork = false))
        assertEquals(0, decision("i left-pad", linksWork = false))
    }

    @Test
    fun `a global command is never changed, because it writes where links work`() {
        assertEquals(1, decision("install -g typescript", linksWork = false))
        assertEquals(1, decision("install --global typescript", linksWork = false))
        assertEquals(1, decision("--location=global i typescript", linksWork = false))
        assertEquals(1, decision("--location global i typescript", linksWork = false))
    }

    @Test
    fun `an argument after the double dash belongs to the script, not to npm`() {
        assertEquals(0, decision("run build -- -g", linksWork = false))
    }

    @Test
    fun `the user can keep links`() {
        assertEquals(1, decision("install", linksWork = false, keep = true))
    }

    @Test
    fun `a missing tool is found through its package and run directly`() {
        assumeTrue(File("/bin/bash").canExecute() || File("/usr/bin/bash").canExecute(), "no bash on this host")
        val bash = if (File("/bin/bash").canExecute()) "/bin/bash" else "/usr/bin/bash"
        val node = listOf("/usr/bin/node", "/usr/local/bin/node", "/opt/node22/bin/node")
            .firstOrNull { File(it).canExecute() }
        assumeTrue(node != null || System.getenv("PATH").orEmpty().split(':').any { File(it, "node").canExecute() }, "no node on this host")

        val pkg = File(tmp, "node_modules/zpkg/bin").apply { mkdirs() }
        File(tmp, "node_modules/zpkg/package.json").writeText("""{"name":"zpkg","bin":{"zcc":"./bin/zcc"}}""")
        File(pkg, "zcc").writeText("#!/usr/bin/env node\nconsole.log('zcc ' + process.argv.slice(2).join(','))\n")
        val nested = File(tmp, "src/deep").apply { mkdirs() }

        val (status, out) = run(bash, nested, ". '${fallback.path}'; zcc a b; echo status=$?")

        assertEquals("zcc a,b\nstatus=0", out, "the tool was not found from a folder below node_modules' parent")
        assertEquals(0, status)
    }

    @Test
    fun `a command that is not a package tool is still not found`() {
        assumeTrue(File("/bin/bash").canExecute() || File("/usr/bin/bash").canExecute(), "no bash on this host")
        val bash = if (File("/bin/bash").canExecute()) "/bin/bash" else "/usr/bin/bash"

        val (_, out) = run(bash, tmp, ". '${fallback.path}'; no-such-tool-anywhere; echo status=$?")

        assertTrue(out.contains("no-such-tool-anywhere: command not found"), out)
        assertTrue(out.endsWith("status=127"), out)
    }
}
