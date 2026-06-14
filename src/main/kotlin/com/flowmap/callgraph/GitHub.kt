package com.flowmap.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * Merged pull-request log via the `gh` CLI. Used by [Impact] to analyze change
 * impact per PR (instead of per raw commit). Each PR is reduced to its merge
 * commit, whose first-parent diff is the PR's net change set — fed into the same
 * line→node machinery as before.
 *
 * Degrades gracefully: if `gh` is missing, unauthenticated, offline, or the repo
 * is not on GitHub, [mergedPulls] returns an empty list and impact simply reports
 * no PRs for that project.
 */
object GitHub {
    private val mapper = ObjectMapper()

    data class Pr(
        val number: Int,
        val title: String,
        val author: String?,
        val mergedAt: String?,
        val mergeCommit: String?,   // merge/squash commit oid; null if unavailable
    )

    /**
     * Newest-first merged PRs targeting [base] (all bases if null), capped at [limit].
     * Returns null when `gh` could not run (missing / unauthenticated / offline / not
     * a GitHub repo) — distinct from an empty list, which means gh ran fine and the
     * base simply has no merged PRs. Callers use that to tell "unknown" from "none".
     */
    fun mergedPulls(repo: File, base: String?, limit: Int): List<Pr>? {
        val args = mutableListOf(
            "pr", "list", "--state", "merged", "--limit", limit.toString(),
            "--json", "number,title,author,mergedAt,mergeCommit",
        )
        if (base != null) { args.add("--base"); args.add(base) }
        val (out, code) = run(repo, args)
        if (code != 0) return null
        return parse(out)
    }

    /** Parse `gh pr list --json number,title,author,mergedAt,mergeCommit` output. Pure. */
    fun parse(json: String): List<Pr> {
        val root = try { mapper.readTree(json) } catch (_: Exception) { return emptyList() }
        if (root == null || !root.isArray) return emptyList()
        return root.mapNotNull { n ->
            val number = n["number"]?.takeIf { it.isNumber }?.asInt() ?: return@mapNotNull null
            Pr(
                number = number,
                title = n["title"]?.asText().orEmpty(),
                author = n["author"]?.get("login")?.asText()?.ifBlank { null },
                mergedAt = n["mergedAt"]?.asText()?.ifBlank { null },
                mergeCommit = n["mergeCommit"]?.get("oid")?.asText()?.ifBlank { null },
            )
        }
    }

    private fun run(repo: File, args: List<String>): Pair<String, Int> = try {
        val p = ProcessBuilder(listOf("gh") + args).directory(repo).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.readBytes()
        out to p.waitFor()
    } catch (_: Exception) {
        "" to -1   // gh not installed / not executable
    }
}
