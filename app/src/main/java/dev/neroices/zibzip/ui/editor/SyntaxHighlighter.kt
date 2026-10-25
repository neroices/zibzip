package dev.neroices.zibzip.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.util.regex.Matcher
import java.util.regex.Pattern

@Immutable
data class SyntaxColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val operator: Color,
    val searchMatch: Color,
    val activeSearchMatch: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val editorBackground: Color,
    val editorText: Color,
)

fun createSyntaxColors(isDark: Boolean, colorScheme: ColorScheme): SyntaxColors {
    return if (isDark) {
        SyntaxColors(
            keyword = Color(0xFFCF8E6D),       // Warm amber / coral
            type = Color(0xFF4EC9B0),          // Teal / turquoise
            string = Color(0xFF6AAB73),        // Vibrant soft green
            comment = Color(0xFF808080),       // Muted slate gray
            number = Color(0xFF2AACB8),        // Light cyan
            annotation = Color(0xFFBBB529),    // Golden amber
            tag = Color(0xFFE06C75),           // Coral red
            attribute = Color(0xFFD19A66),     // Warm orange
            operator = Color(0xFFA6B2C0),      // Light slate
            searchMatch = Color(0x66FFEB3B),   // Transparent yellow
            activeSearchMatch = Color(0xFFFF9800), // Vibrant amber
            gutterBackground = colorScheme.surfaceContainerLow,
            gutterText = colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            editorBackground = colorScheme.surface,
            editorText = colorScheme.onSurface,
        )
    } else {
        SyntaxColors(
            keyword = Color(0xFF0033B3),       // Deep royal blue
            type = Color(0xFF00627A),          // Deep teal
            string = Color(0xFF067D17),        // Forest green
            comment = Color(0xFF8C8C8C),       // Slate gray
            number = Color(0xFF1750EB),        // Blue
            annotation = Color(0xFF9E880D),    // Dark mustard / gold
            tag = Color(0xFF800000),           // Dark maroon
            attribute = Color(0xFF0055AA),     // Medium blue
            operator = Color(0xFF383A42),      // Charcoal
            searchMatch = Color(0x88FFF59D),   // Soft yellow
            activeSearchMatch = Color(0xFFFF9800), // Amber
            gutterBackground = colorScheme.surfaceContainerLow,
            gutterText = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            editorBackground = colorScheme.surface,
            editorText = colorScheme.onSurface,
        )
    }
}

/**
 * Precompiled regex rules for each syntax category.
 */
internal data class LanguagePatterns(
    val pattern: Pattern?,
    val groupNames: List<String>,
)

internal object SyntaxRegistry {
    private val cache = mutableMapOf<SyntaxLanguage, LanguagePatterns>()

    private const val TRIPLE_DOUBLE_QUOTES = "\"\"\"[\\s\\S]*?\"\"\""
    private const val TRIPLE_SINGLE_QUOTES = "'''[\\s\\S]*?'''"
    private const val DOUBLE_QUOTED_STRING = "\"(?:\\\\.|[^\"\\\\])*\""
    private const val SINGLE_QUOTED_STRING = "'(?:\\\\.|[^'\\\\])*'"

    @Synchronized
    fun getPatterns(language: SyntaxLanguage): LanguagePatterns {
        cache[language]?.let { return it }

        val groups = mutableListOf<Pair<String, String>>()

        when (language) {
            SyntaxLanguage.Kotlin -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:$TRIPLE_DOUBLE_QUOTES|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "annotation" to """@[A-Za-z0-9_.]+"""
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?)[fFlLuU]?\b"""
                groups += "keyword" to """\b(?:package|import|class|interface|object|enum|fun|val|var|private|public|protected|internal|override|final|abstract|open|data|sealed|companion|init|constructor|this|super|return|if|else|when|for|while|do|try|catch|finally|throw|is|as|in|by|suspend|inline|reified|typealias|const|lateinit|null|true|false)\b"""
                groups += "type" to """\b(?:Int|Long|Float|Double|Boolean|String|Char|Byte|Short|Unit|Any|List|Map|Set|Array|Sequence)\b"""
            }
            SyntaxLanguage.Java -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:$TRIPLE_DOUBLE_QUOTES|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "annotation" to """@[A-Za-z0-9_.]+"""
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?)[fFlLdD]?\b"""
                groups += "keyword" to """\b(?:package|import|public|protected|private|static|final|abstract|class|interface|enum|record|extends|implements|new|this|super|return|void|if|else|switch|case|default|break|continue|for|while|do|try|catch|finally|throw|throws|instanceof|synchronized|volatile|transient|native|null|true|false)\b"""
                groups += "type" to """\b(?:int|long|float|double|boolean|char|byte|short|Integer|Long|Float|Double|Boolean|String|Character|Byte|Short|Object|Void|List|Map|Set)\b"""
            }
            SyntaxLanguage.Python -> {
                groups += "comment" to """#[^\r\n]*"""
                groups += "string" to "(?:(?:f|r|u|fr|rf)?(?:$TRIPLE_SINGLE_QUOTES|$TRIPLE_DOUBLE_QUOTES|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING))"
                groups += "annotation" to """@[A-Za-z0-9_.]+"""
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?j?)\b"""
                groups += "keyword" to """\b(?:def|class|import|from|as|return|if|elif|else|for|while|break|continue|try|except|finally|raise|with|yield|lambda|pass|assert|global|nonlocal|async|await|and|or|not|in|is|True|False|None)\b"""
                groups += "type" to """\b(?:int|float|str|bool|list|dict|set|tuple|bytes|bytearray|range|type|object)\b"""
            }
            SyntaxLanguage.JavaScript, SyntaxLanguage.TypeScript -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:`(?:\\\\.|[^`\\\\])*`|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "annotation" to """@[A-Za-z0-9_.]+"""
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?n?)\b"""
                groups += "keyword" to """\b(?:function|class|const|let|var|return|if|else|for|while|do|switch|case|default|break|continue|try|catch|finally|throw|new|this|super|import|export|from|as|default|async|await|yield|typeof|instanceof|void|delete|in|of|null|undefined|true|false|NaN|interface|type|enum|extends|implements|public|private|protected|readonly|declare|namespace|keyof)\b"""
                groups += "type" to """\b(?:string|number|boolean|symbol|bigint|any|unknown|never|void|object|Array|Promise|Record)\b"""
            }
            SyntaxLanguage.Rust -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:r#*\"[\\s\\S]*?\"#*|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "annotation" to """#!?\[[\s\S]*?\]"""
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?)(?:[iu](?:8|16|32|64|128|size)|f(?:32|64))?\b"""
                groups += "keyword" to """\b(?:fn|let|mut|struct|enum|impl|trait|type|pub|mod|use|crate|super|self|Self|return|if|else|match|loop|while|for|in|break|continue|where|const|static|unsafe|async|await|move|ref|dyn|true|false)\b"""
                groups += "type" to """\b(?:i8|i16|i32|i64|i128|isize|u8|u16|u32|u64|u128|usize|f32|f64|bool|char|str|String|Vec|Option|Result|Box|Rc|Arc)\b"""
            }
            SyntaxLanguage.C, SyntaxLanguage.Cpp -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "annotation" to """#[ \t]*(?:include|define|undef|ifdef|ifndef|if|elif|else|endif|pragma)[^\r\n]*"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?)[uUlLfF]*\b"""
                groups += "keyword" to """\b(?:auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|class|namespace|new|delete|public|private|protected|virtual|override|template|typename|using|try|catch|throw|nullptr|true|false|constexpr)\b"""
                groups += "type" to """\b(?:bool|size_t|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|std::string|std::vector|std::map)\b"""
            }
            SyntaxLanguage.Go -> {
                groups += "comment" to """(?://[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:`(?:\\\\.|[^`\\\\])*`|$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.[\d_]+)?(?:[eE][+-]?\d+)?)\b"""
                groups += "keyword" to """\b(?:break|default|func|interface|select|case|defer|go|map|struct|chan|else|goto|package|switch|const|fallthrough|if|range|type|continue|for|import|return|var|nil|true|false)\b"""
                groups += "type" to """\b(?:int|int8|int16|int32|int64|uint|uint8|uint16|uint32|uint64|float32|float64|complex64|complex128|string|bool|byte|rune|error)\b"""
            }
            SyntaxLanguage.Shell -> {
                groups += "comment" to """#[^\r\n]*"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "annotation" to """\$(?:\{[A-Za-z0-9_]+\}|[A-Za-z0-9_]+|[0-9#?!*@$])"""
                groups += "keyword" to """\b(?:if|then|else|elif|fi|case|esac|for|while|until|do|done|in|function|select|return|exit|local|export|readonly|echo|source|alias)\b"""
            }
            SyntaxLanguage.Html, SyntaxLanguage.Xml -> {
                groups += "comment" to """<!--[\s\S]*?-->"""
                groups += "annotation" to """<!(?:DOCTYPE|\[CDATA\[)[\s\S]*?>?"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "tag" to """</?[A-Za-z0-9_:\-]+"""
                groups += "attribute" to """\b[A-Za-z0-9_:\-]+(?=\s*=)"""
            }
            SyntaxLanguage.Css -> {
                groups += "comment" to """/\*[\s\S]*?\*/"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|pt|s|ms|deg)?\b"""
                groups += "annotation" to """@[A-Za-z0-9_\-]+"""
                groups += "attribute" to """\b[A-Za-z0-9_\-]+(?=\s*:)"""
            }
            SyntaxLanguage.Json -> {
                groups += "attribute" to "\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)"
                groups += "string" to DOUBLE_QUOTED_STRING
                groups += "number" to """\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b"""
                groups += "keyword" to """\b(?:true|false|null)\b"""
            }
            SyntaxLanguage.Yaml -> {
                groups += "comment" to """#[^\r\n]*"""
                groups += "attribute" to """^[ \t]*[A-Za-z0-9_\-\.]+(?=\s*:)"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b\d+(?:\.\d+)?\b"""
                groups += "keyword" to """\b(?:true|false|yes|no|null|~)\b"""
            }
            SyntaxLanguage.Toml, SyntaxLanguage.Properties -> {
                groups += "comment" to """(?:#|;)[^\r\n]*"""
                groups += "annotation" to """^\[.*?\]"""
                groups += "attribute" to """^[ \t]*[A-Za-z0-9_\-\.]+(?=\s*[:=])"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b\d+(?:\.\d+)?\b"""
                groups += "keyword" to """\b(?:true|false)\b"""
            }
            SyntaxLanguage.Sql -> {
                groups += "comment" to """(?:--[^\r\n]*|/\*[\s\S]*?\*/)"""
                groups += "string" to "(?:$DOUBLE_QUOTED_STRING|$SINGLE_QUOTED_STRING)"
                groups += "number" to """\b\d+(?:\.\d+)?\b"""
                groups += "keyword" to """\b(?i:SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|TABLE|DROP|ALTER|INDEX|JOIN|INNER|LEFT|RIGHT|FULL|ON|GROUP|BY|ORDER|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|AS|AND|OR|NOT|IN|IS|NULL|LIKE|BETWEEN|EXISTS|CASE|WHEN|THEN|ELSE|END|PRIMARY|KEY|FOREIGN|REFERENCES|CONSTRAINT|DEFAULT|CHECK|VALUES|SET|INTO|VIEW|TRIGGER)\b"""
            }
            SyntaxLanguage.Markdown -> {
                groups += "annotation" to """^#{1,6}\s+[^\r\n]*"""
                groups += "string" to "(?:```[\\s\\S]*?```|`[^`\\r\\n]+`)"
                groups += "tag" to """!?\[.*?\]\(.*?\)"""
                groups += "comment" to """^>[^\r\n]*"""
                groups += "keyword" to """^[ \t]*[-*+]\s+|^[ \t]*\d+\.\s+"""
            }
            SyntaxLanguage.Lrc -> {
                groups += "number" to """\[\d{2}:\d{2}(?:\.\d{2,3})?\]"""
                groups += "annotation" to """\[[a-zA-Z]{2,8}:[^\]]*\]"""
                groups += "comment" to """\([^)]*\)"""
            }
            SyntaxLanguage.PlainText -> {
                // Highlighting log levels or URLs in plain text
                groups += "keyword" to """\b(?:ERROR|FATAL|CRITICAL)\b"""
                groups += "annotation" to """\b(?:WARN|WARNING)\b"""
                groups += "type" to """\b(?:INFO|NOTICE)\b"""
                groups += "comment" to """\b(?:DEBUG|TRACE)\b"""
                groups += "string" to """https?://[^\s]+"""
            }
        }

        if (groups.isEmpty()) {
            val empty = LanguagePatterns(null, emptyList())
            cache[language] = empty
            return empty
        }

        val patternBuilder = StringBuilder()
        val groupNames = mutableListOf<String>()

        for ((idx, pair) in groups.withIndex()) {
            val (name, regex) = pair
            if (idx > 0) patternBuilder.append("|")
            val groupName = "$name$idx"
            patternBuilder.append("(?<$groupName>$regex)")
            groupNames += groupName
        }

        val pattern = Pattern.compile(patternBuilder.toString(), Pattern.MULTILINE)
        val compiled = LanguagePatterns(pattern, groupNames)
        cache[language] = compiled
        return compiled
    }
}

/**
 * Highlights [text] using regex token matching.
 */
fun highlightSyntax(
    text: String,
    language: SyntaxLanguage,
    colors: SyntaxColors,
    searchQuery: String = "",
    searchCaseSensitive: Boolean = false,
    activeMatchIndex: Int = -1,
): AnnotatedString {
    val builder = AnnotatedString.Builder(text)

    // Safety limit: if text > 100,000 chars, skip full-document tokenization to avoid blocking the UI thread
    if (text.length > 100_000) {
        applySearchHighlights(builder, text, searchQuery, searchCaseSensitive, activeMatchIndex, colors)
        return builder.toAnnotatedString()
    }

    val patterns = SyntaxRegistry.getPatterns(language)
    val matcher: Matcher? = patterns.pattern?.matcher(text)

    if (matcher != null) {
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (start == end) continue

            // Determine which group matched
            for (groupName in patterns.groupNames) {
                if (matcher.group(groupName) != null) {
                    val style = when {
                        groupName.startsWith("comment") -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                        groupName.startsWith("string") -> SpanStyle(color = colors.string)
                        groupName.startsWith("keyword") -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.SemiBold)
                        groupName.startsWith("type") -> SpanStyle(color = colors.type, fontWeight = FontWeight.Medium)
                        groupName.startsWith("number") -> SpanStyle(color = colors.number)
                        groupName.startsWith("annotation") -> SpanStyle(color = colors.annotation)
                        groupName.startsWith("tag") -> SpanStyle(color = colors.tag, fontWeight = FontWeight.Bold)
                        groupName.startsWith("attribute") -> SpanStyle(color = colors.attribute)
                        groupName.startsWith("operator") -> SpanStyle(color = colors.operator)
                        else -> null
                    }
                    if (style != null) {
                        builder.addStyle(style, start, end)
                    }
                    break
                }
            }
        }
    }

    applySearchHighlights(builder, text, searchQuery, searchCaseSensitive, activeMatchIndex, colors)
    return builder.toAnnotatedString()
}

private fun applySearchHighlights(
    builder: AnnotatedString.Builder,
    text: String,
    query: String,
    caseSensitive: Boolean,
    activeMatchIndex: Int,
    colors: SyntaxColors,
) {
    if (query.isEmpty()) return

    val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    val pattern = runCatching { Pattern.compile(Pattern.quote(query), flags) }.getOrNull() ?: return
    val matcher = pattern.matcher(text)
    var matchIndex = 0

    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val isActive = matchIndex == activeMatchIndex

        val style = if (isActive) {
            SpanStyle(
                background = colors.activeSearchMatch,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
            )
        } else {
            SpanStyle(
                background = colors.searchMatch,
            )
        }

        builder.addStyle(style, start, end)
        matchIndex++
    }
}

/**
 * VisualTransformation that applies syntax and search match highlighting.
 */
class SyntaxHighlightTransformation(
    private val language: SyntaxLanguage,
    private val colors: SyntaxColors,
    private val searchQuery: String = "",
    private val searchCaseSensitive: Boolean = false,
    private val activeMatchIndex: Int = -1,
) : VisualTransformation {

    // Simple cache to avoid recomputing if text and parameters have not changed
    private var lastRawText: String? = null
    private var lastTransformed: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val cached = lastTransformed
        if (raw == lastRawText && cached != null) {
            return cached
        }

        val highlighted = highlightSyntax(
            text = raw,
            language = language,
            colors = colors,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            activeMatchIndex = activeMatchIndex,
        )

        val result = TransformedText(highlighted, OffsetMapping.Identity)
        lastRawText = raw
        lastTransformed = result
        return result
    }
}

private val lineCache = java.util.Collections.synchronizedMap(
    object : java.util.LinkedHashMap<String, AnnotatedString>(400, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?): Boolean {
            return size > 400
        }
    }
)

/**
 * Highlights a single line of text for line-based viewing (LazyColumn).
 */
fun highlightLine(
    line: String,
    language: SyntaxLanguage,
    colors: SyntaxColors,
    searchQuery: String = "",
    searchCaseSensitive: Boolean = false,
    isActiveMatchLine: Boolean = false,
): AnnotatedString {
    if (line.isEmpty()) return AnnotatedString(" ")

    // If active search query, compute directly with search highlights
    if (searchQuery.isNotEmpty()) {
        return highlightSyntax(
            text = line,
            language = language,
            colors = colors,
            searchQuery = searchQuery,
            searchCaseSensitive = searchCaseSensitive,
            activeMatchIndex = if (isActiveMatchLine) 0 else -1,
        )
    }

    val cacheKey = "${language.name}_${colors.keyword.value}_$line"
    lineCache[cacheKey]?.let { return it }

    val highlighted = highlightSyntax(
        text = line,
        language = language,
        colors = colors,
    )
    lineCache[cacheKey] = highlighted
    return highlighted
}
