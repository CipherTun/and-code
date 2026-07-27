package com.yugahashimoto.andcode.core.api

import com.yugahashimoto.andcode.feature.workspace.GitHubReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubApiClient(private val token: String?) {
    private val client: OkHttpClient = OkHttpClient()
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun getPullRequests(
        owner: String,
        repo: String,
        branch: String,
    ): List<GitHubReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "https://api.github.com/repos/$owner/$repo/pulls".toHttpUrl().newBuilder()
                            .addQueryParameter("head", "$owner:$branch")
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GitHubPull>>(body)
                    items.map { pull ->
                        GitHubReference(
                            type = "PR",
                            number = pull.number,
                            title = pull.title,
                            url = pull.htmlUrl,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun searchIssues(
        owner: String,
        repo: String,
        query: String,
    ): List<GitHubReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "https://api.github.com/search/issues".toHttpUrl().newBuilder()
                            .addQueryParameter("q", "repo:$owner/$repo $query")
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val result = json.decodeFromString<GitHubSearchResult>(body)
                    result.items.map { item ->
                        GitHubReference(
                            type = "Issue",
                            number = item.number,
                            title = item.title,
                            url = item.htmlUrl,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .apply {
                token?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

    @Serializable
    private data class GitHubPull(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class GitHubSearchResult(
        val items: List<GitHubIssue> = emptyList(),
    )

    @Serializable
    private data class GitHubIssue(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )
}
