package id.dysklink.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * When DyskLink can't start, the Windows launcher only says "Failed to launch JVM" and Linux shows nothing. The error
 * is written to startup-error.txt in the data folder (or the temp folder) and shown, so it can be copied into a report.
 */
object StartupCrash {
    fun report(error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("DyskLink ${System.getProperty("dysklink.version") ?: "dev"} could not start, ${LocalDateTime.now()}")
            appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
            appendLine()
            append(trace)
        }
        System.err.println(text)
        val file = write(text)
        runCatching {
            if (java.awt.GraphicsEnvironment.isHeadless()) return
            val area = JTextArea(text, 18, 80).apply { isEditable = false }
            val where = file?.let { "\n\nSaved in $it" }.orEmpty()
            JOptionPane.showMessageDialog(
                null,
                arrayOf<Any>("DyskLink could not start. Please send this to DyskLab (github.com/DyskLab/dysklink-desktop/issues).$where", JScrollPane(area)),
                "DyskLink",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun write(text: String): File? {
        val dirs = listOfNotNull(runCatching { Platform.dataDir }.getOrNull(), File(System.getProperty("java.io.tmpdir")))
        for (dir in dirs) {
            val file = File(dir, "startup-error.txt")
            if (runCatching { dir.mkdirs(); file.writeText(text) }.isSuccess) return file
        }
        return null
    }
}
