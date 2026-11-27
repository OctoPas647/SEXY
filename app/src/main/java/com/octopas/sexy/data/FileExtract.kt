package com.octopas.sexy.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** A file picked on the phone, ready to upload to the PC and to show the model. */
class PickedFile(val name: String, val bytes: ByteArray, val mime: String?) {
    val isImage get() = mime?.startsWith("image/") == true || LmsFormat.isImageName(name)
}

/**
 * Like LM Studio's "attach file": text, code, PDF and Office files are read out and given to the model;
 * pictures go to vision models. Everything is also stored on the PC.
 */
object FileExtract {
    /** About 30k tokens: more than that is cut so the chat still fits the model's context. */
    const val MAX_CHARS = 120_000

    private val textExt = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log",
        "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "swift", "m", "php", "sh", "bat", "ps1", "sql", "gradle", "properties", "tex", "srt", "vtt", "r", "lua", "dart",
    )

    fun read(context: Context, uri: Uri): PickedFile {
        val cr = context.contentResolver
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return PickedFile(name, bytes, cr.getType(uri))
    }

    /** The text inside a file, or null when it isn't a kind of file we can read. */
    fun text(context: Context, f: PickedFile): String? {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val raw = when {
            ext in textExt || f.mime?.startsWith("text/") == true -> f.bytes.toString(Charsets.UTF_8)
            ext == "pdf" || f.mime == "application/pdf" -> pdf(context, f.bytes)
            ext == "docx" -> officeXml(f.bytes, Regex("word/document\\.xml"), paragraphTag = "w:p")
            ext == "pptx" -> officeXml(f.bytes, Regex("ppt/slides/slide\\d+\\.xml"), paragraphTag = "a:p")
            ext == "xlsx" -> officeXml(f.bytes, Regex("xl/sharedStrings\\.xml"), paragraphTag = "si")
            ext == "odt" -> officeXml(f.bytes, Regex("content\\.xml"), paragraphTag = "text:p")
            else -> null
        } ?: return null
        val t = raw.replace("\r\n", "\n").trim()
        return if (t.length > MAX_CHARS) t.take(MAX_CHARS) + "\n[... cut: the file is longer than the app sends at once]" else t
    }

    private fun pdf(context: Context, bytes: ByteArray): String? = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(ByteArrayInputStream(bytes)).use { PDFTextStripper().getText(it) }
    }.getOrNull()

    /** Office files are zips of XML: keep the text, one line per paragraph. */
    private fun officeXml(bytes: ByteArray, entry: Regex, paragraphTag: String): String? = runCatching {
        val parts = sortedMapOf<String, String>(compareBy({ it.length }, { it }))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (entry.matches(e.name)) parts[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        parts.values.joinToString("\n\n") { xml ->
            xml.replace(Regex("</$paragraphTag>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n")
        }.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pictures for the model: at most 1600 px on the long side, JPEG, as a data: URL. */
    fun imageDataUrl(context: Context, f: PickedFile): String? = runCatching {
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(f.bytes))) { d, info, _ ->
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > 1600) {
                    val k = 1600f / longSide
                    d.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
                }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val b = BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size)
            val longSide = maxOf(b.width, b.height)
            if (longSide > 1600) Bitmap.createScaledBitmap(b, b.width * 1600 / longSide, b.height * 1600 / longSide, true) else b
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun dataUrl(bytes: ByteArray, name: String): String {
        val ext = name.substringAfterLast('.', "jpg").lowercase().let { if (it == "jpg") "jpeg" else it }
        return "data:image/$ext;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** What the model sees for a file: its name and its text, like LM Studio does for small files. */
    fun forPrompt(name: String, text: String) = "<file name=\"$name\">\n$text\n</file>"
}
