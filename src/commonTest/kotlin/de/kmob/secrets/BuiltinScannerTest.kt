package de.kmob.secrets

import de.kmob.kiro.Snapshot
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fixtures werden zur Laufzeit erzeugt, damit keine echt aussehenden Tokens im Repo liegen
 * (GitHub Push Protection, Secret-Scanner im Team).
 */
object Fixtures {
    private val rnd = Random(42)
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    fun random(n: Int, alphabet: String = ALNUM) = (1..n).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")

    val awsKey get() = "AK" + "IA" + random(16, "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
    val awsDocKey = "AK" + "IA" + "IOSFODNN7" + "EXAMPLE"
    val githubPat get() = "gh" + "p_" + random(36)
    val githubFine get() = "github" + "_pat_" + random(40, ALNUM + "_")
    val slack get() = "xo" + "xb-" + random(11, "0123456789") + "-" + random(12, "0123456789") + "-" + random(24)
    val bearer get() = "Authorization: Bearer " + random(40)
    val privateKey get() = "-----BEGIN RSA PRI" + "VATE KEY-----\\n" + random(64) + "\\n" + random(64) + "\\n-----END RSA PRI" + "VATE KEY-----"

    /** JSON-Log-Zeile wie in Kiro (Tool-Ausgabe als String). */
    fun line(stdout: String) = """{"version":"v1","kind":"ToolResults","data":{"stdout":"$stdout"}}"""
}

class BuiltinScannerTest {
    private val scanner = BuiltinScanner()

    private fun ids(text: String) = scanner.matchRules(Fixtures.line(text))

    @Test
    fun detectsEachMandatoryRule() {
        assertEquals(listOf("kmob-aws-access-key-id"), ids("AWS_ACCESS_KEY_ID=${Fixtures.awsKey}"))
        assertEquals(listOf("kmob-aws-access-key-id"), ids("\\n${Fixtures.awsKey}\\n"))
        assertEquals(listOf("kmob-github-token"), ids("token ${Fixtures.githubPat}"))
        assertEquals(listOf("kmob-github-token"), ids(Fixtures.githubFine))
        assertEquals(listOf("kmob-slack-token"), ids(Fixtures.slack))
        assertEquals(listOf("kmob-bearer-token"), ids("curl -H '${Fixtures.bearer}'"))
        assertEquals(listOf("kmob-private-key"), ids(Fixtures.privateKey))
    }

    @Test
    fun ignoresKeyNamesExamplesAndProse() {
        assertEquals(emptyList(), ids("set aws_secret_access_key in ~/.aws/credentials"))
        assertEquals(emptyList(), ids("example: ${Fixtures.awsDocKey}"))
        assertEquals(emptyList(), ids("use a Bearer token in the Authorization header"))
        assertEquals(emptyList(), ids("Bearer xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertEquals(emptyList(), ids("Bearer \$TOKEN_FROM_ENVIRONMENT_VARIABLE"))
        assertEquals(emptyList(), ids("github high ghost gh_cli"))
        assertEquals(emptyList(), ids("-----BEGIN RSA PRI" + "VATE KEY----- header only"))
        assertEquals(emptyList(), ids("xoxb-your-token-here-please"))
        assertEquals(emptyList(), ids("XAKIA" + "ABCDEFGHIJKLMNOP is part of a longer word"))
    }

    @Test
    fun scansMetadataAndLogWithEntryNumbers() {
        val fs = FakeFileSystem()
        fs.createDirectories("/t".toPath())
        fs.write("/t/meta.json".toPath()) { writeUtf8("""{"title":"key ${Fixtures.githubPat}"}""") }
        fs.write("/t/log.jsonl".toPath()) {
            writeUtf8(Fixtures.line("clean") + "\n" + Fixtures.line(Fixtures.awsKey) + "\n" + Fixtures.line("ok") + "\n" + Fixtures.line(Fixtures.slack + " " + Fixtures.githubPat) + "\n")
        }
        val r = scanner.scan(fs, Snapshot("/t/meta.json".toPath(), "/t/log.jsonl".toPath(), 4))
        assertIs<ScanOutcome.Findings>(r)
        assertEquals(
            listOf(
                Hit(listOf("kmob-github-token"), "session metadata"),
                Hit(listOf("kmob-aws-access-key-id"), "log entry #2"),
                Hit(listOf("kmob-github-token", "kmob-slack-token"), "log entry #4"),
            ),
            r.hits,
        )
    }

    @Test
    fun cleanSnapshot() {
        val fs = FakeFileSystem()
        fs.createDirectories("/t".toPath())
        fs.write("/t/meta.json".toPath()) { writeUtf8("{}") }
        fs.write("/t/log.jsonl".toPath()) { writeUtf8(Fixtures.line("hello") + "\n") }
        assertEquals(ScanOutcome.Clean, scanner.scan(fs, Snapshot("/t/meta.json".toPath(), "/t/log.jsonl".toPath(), 1)))
    }

    @Test
    fun describeNeverContainsSecretAndIsCapped() {
        val hits = (1..12).map { Hit(listOf("kmob-github-token"), "log entry #$it") }
        val lines = describeHits(hits)
        assertEquals(11, lines.size)
        assertEquals("possible secret (kmob-github-token) in log entry #1", lines.first())
        assertEquals("… and 2 more", lines.last())
    }

    @Test
    fun entropy() {
        assertEquals(0.0, shannonEntropy("aaaa"))
        assertEquals(2.0, shannonEntropy("abcd"))
        assertTrue(shannonEntropy(Fixtures.random(40)) > 4.0)
    }
}
