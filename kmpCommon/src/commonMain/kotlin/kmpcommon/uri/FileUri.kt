package kmpcommon.uri

/**
 * Build a `file://` [Uri] from an absolute filesystem [path], percent-encoding it so a name with a
 * space / `#` / `%` / non-ASCII round-trips and never mis-parses (a raw `#` would otherwise start a
 * fragment). Always use this instead of string-concatenating `"file://" + path`.
 */
fun fileUri(path: String): Uri =
    UriBuilder()
        .scheme("file")
        .authority("")
        .path(if (path.startsWith('/')) path else "/$path")
        .build()

/**
 * The decoded filesystem path of this uri (percent-decoded). Use instead of `removePrefix("file://")`,
 * which leaves `%20` etc. in the path.
 */
fun Uri.filePath(): String = path.toString()

/**
 * The canonical form for IDENTITY comparison — normalized percent-encoding + a lowercased scheme — so
 * two uris that denote the same resource (e.g. `My Song.mp3` vs `My%20Song.mp3`) compare equal. Store
 * and match uris through [canonicalString], never the raw string.
 */
fun Uri.canonical(): Uri = UriBuilder(this).build()

fun Uri.canonicalString(): String = canonical().toString()
