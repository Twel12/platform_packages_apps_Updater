package app.seamlessupdate.client

import java.security.GeneralSecurityException

data class ServerMetadata(
    val targetIncremental: String,
    val targetBuildDate: Long,
    val targetDevice: String,
    val targetChannel: String
)

data class PackageMetadata(
    val timestamp: Long,
    val serialno: String?,
    val device: String?,
    val type: String?,
    val sourceIncremental: String?,
    val sourceFingerprint: String?,
    val streamingPropertyFiles: List<String>
)

object UpdateMetadataParser {
    fun parseServerMetadata(metadataLine: String): ServerMetadata {
        val fields = metadataLine.trim().split(Regex("\\s+"))
        if (fields.size != 4) {
            throw GeneralSecurityException("unexpected server metadata format")
        }
        val targetBuildDate = fields[1].toLongOrNull()
            ?: throw GeneralSecurityException("invalid target build date")
        return ServerMetadata(
            targetIncremental = fields[0],
            targetBuildDate = targetBuildDate,
            targetDevice = fields[2],
            targetChannel = fields[3]
        )
    }

    fun parsePackageMetadata(lines: Sequence<String>): PackageMetadata {
        var device: String? = null
        var serialno: String? = null
        var type: String? = null
        var sourceIncremental: String? = null
        var sourceFingerprint: String? = null
        var streamingPropertyFiles: List<String> = emptyList()
        var timestamp = 0L

        for (line in lines) {
            val pair = line.split("=", limit = 2)
            if (pair.size != 2) {
                continue
            }
            val key = pair[0]
            val value = pair[1]
            when (key) {
                "post-timestamp" -> {
                    timestamp = value.toLongOrNull()
                        ?: throw GeneralSecurityException("invalid post-timestamp")
                }
                "serialno" -> serialno = value
                "pre-device" -> device = value
                "ota-type" -> type = value
                "ota-streaming-property-files" -> {
                    streamingPropertyFiles = value.trim()
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
                "pre-build-incremental" -> sourceIncremental = value
                "pre-build" -> sourceFingerprint = value
            }
        }

        return PackageMetadata(
            timestamp = timestamp,
            serialno = serialno,
            device = device,
            type = type,
            sourceIncremental = sourceIncremental,
            sourceFingerprint = sourceFingerprint,
            streamingPropertyFiles = streamingPropertyFiles
        )
    }

    fun payloadOffsetFromStreamingProperties(streamingProperties: List<String>): Long {
        for (streamingPropertyFile in streamingProperties) {
            val properties = streamingPropertyFile.split(":", limit = 2)
            if (properties.size != 2) {
                continue
            }
            if (properties[0] == "payload.bin") {
                val offset = properties[1].toLongOrNull()
                    ?: throw GeneralSecurityException("invalid payload offset")
                return offset
            }
        }
        throw GeneralSecurityException("payload.bin missing from streaming properties")
    }
}
