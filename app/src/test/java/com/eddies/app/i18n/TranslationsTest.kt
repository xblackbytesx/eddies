package com.eddies.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Keeps every translation in step with the English it translates.
 *
 * A string missing from a translation falls back to English without a word,
 * and one whose placeholders differ crashes the screen that formats it, on that
 * language only. Neither shows up in an English build, so both are checked here.
 */
class TranslationsTest {

    private val res = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private val base = entries(File(res, "values/strings.xml"))

    private val translations: List<File> =
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }
            .orEmpty().map { File(it, "strings.xml") }.sortedBy { it.path }

    /** Resource name (plus quantity for a plural item) to its text. */
    private fun entries(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> out[name] = node.textContent
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        out["$name#${item.getAttribute("quantity")}"] = item.textContent
                    }
                }
            }
        }
        return out
    }

    /** "values-nl", for naming the translation in a failure. */
    private fun folder(file: File): String = file.parentFile?.name.orEmpty()

    private fun placeholders(text: String): List<String> =
        Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `there is at least one translation to check`() {
        assertTrue("no values-xx/strings.xml found under ${res.path}", translations.isNotEmpty())
    }

    @Test
    fun `every translation has exactly the strings the english file has`() {
        for (file in translations) {
            val theirs = entries(file)
            assertEquals("missing from ${folder(file)}", emptySet<String>(), base.keys - theirs.keys)
            assertEquals("left over in ${folder(file)}", emptySet<String>(), theirs.keys - base.keys)
        }
    }

    @Test
    fun `every translation formats the same arguments as the english`() {
        for (file in translations) {
            val theirs = entries(file)
            for ((key, english) in base) {
                assertEquals(
                    "$key in ${folder(file)}",
                    placeholders(english),
                    placeholders(theirs[key].orEmpty()),
                )
            }
        }
    }

    @Test
    fun `no translation is left empty`() {
        for (file in translations) {
            val blank = entries(file).filterValues { it.isBlank() }.keys
            assertEquals("blank in ${folder(file)}", emptySet<String>(), blank)
        }
    }
}
