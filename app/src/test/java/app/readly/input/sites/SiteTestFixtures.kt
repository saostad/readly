package app.readly.input.sites

/**
 * Small representative fixtures for the shared site-engine tests:
 * realistic entry texts plus in-memory import helpers. Never the full
 * upstream code; the real archive is exercised only by the gated
 * [SiteBundleReportTest] with an explicit local path (no network).
 */
internal object SiteTestFixtures {
    const val VERSION = "4.4.7.4"
    const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    const val URL = SiteCatalog.OFFICIAL_UPDATE_URL
    const val LICENSE = "MIT License\n\nCopyright (c) 2020, magnolia1234\n\nPermission note.\n"

    const val NYT_UA = "Mozilla/5.0 (compatible; Google-InspectionTool/1.0)"
    const val NYT_BLOCK =
        "(\\.nytimes\\.com\\/(meter\\.js|svc\\/onsite-messaging\\/query)|" +
            "mwcm\\.nyt\\.com\\/.+\\.js|cooking\\.nytimes\\.com\\/api\\/.+\\/access)"
    const val GOOGLEBOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

    /** One `sites.js` site entry from a raw field body. */
    fun entry(key: String, body: String): String = "  \"$key\": {\n$body\n  }"

    fun sitesJs(vararg entries: String, prefix: String = "", suffix: String = ""): String =
        prefix + "var defaultSites = {\n" + entries.joinToString(",\n") + "\n};\n" + suffix

    fun manifestJson(versionFragment: String = "\"$VERSION\""): String =
        "{\"name\": \"Bypass Paywalls Clean\", \"version\": $versionFragment}"

    fun import(vararg entries: String, prefix: String = "", suffix: String = ""): SiteCatalog =
        SitesImporter.importText(sitesJs(*entries, prefix = prefix, suffix = suffix), manifestJson(), LICENSE, SHA, URL)

    /** The real upstream NYT entry text (transcribed fields, current snapshot). */
    fun nytEntry(): String = entry(
        "The New York Times",
        "    domain: \"nytimes.com\",\n" +
            "    allow_cookies: 1,\n" +
            "    block_regex: /$NYT_BLOCK/,\n" +
            "    useragent_custom: \"$NYT_UA\"",
    )

    fun ruleFor(catalog: SiteCatalog, host: String): SiteRule =
        catalog.findRule(host) ?: throw AssertionError("no rule matches $host")

    const val TOP = "bypass-paywalls-chrome-clean-master/"

    /**
     * In-memory update ZIP: `sites.js` + `manifest.json` + `LICENSE` under
     * [top] (the importer requires the license next to `sites.js`).
     * [license] null omits it for negative tests.
     */
    fun updateZip(
        sitesJsText: String,
        version: String = VERSION,
        license: String? = LICENSE,
        top: String = TOP,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun add(name: String, data: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            add("${top}sites.js", sitesJsText.toByteArray(Charsets.UTF_8))
            add(
                "${top}manifest.json",
                "{\"name\": \"Bypass Paywalls Clean\", \"version\": \"$version\"}"
                    .toByteArray(Charsets.UTF_8),
            )
            if (license != null) add("${top}LICENSE", license.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /** Standard two-rule catalog text: NYT plus one googlebot neighbor. */
    fun twoRuleSitesJs(nytUa: String = NYT_UA): String = sitesJs(
        entry(
            "The New York Times",
            "    domain: \"nytimes.com\",\n" +
                "    allow_cookies: 1,\n" +
                "    useragent_custom: \"$nytUa\"",
        ),
        entry("Neighbor", "    domain: \"neighbor.example.com\",\n    useragent: \"googlebot\""),
    )

    fun catalogJson(vararg entries: String): String =
        SiteCatalogJson.encode(import(*entries))
}
