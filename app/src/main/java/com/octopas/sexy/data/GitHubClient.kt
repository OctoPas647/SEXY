package com.octopas.sexy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable data class GhUser(val login: String, val name: String? = null)
@Serializable data class GhOwner(val login: String)
@Serializable data class GhRepo(
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val description: String? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val owner: GhOwner,
)
@Serializable data class GhBranch(val name: String)
@Serializable data class GhTreeEntry(val path: String, val mode: String, val type: String, val sha: String, val size: Long? = null)
@Serializable data class GhTree(val sha: String, val tree: List<GhTreeEntry>, val truncated: Boolean = false)
@Serializable data class GhPull(val number: Int, @SerialName("html_url") val htmlUrl: String, val draft: Boolean = false)

class GitHubException(message: String, val code: Int = 0) : Exception(message)

/** Minimal GitHub REST client: list repos, read files, commit changes to a new branch, open a PR. */
class GitHubClient(
    private val token: String,
    private val api: String = "https://api.github.com",
    private val http: OkHttpClient = defaultHttp,
) {
    private fun url(segments: String, vararg query: Pair<String, String>): HttpUrl =
        api.toHttpUrl().newBuilder().addPathSegments(segments).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun base(url: HttpUrl, accept: String = "application/vnd.github+json") = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")

    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GitHubException(errorText(r.code, body), r.code)
            body
        }
    }

    private suspend fun get(url: HttpUrl): String = send(base(url).get().build())

    private suspend fun post(url: HttpUrl, body: JsonObject): JsonObject {
        val text = send(base(url).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return appJson.parseToJsonElement(text) as JsonObject
    }

    suspend fun me(): GhUser = appJson.decodeFromString(get(url("user")))

    suspend fun repos(): List<GhRepo> {
        val all = mutableListOf<GhRepo>()
        for (page in 1..5) {
            val batch: List<GhRepo> = appJson.decodeFromString(
                get(url("user/repos", "per_page" to "100", "page" to "$page", "sort" to "pushed",
                    "affiliation" to "owner,collaborator,organization_member"))
            )
            all += batch
            if (batch.size < 100) break
        }
        return all
    }

    suspend fun branches(owner: String, repo: String): List<GhBranch> =
        appJson.decodeFromString(get(url("repos/$owner/$repo/branches", "per_page" to "100")))

    suspend fun headCommit(owner: String, repo: String, branch: String): String {
        val o = appJson.parseToJsonElement(get(url("repos/$owner/$repo/git/ref/heads/$branch"))) as JsonObject
        return ((o["object"] as JsonObject)["sha"] as JsonPrimitive).content
    }

    private suspend fun commitTree(owner: String, repo: String, commitSha: String): String {
        val o = appJson.parseToJsonElement(get(url("repos/$owner/$repo/git/commits/$commitSha"))) as JsonObject
        return ((o["tree"] as JsonObject)["sha"] as JsonPrimitive).content
    }

    suspend fun tree(owner: String, repo: String, branch: String): GhTree {
        val treeSha = commitTree(owner, repo, headCommit(owner, repo, branch))
        return appJson.decodeFromString(get(url("repos/$owner/$repo/git/trees/$treeSha", "recursive" to "1")))
    }

    suspend fun fileText(owner: String, repo: String, path: String, ref: String): String =
        send(base(url("repos/$owner/$repo/contents/$path", "ref" to ref), "application/vnd.github.raw+json").get().build())

    /**
     * Creates ONE commit with all [edits] on a NEW branch (base branch untouched) and opens a pull request.
     * Tries a draft PR first; free private repos don't support drafts, so it falls back to a normal PR.
     */
    suspend fun commitAndOpenPr(
        owner: String,
        repo: String,
        baseBranch: String,
        newBranch: String,
        commitMessage: String,
        prTitle: String,
        prBody: String,
        edits: List<FileEdit>,
        modes: Map<String, String> = emptyMap(),
    ): GhPull {
        require(edits.isNotEmpty()) { "Nothing to commit" }
        val baseCommit = headCommit(owner, repo, baseBranch)
        val baseTree = commitTree(owner, repo, baseCommit)

        val treeBody = buildJsonObject {
            put("base_tree", baseTree)
            putJsonArray("tree") {
                edits.forEach { e ->
                    addJsonObject {
                        put("path", e.path)
                        put("mode", modes[e.path]?.takeIf { it.startsWith("100") } ?: "100644")
                        put("type", "blob")
                        if (e.newContent == null) put("sha", JsonNull) else put("content", e.newContent)
                    }
                }
            }
        }
        val newTree = post(url("repos/$owner/$repo/git/trees"), treeBody).str("sha")

        val commit = post(url("repos/$owner/$repo/git/commits"), buildJsonObject {
            put("message", commitMessage)
            put("tree", newTree)
            putJsonArray("parents") { add(JsonPrimitive(baseCommit)) }
        }).str("sha")

        try {
            post(url("repos/$owner/$repo/git/refs"), buildJsonObject {
                put("ref", "refs/heads/$newBranch")
                put("sha", commit)
            })
        } catch (e: GitHubException) {
            if (e.code == 422) throw GitHubException("Branch '$newBranch' already exists. Change the branch name.", 422)
            throw e
        }

        fun prJson(draft: Boolean) = buildJsonObject {
            put("title", prTitle)
            put("head", newBranch)
            put("base", baseBranch)
            put("body", prBody)
            put("draft", draft)
        }
        val pr = try {
            post(url("repos/$owner/$repo/pulls"), prJson(true))
        } catch (e: GitHubException) {
            if (e.code == 422 || e.code == 403) post(url("repos/$owner/$repo/pulls"), prJson(false)) else throw e
        }
        return appJson.decodeFromJsonElement(GhPull.serializer(), pr)
    }

    private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun errorText(code: Int, body: String): String {
            val o = runCatching { appJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val msg = (o?.get("message") as? JsonPrimitive)?.contentOrNull
            val details = (o?.get("errors") as? JsonArray)?.joinToString("; ") { el ->
                ((el as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: el.toString()
            }
            return when (code) {
                401 -> "GitHub token is invalid or expired. Create a new one in Settings."
                403 -> "GitHub refused: ${msg ?: "forbidden"}. The token needs Contents + Pull requests: Read and write for this repo."
                404 -> "Not found - the repo/branch doesn't exist or the token can't access it."
                else -> "GitHub error $code: ${msg ?: body.take(200)}" + (details?.let { " ($it)" } ?: "")
            }
        }
    }
}
