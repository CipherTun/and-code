package com.opencode.android.data.connection

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ConnectionProfile(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("name") val name: String,
    @SerialName("baseUrl") val baseUrl: String,
    @SerialName("username") val username: String = "opencode",
    @SerialName("password") val password: String? = null,
    @SerialName("allowInsecureLan") val allowInsecureLan: Boolean = false
) {
    override fun toString(): String =
        "ConnectionProfile(id=$id, name=$name, baseUrl=$baseUrl, username=$username, password=<redacted>, allowInsecureLan=$allowInsecureLan)"
}

object ConnectionProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun encode(profiles: List<ConnectionProfile>): String = json.encodeToString(profiles)

    fun decode(jsonString: String): List<ConnectionProfile> {
        if (jsonString.isBlank()) return emptyList()
        return json.decodeFromString<List<ConnectionProfile>>(jsonString)
    }
}
