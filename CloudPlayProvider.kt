package com.amaze

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.util.UUID

class CloudPlay(override var mainUrl: String, override var name: String) : MainAPI() {
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiHeaders = mapOf(
        "Connection" to "Keep-Alive",
        "User-Agent" to "okhttp/4.12.0",
        "X-Package" to base64Decode("Y29tLmNsb3VkcGxheS5hcHA=")
    )

    val stream = CloudPlayStream(
        name = name,
        url = mainUrl,
        logo = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val shows = fetchChannels(stream.url, stream.logo)

        return newHomePageResponse(arrayListOf(
            HomePageList(
                stream.name ?: "Unknown",
                shows,
                isHorizontalImages = true
            )
        ), hasNext = false)
    }

    private suspend fun fetchChannels(url: String, fallbackLogo: String?): List<SearchResponse> {
        val shows = mutableListOf<SearchResponse>()
        val isHost = url.contains(base64Decode("aG9zdC5jbG91ZHBsYXkubWU="))
        val headers = if (isHost) apiHeaders else emptyMap()

        val resText = app.get(url, headers = headers).text
        if (resText.isBlank()) return shows

        try {
            // Try parsing the list of channels
            val channels = parseJson<List<CloudPlayChannel>>(resText)
            if (channels.isNotEmpty() && (channels[0].m3u8_url != null || channels[0].mpd_url != null)) {
                return channels.map { channel ->
                    val channelName = channel.name ?: "Unknown"
                    val posterUrl = channel.logo ?: fallbackLogo ?: ""
                    val data = channel.toJson()

                    newLiveSearchResponse(channelName, data, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                }
            }
        } catch (_: Exception) {
        }

        return shows
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchChannels(
            stream.url,
            stream.logo
        ).filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<CloudPlayChannel>(url)
        val title = data.name ?: "Unknown"
        val poster = data.logo ?: ""

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = data.group
        }
    }

    private fun String.hexToBase64Url(): String {
        val normalizedHex = trim().replace("-", "")
        if (normalizedHex.isEmpty() || normalizedHex.length % 2 != 0 || !normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))) {
            return this
        }
        return try {
            val bytes = normalizedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            this
        }
    }

    private suspend fun getMpdStream(url: String, customHeaders: Map<String, String>?): String {
        return app.get(url, headers = customHeaders ?: emptyMap()).text
    }

    private suspend fun getDRMKeysFromLicenseServer(url: String, kid: String): String {
        val userAgent = "Dalvik/2.1.0 (Linux; U; Android)"

        val responseString = app.post(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Content-Type" to "application/json;charset=UTF-8"
            ),
            json = mapOf(
                "kids" to listOf(kid),
                "type" to "temporary"
            )
        ).text

        return try {
            val jsonResponse = parseJson<Map<String, Any>>(responseString)

            @Suppress("UNCHECKED_CAST")
            val keys = jsonResponse["keys"] as? List<Map<String, String>> ?: return ""

            keys.firstOrNull { it["kid"] == kid }
            ?.get("k")
            ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = parseJson<CloudPlayChannel>(data)

        if (channel.mpd_url != null) {
            val licenseUrl = channel.license_url ?: ""
            var keyStr = ""
            var kidStr = ""
            if (licenseUrl.contains("keyid=") && licenseUrl.contains("key=")) {
                kidStr = Regex("keyid=([^&]+)").find(licenseUrl)?.groupValues?.get(1)?.hexToBase64Url() ?: ""
                keyStr = Regex("key=([^&]+)").find(licenseUrl)?.groupValues?.get(1)?.hexToBase64Url() ?: ""
            } else if (licenseUrl.isNotEmpty()) {
                val mpdStr = getMpdStream(channel.mpd_url, channel.headers)
                val regex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                val matchResult = regex.find(mpdStr)
                val drmKid = matchResult?.groupValues?.get(1) ?: UUID.randomUUID().toString()
                kidStr = drmKid.hexToBase64Url()
                keyStr = getDRMKeysFromLicenseServer(licenseUrl, kidStr)
            }

            callback.invoke(
                newDrmExtractorLink(
                    this.name,
                    channel.name ?: "DASH",
                    channel.mpd_url,
                    INFER_TYPE,
                    if (kidStr.isNotEmpty() && keyStr.isNotEmpty()) CLEARKEY_UUID else UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                ) {
                    if (channel.headers != null) {
                        this.headers = channel.headers
                    }
                    if (kidStr.isNotEmpty() && keyStr.isNotEmpty()) {
                        this.kid = kidStr
                        this.key = keyStr
                    } else if (licenseUrl.isNotEmpty()) {
                        this.licenseUrl = licenseUrl
                    }
                }
            )
        } else if (channel.m3u8_url != null) {
            val isTs = channel.m3u8_url.contains(".ts", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    this.name,
                    channel.name ?: "HLS",
                    channel.m3u8_url,
                    if (isTs) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    if (channel.headers != null) {
                        this.headers = channel.headers
                    }
                    channel.headers?.amap { (key, value) ->
                        if (key.equals("referer", ignoreCase = true)) {
                            this.referer = value
                        }
                    }
                }
            )
        }

        return true
    }

    data class CloudPlayStream(
        val name: String?,
        val url: String,
        val logo: String?
    )

    data class CloudPlayChannel(
        val type: String?,
        val id: String?,
        val name: String?,
        val group: String?,
        val logo: String?,
        val user_agent: String?,
        val m3u8_url: String?,
        val mpd_url: String?,
        val license_url: String?,
        val headers: Map<String, String>?
    )
}
