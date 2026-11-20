package com.marktext.jetbrains.editor

private const val WEB_SCRIPT_URL = "http://marktext.intellij/"

internal object WebBundle {
    fun html(): String {
        val script = resource("/web/marktext-web.js")
            .replace("</script", "<\\/script", ignoreCase = true)
        val style = resource("/web/marktext-web.css")
            .replace("</style", "<\\/style", ignoreCase = true)

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>$style</style>
            </head>
            <body>
              <main id="editor" class="muya-starting">MarkText editor is starting. If this message remains, check the IDE log for JCEF errors.</main>
              <script><!-- MARKTEXT_HOST_BRIDGE --></script>
              <script>$script</script>
            </body>
            </html>
        """.trimIndent()
    }

    fun scriptUrl(): String = WEB_SCRIPT_URL

    fun baseUrl(documentPath: String): String = java.nio.file.Paths.get(documentPath).parent.resolve("marktext-editor.html").toUri().toString()

    private fun resource(path: String): String {
        val stream = WebBundle::class.java.getResourceAsStream(path)
            ?: error("Missing bundled web resource $path. Run pnpm --filter @marktext/jetbrains-web build first.")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
