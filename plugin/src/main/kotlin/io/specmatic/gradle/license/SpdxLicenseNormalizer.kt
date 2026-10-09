package io.specmatic.gradle.license

import com.github.jk1.license.License
import com.github.jk1.license.filter.SpdxLicenseBundleNormalizer
import groovy.lang.Closure

internal class SpdxLicenseNormalizer : SpdxLicenseBundleNormalizer() {
    fun canonicalUrl(licenseId: String): String? {
        init()
        if (!licenseId.matches(Regex("[A-Za-z0-9.-]+"))) return null
        return normalizerConfig.bundles
            .firstOrNull { it.bundleName == licenseId }
            ?.let { "https://spdx.org/licenses/$licenseId.html" }
    }

    fun normalize(name: String?, url: String?, text: String?): List<License> {
        init()
        val matchers = makeNormalizerTransformationRuleMatchers(normalizerConfig.transformationRules)
        val matches = transformationRulesFor(matchers, "", name, url, text?.let { licenseText ->
            object : Closure<String>(this) {
                fun doCall(): String = licenseText
            }
        })
        return matches.map { rule -> normalizeSingleLicense(rule, License(name, url)) }
    }
}
