package com.flowmap.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * Merged pull-request source for [Impact], which analyzes change impact per PR
 * (not per raw commit). Each PR is reduced to its merge/squash commit, whose
 * first-parent diff is the PR's net change set — fed into the line→node machinery.
 *
 * GIT-FIRST, gh-fallback: PRs and their file diffs are derived from local git
 * (`git log --first-parent` + `git show`) so analysis works with NO `gh` and on
 * GitHub Enterprise. `gh` is used only when git yields no PR markers (e.g. a
 * rebase-merge history) or can't run. Degrades gracefully: if neither source
 * works, [mergedPulls] is null and impact reports no PRs for that project.
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
     *
     * GIT-FIRST: derive the PR set from the integration history `git log --first-parent`
     * (no `gh`, works on any host incl. GitHub Enterprise) — each PR appears as either a
     * merge commit (`Merge pull request #N`, title in the body) or a squash commit
     * (subject `… (#N)`); the commit is the PR's merge commit, whose first-parent diff is
     * its net change. Only when git yields NOTHING (e.g. rebase-merge history with no PR
     * markers, or not a git repo) does it FALL BACK to `gh pr list`.
     *
     * Returns null only when BOTH sources are unavailable — distinct from an empty list,
     * which means a source ran fine and the base simply has no merged PRs. Callers use
     * that to tell "unknown" (keep prior artifacts) from "none" (prune stale).
     */
    fun mergedPulls(repo: File, base: String?, limit: Int): List<Pr>? {
        val fromGit = gitMergedPulls(repo, base ?: "HEAD", limit)
        if (fromGit.isNotEmpty()) return fromGit
        return ghMergedPulls(repo, base, limit)
    }

    /** PR set parsed from `git log --first-parent` (merge + squash markers). Empty if none/not a git repo. */
    fun gitMergedPulls(repo: File, base: String, limit: Int): List<Pr> {
        // \x1f field sep, \x1e record sep — safe across multi-line bodies.
        val (out, code) = git(repo, listOf(
            "log", "--first-parent", base, "-n", "5000", "--no-color",
            "--pretty=format:%H%x1f%cI%x1f%an%x1f%s%x1f%b%x1e",
        ))
        if (code != 0) return emptyList()
        return parseGitLog(out, limit)
    }

    private val MERGE_PR = Regex("^Merge pull request #(\\d+) ")
    private val SQUASH_PR = Regex("\\(#(\\d+)\\)\\s*$")

    /** Parse the `%H\x1f%cI\x1f%an\x1f%s\x1f%b\x1e`-formatted log into newest-first PRs. Pure. */
    fun parseGitLog(out: String, limit: Int): List<Pr> {
        val prs = ArrayList<Pr>()
        for (rec in out.split('\u001e')) {
            val r = rec.trim('\n', '\r')
            if (r.isBlank()) continue
            val f = r.split('\u001f')
            if (f.size < 4) continue
            val sha = f[0].ifBlank { null } ?: continue
            val date = f[1].ifBlank { null }
            val author = f[2].ifBlank { null }
            val subject = f[3]
            val body = f.getOrElse(4) { "" }
            val merge = MERGE_PR.find(subject)
            val squash = if (merge == null) SQUASH_PR.find(subject) else null
            val number = (merge ?: squash)?.groupValues?.get(1)?.toIntOrNull() ?: continue   // non-PR commit
            val title = if (merge != null) body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: subject
                        else subject.replace(SQUASH_PR, "").trim()
            prs.add(Pr(number, title, author, date, sha))
            if (prs.size >= limit) break
        }
        return prs
    }

    /** Merged PRs via `gh pr list` (server source / fallback). Null when `gh` cannot run. */
    fun ghMergedPulls(repo: File, base: String?, limit: Int): List<Pr>? {
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
     * File-level change set of a single PR (status + unified patch).
     *
     * GIT-FIRST: when the PR's [Pr.mergeCommit] is known, derive it locally from
     * `git show --first-parent -M <sha>` (the PR's net diff) — no `gh`, works on
     * GitHub Enterprise. Falls back to the REST API only when there is no merge
     * commit or git can't run. Returns null only when neither source works.
     */
    fun pullFiles(repo: File, pr: Pr): List<PrFile>? {
        pr.mergeCommit?.let { sha ->
            val fromGit = gitPullFiles(repo, sha)
            if (fromGit != null) return fromGit
        }
        return ghPullFiles(repo, pr.number)
    }

    /** Per-file status + patch from a merge/squash commit's first-parent diff. Null if git can't run. */
    fun gitPullFiles(repo: File, sha: String): List<PrFile>? {
        val (out, code) = git(repo, listOf("show", "--first-parent", "-M", "--no-color", "--format=", sha))
        if (code != 0) return null
        return parseShow(out)
    }

    private val DIFF_GIT = Regex("^diff --git a/(.*) b/(.*)$")

    /** Parse a `git show`/`git diff` unified diff into per-file [PrFile]s (status, patch, +/- counts). Pure. */
    fun parseShow(diff: String): List<PrFile> {
        val files = ArrayList<PrFile>()
        var path: String? = null; var oldPath: String? = null
        var status = "modified"; var add = 0; var del = 0
        val patch = StringBuilder(); var inHunk = false
        fun flush() {
            val p = path ?: return
            files.add(PrFile(
                path = p, status = status, additions = add, deletions = del, changes = add + del,
                previousPath = oldPath?.takeIf { status == "renamed" && it != p },
                patch = patch.toString().ifEmpty { null },
            ))
        }
        for (line in diff.lineSequence()) {
            if (line.startsWith("diff --git ")) {           // new file block
                flush()
                path = null; oldPath = null; status = "modified"; add = 0; del = 0; patch.setLength(0); inHunk = false
                DIFF_GIT.find(line)?.let { oldPath = it.groupValues[1]; path = it.groupValues[2] }
                continue
            }
            if (inHunk) {                                    // hunk body until the next file block
                patch.append(line).append('\n')
                when (line.firstOrNull()) { '+' -> add++; '-' -> del++; else -> {} }
                continue
            }
            when {                                           // pre-hunk file header
                line.startsWith("new file") -> status = "added"
                line.startsWith("deleted file") -> status = "removed"
                line.startsWith("rename from ") -> { oldPath = line.removePrefix("rename from "); status = "renamed" }
                line.startsWith("rename to ") -> path = line.removePrefix("rename to ")
                line.startsWith("--- a/") -> oldPath = line.removePrefix("--- a/")
                line.startsWith("+++ b/") -> path = line.removePrefix("+++ b/")
                line.startsWith("@@") -> { inHunk = true; patch.append(line).append('\n') }
            }
        }
        flush()
        return files.filter { it.path.isNotEmpty() }
    }

    /**
     * File-level change set via the REST API (fallback): `gh api --paginate
     * repos/{owner}/{repo}/pulls/{n}/files`. `{owner}/{repo}` + host are resolved by
     * `gh` from [repo]; `--paginate` merges all pages (>30 files). Null if `gh` can't run.
     */
    fun ghPullFiles(repo: File, number: Int): List<PrFile>? {
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

    private fun run(repo: File, args: List<String>): Pair<String, Int> = exec(repo, "gh", args)
    private fun git(repo: File, args: List<String>): Pair<String, Int> = exec(repo, "git", args)

    /** Run [command] in [repo]; returns (stdout, exitCode), or ("", -1) if it can't be launched. */
    private fun exec(repo: File, command: String, args: List<String>): Pair<String, Int> = try {
        val p = ProcessBuilder(listOf(command) + args).directory(repo).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.readBytes()
        out to p.waitFor()
    } catch (_: Exception) {
        "" to -1   // command not installed / not executable
    }
}
