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
     * One file's change set within a PR, from the REST `pulls/{n}/files` endpoint —
     * richer than the merge-commit diff [Impact] mines: it carries GitHub's own
     * [status] (added/removed/modified/renamed/copied/changed) and the unified
     * [patch] hunk. [patch] is null for binary or too-large files; [previousPath]
     * is set only for renames/copies.
     */
    data class PrFile(
        val path: String,
        val status: String,
        val additions: Int,
        val deletions: Int,
        val changes: Int,
        val previousPath: String?,
        val patch: String?,
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

    /**
     * File-level change set of a single PR (status + unified patch) via the REST API:
     * `gh api --paginate repos/{owner}/{repo}/pulls/{n}/files`. `{owner}/{repo}` are
     * resolved by `gh` from the repo in [repo]; `--paginate` merges all pages into one
     * JSON array (>30 files). Returns null when `gh` could not run (missing /
     * unauthenticated / offline / not a GitHub repo), distinct from an empty list.
     */
    fun pullFiles(repo: File, number: Int): List<PrFile>? {
        val (out, code) = run(repo, listOf("api", "--paginate", "repos/{owner}/{repo}/pulls/$number/files"))
        if (code != 0) return null
        return parseFiles(out)
    }

    /** Parse a REST `pulls/{n}/files` JSON array. Pure. */
    fun parseFiles(json: String): List<PrFile> {
        val root = try { mapper.readTree(json) } catch (_: Exception) { return emptyList() }
        if (root == null || !root.isArray) return emptyList()
        return root.mapNotNull { n ->
            val path = n["filename"]?.asText()?.ifBlank { null } ?: return@mapNotNull null
            PrFile(
                path = path,
                status = n["status"]?.asText()?.ifBlank { null } ?: "modified",
                additions = n["additions"]?.takeIf { it.isNumber }?.asInt() ?: 0,
                deletions = n["deletions"]?.takeIf { it.isNumber }?.asInt() ?: 0,
                changes = n["changes"]?.takeIf { it.isNumber }?.asInt() ?: 0,
                previousPath = n["previous_filename"]?.asText()?.ifBlank { null },
                patch = n["patch"]?.asText()?.ifBlank { null },
            )
        }
    }

    /**
     * Full per-PR shard document — that PR's per-file status + unified patch, written
     * to `<dir>/<number>.json` and lazy-loaded on demand. A merged PR's files are
     * immutable, so a shard, once written, can be reused without re-calling `gh`
     * (see [readShard]); the caller skips already-collected PRs on that basis.
     */
    fun buildShard(pr: Pr, files: List<PrFile>, webBase: String?): Map<String, Any?> = linkedMapOf(
        "command" to "pull-files", "number" to pr.number, "title" to pr.title,
        "author" to pr.author, "mergedAt" to pr.mergedAt, "mergeCommit" to pr.mergeCommit,
        "url" to webBase?.let { "$it/pull/${pr.number}" }, "repoUrl" to webBase,
        "additions" to files.sumOf { it.additions },
        "deletions" to files.sumOf { it.deletions },
        "changedFiles" to files.size,
        "files" to files.map { f ->
            linkedMapOf(
                "path" to f.path, "status" to f.status,
                "additions" to f.additions, "deletions" to f.deletions, "changes" to f.changes,
                "previousPath" to f.previousPath, "patch" to f.patch,
            )
        },
    )

    /**
     * One light index entry derived from a [shard] doc (fresh in-memory or read back
     * from disk via [readShard]) — PR metadata + line stats + a `file` ref to the
     * shard. Carries NO patch, so the index stays small.
     */
    fun indexEntry(shard: Map<String, Any?>, shardDir: String): Map<String, Any?> = linkedMapOf(
        "number" to shard["number"], "title" to shard["title"], "author" to shard["author"],
        "mergedAt" to shard["mergedAt"], "url" to shard["url"],
        "additions" to shard["additions"], "deletions" to shard["deletions"],
        "changedFiles" to shard["changedFiles"],
        "file" to "$shardDir/${shard["number"]}.json",
    )

    /** The `<project>.pulls.json` index doc wrapping the per-PR [entries] (newest-first). */
    fun pullIndexDoc(base: String, webBase: String?, shardDir: String, entries: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "command" to "pull-files-index", "base" to base, "repoUrl" to webBase,
            "dir" to shardDir, "pullCount" to entries.size, "pulls" to entries,
        )

    /** Read an existing shard json back into a map (to reuse an already-collected PR). Null if unreadable. */
    @Suppress("UNCHECKED_CAST")
    fun readShard(file: File): Map<String, Any?>? = try {
        mapper.readValue(file, Map::class.java) as? Map<String, Any?>
    } catch (_: Exception) { null }

    private fun run(repo: File, args: List<String>): Pair<String, Int> = try {
        val p = ProcessBuilder(listOf("gh") + args).directory(repo).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.readBytes()
        out to p.waitFor()
    } catch (_: Exception) {
        "" to -1   // gh not installed / not executable
    }
}
