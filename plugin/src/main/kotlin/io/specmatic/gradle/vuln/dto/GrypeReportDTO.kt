package io.specmatic.gradle.vuln.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class GrypeReport(
    @field:JsonProperty("matches") val matches: List<GrypeMatch> = emptyList(),
    @field:JsonProperty("source") val source: GrypeSource? = null,
    @field:JsonProperty("distro") val distro: GrypeDistro? = null,
    @field:JsonProperty("descriptor") val descriptor: GrypeDescriptor? = null,
    @field:JsonProperty("db") val db: GrypeDatabase? = null,
    @field:JsonProperty("timestamp") val timestamp: String? = null,
)

data class GrypeMatch(
    @field:JsonProperty("vulnerability") val vulnerability: GrypeVulnerability? = null,
    @field:JsonProperty("relatedVulnerabilities") val relatedVulnerabilities: List<GrypeRelatedVulnerability> = emptyList(),
    @field:JsonProperty("matchDetails") val matchDetails: List<GrypeMatchDetail> = emptyList(),
    @field:JsonProperty("artifact") val artifact: GrypeArtifact? = null,
)

data class GrypeRelatedVulnerability(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("severity") val severity: String? = null,
)

data class GrypeVulnerability(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("dataSource") val dataSource: String? = null,
    @field:JsonProperty("namespace") val namespace: String? = null,
    @field:JsonProperty("severity") val severity: String? = null,
    @field:JsonProperty("urls") val urls: List<String> = emptyList(),
    @field:JsonProperty("description") val description: String? = null,
    @field:JsonProperty("cvss") val cvss: List<GrypeCvssEntry> = emptyList(),
    @field:JsonProperty("epss") val epss: List<GrypeEpss> = emptyList(),
    @field:JsonProperty("cwes") val cwes: List<GrypeCwe> = emptyList(),
    @field:JsonProperty("fix") val fix: GrypeFix? = null,
    @field:JsonProperty("advisories") val advisories: List<GrypeAdvisory> = emptyList(),
    @field:JsonProperty("risk") val risk: Double? = null,
)

data class GrypeCvssEntry(
    @field:JsonProperty("source") val source: String? = null,
    @field:JsonProperty("type") val type: String? = null,
    @field:JsonProperty("version") val version: String? = null,
    @field:JsonProperty("vector") val vector: String? = null,
    @field:JsonProperty("metrics") val metrics: GrypeCvssMetrics? = null,
    @field:JsonProperty("vendorMetadata") val vendorMetadata: Map<String, Any> = emptyMap(),
)

data class GrypeCvssMetrics(
    @field:JsonProperty("baseScore") val baseScore: Double? = null,
    @field:JsonProperty("exploitabilityScore") val exploitabilityScore: Double? = null,
    @field:JsonProperty("impactScore") val impactScore: Double? = null,
)

data class GrypeEpss(
    @field:JsonProperty("cve") val cve: String? = null,
    @field:JsonProperty("epss") val epss: Double? = null,
    @field:JsonProperty("percentile") val percentile: Double? = null,
    @field:JsonProperty("date") val date: String? = null,
)

data class GrypeCwe(
    @field:JsonProperty("cve") val cve: String? = null,
    @field:JsonProperty("cwe") val cwe: String? = null,
    @field:JsonProperty("source") val source: String? = null,
    @field:JsonProperty("type") val type: String? = null,
)

data class GrypeFix(
    @field:JsonProperty("versions") val versions: List<String> = emptyList(),
    @field:JsonProperty("state") val state: String? = null,
)

data class GrypeAdvisory(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("link") val link: String? = null,
)

data class GrypeMatchDetail(
    @field:JsonProperty("type") val type: String? = null,
    @field:JsonProperty("matcher") val matcher: String? = null,
    @field:JsonProperty("searchedBy") val searchedBy: GrypeSearchedBy? = null,
    @field:JsonProperty("found") val found: GrypeFound? = null,
)

data class GrypeSearchedBy(
    @field:JsonProperty("namespace") val namespace: String? = null,
    @field:JsonProperty("cpes") val cpes: List<String> = emptyList(),
    @field:JsonProperty("package") val packageInfo: GrypePackageRef? = null,
)

data class GrypePackageRef(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("version") val version: String? = null,
)

data class GrypeFound(
    @field:JsonProperty("vulnerabilityID") val vulnerabilityID: String? = null,
    @field:JsonProperty("versionConstraint") val versionConstraint: String? = null,
    @field:JsonProperty("cpes") val cpes: List<String> = emptyList(),
)

data class GrypeArtifact(
    @field:JsonProperty("id") val id: String? = null,
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("version") val version: String? = null,
    @field:JsonProperty("type") val type: String? = null,
    @field:JsonProperty("locations") val locations: List<GrypeArtifactLocation>? = emptyList(),
    @field:JsonProperty("language") val language: String? = null,
    @field:JsonProperty("licenses") val licenses: List<String> = emptyList(),
    @field:JsonProperty("cpes") val cpes: List<String> = emptyList(),
    @field:JsonProperty("purl") val purl: String? = null,
    @field:JsonProperty("upstreams") val upstreams: List<GrypeUpstream> = emptyList(),
    @field:JsonProperty("metadataType") val metadataType: String? = null,
    @field:JsonProperty("metadata") val metadata: GrypeArtifactMetadata? = null,
)

data class GrypeArtifactLocation(
    @field:JsonProperty("path") val path: String? = null,
    @field:JsonProperty("layerID") val layerID: String? = null,
    @field:JsonProperty("accessPath") val accessPath: String? = null,
    @field:JsonProperty("annotations") val annotations: Map<String, String> = emptyMap(),
)

data class GrypeUpstream(
    @field:JsonProperty("name") val name: String? = null,
)

data class GrypeArtifactMetadata(
    @field:JsonProperty("files") val files: List<GrypeArtifactFile> = emptyList(),
)

data class GrypeArtifactFile(
    @field:JsonProperty("path") val path: String? = null,
)

data class GrypeSource(
    @field:JsonProperty("type") val type: String? = null,
    @field:JsonProperty("target") val target: Any? = null,
)

data class GrypeSourceTarget(
    @field:JsonProperty("userInput") val userInput: String? = null,
    @field:JsonProperty("imageID") val imageID: String? = null,
    @field:JsonProperty("manifestDigest") val manifestDigest: String? = null,
    @field:JsonProperty("mediaType") val mediaType: String? = null,
    @field:JsonProperty("tags") val tags: List<String> = emptyList(),
    @field:JsonProperty("imageSize") val imageSize: Long? = null,
    @field:JsonProperty("layers") val layers: List<GrypeLayer> = emptyList(),
    @field:JsonProperty("manifest") val manifest: String? = null,
    @field:JsonProperty("config") val config: String? = null,
    @field:JsonProperty("repoDigests") val repoDigests: List<String> = emptyList(),
    @field:JsonProperty("architecture") val architecture: String? = null,
    @field:JsonProperty("os") val os: String? = null,
)

data class GrypeLayer(
    @field:JsonProperty("mediaType") val mediaType: String? = null,
    @field:JsonProperty("digest") val digest: String? = null,
    @field:JsonProperty("size") val size: Long? = null,
)

data class GrypeDistro(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("version") val version: String? = null,
    @field:JsonProperty("idLike") val idLike: List<String>? = emptyList(),
)

data class GrypeDescriptor(
    @field:JsonProperty("name") val name: String? = null,
    @field:JsonProperty("version") val version: String? = null,
    @field:JsonProperty("configuration") val configuration: GrypeConfiguration? = null,
    @field:JsonProperty("db") val db: GrypeDescriptorDb? = null,
    @field:JsonProperty("timestamp") val timestamp: String? = null,
)

data class GrypeConfiguration(
    @field:JsonProperty("output") val output: List<String> = emptyList(),
    @field:JsonProperty("only-fixed") val onlyFixed: Boolean? = null,
    @field:JsonProperty("only-notfixed") val onlyNotFixed: Boolean? = null,
    @field:JsonProperty("fail-on-severity") val failOnSeverity: String? = null,
    @field:JsonProperty("timestamp") val timestamp: Boolean? = null,
)

data class GrypeDescriptorDb(
    @field:JsonProperty("status") val status: GrypeDbStatus? = null,
    @field:JsonProperty("providers") val providers: Map<String, GrypeDbProviderStatus> = emptyMap(),
)

data class GrypeDatabase(
    @field:JsonProperty("status") val status: GrypeDbStatus? = null,
    @field:JsonProperty("providers") val providers: Map<String, GrypeDbProviderStatus> = emptyMap(),
)

data class GrypeDbStatus(
    @field:JsonProperty("schemaVersion") val schemaVersion: String? = null,
    @field:JsonProperty("from") val from: String? = null,
    @field:JsonProperty("built") val built: String? = null,
    @field:JsonProperty("path") val path: String? = null,
    @field:JsonProperty("valid") val valid: Boolean? = null,
)

data class GrypeDbProviderStatus(
    @field:JsonProperty("captured") val captured: String? = null,
    @field:JsonProperty("input") val input: String? = null,
)
