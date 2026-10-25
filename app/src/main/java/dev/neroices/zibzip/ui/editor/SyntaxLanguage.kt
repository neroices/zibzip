package dev.neroices.zibzip.ui.editor

import java.util.Locale

enum class SyntaxLanguage(val displayName: String) {
    Kotlin("Kotlin"),
    Java("Java"),
    Python("Python"),
    JavaScript("JavaScript"),
    TypeScript("TypeScript"),
    Rust("Rust"),
    C("C"),
    Cpp("C++"),
    Go("Go"),
    Shell("Shell Script"),
    Html("HTML"),
    Xml("XML"),
    Css("CSS"),
    Json("JSON"),
    Yaml("YAML"),
    Toml("TOML"),
    Sql("SQL"),
    Markdown("Markdown"),
    Properties("Properties / INI"),
    Lrc("Lyrics (LRC)"),
    PlainText("Plain Text");

    companion object {
        fun detect(fileName: String): SyntaxLanguage {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val baseName = fileName.substringAfterLast('/').lowercase(Locale.ROOT)

            // Exact filename checks
            when (baseName) {
                "dockerfile" -> return Shell
                "makefile", "gnumakefile" -> return Shell
                "cmakelists.txt" -> return Cpp
                ".gitignore", ".gitattributes", ".gitmodules" -> return Properties
                ".env", ".env.local", ".env.production", ".env.development" -> return Properties
                ".bashrc", ".zshrc", ".profile", ".bash_profile" -> return Shell
            }

            // Extension checks
            return when (ext) {
                "kt", "kts" -> Kotlin
                "java" -> Java
                "py", "pyw" -> Python
                "js", "jsx", "mjs", "cjs" -> JavaScript
                "ts", "tsx" -> TypeScript
                "rs" -> Rust
                "c", "h" -> C
                "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> Cpp
                "go" -> Go
                "sh", "bash", "zsh", "fish" -> Shell
                "html", "htm", "xhtml" -> Html
                "xml", "svg", "plist", "xsd", "xsl", "rss", "atom" -> Xml
                "css", "scss", "sass", "less" -> Css
                "json" -> Json
                "yml", "yaml" -> Yaml
                "toml" -> Toml
                "sql" -> Sql
                "md", "markdown" -> Markdown
                "ini", "conf", "cfg", "properties", "gradle" -> Properties
                "lrc" -> Lrc
                else -> PlainText
            }
        }
    }
}
