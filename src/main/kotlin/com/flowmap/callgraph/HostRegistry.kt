package com.flowmap.callgraph

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Resolves EXTERNAL HTTP calls to a SERVER-TO-SERVER target by HOST.
 *
 * 다른 시스템(서비스)으로 나가는 외부 호출의 호스트 정보가 application*.yml 에 설정돼 있는 경우가 많다
 * (예: `service-url.bank-broker: https://bank-api-stage.terafunding.com`). 같은 서비스라도 환경
 * (develop/sandbox/production/beta/live/local …)마다 호스트가 다르므로, 모든 프로파일의 yml 을 읽어
 * 호스트 집합을 모은 뒤, 어느 환경 하나라도 일치하면 같은 서비스로 본다.
 *
 * 매핑 방식: yml 의 URL 프로퍼티(value 가 http(s)://…)에서 host 를 추출하고
 *  1) 프로퍼티 leaf 키(`service-url.bank-broker` → `bank-broker`)를 분석 대상 프로젝트명과 별칭 매칭
 *  2) 실패 시 host 의 첫 라벨(`twice-api-stage` → `twice-api`)을 별칭 매칭
 * 매칭되면 host→project 로 등록한다. 키 매칭이 host 매칭보다 우선.
 *
 * localhost 류(127.*, 0.0.0.0, ::1, *.local …)는 제외한다. snakeyaml 만 사용(분석 API 무관).
 */
object HostRegistry {

    private val LOCAL = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Absolute http(s) URL → bare host (lowercased), or null for relative/jdbc/placeholder/local. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        if (!SCHEME.containsMatchIn(u)) return null
        val scheme = u.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        var s = u.substringAfter("://").substringBefore('/')
        s = s.substringAfterLast('@')          // drop userinfo
        if (s.startsWith("[")) s = s.substringAfter('[').substringBefore(']') // ipv6
        else s = s.substringBefore(':')        // drop port
        s = s.lowercase().trim()
        if (s.isEmpty() || s.contains("\${")) return null
        return s
    }

    fun isLocal(host: String?): Boolean =
        host == null || host in LOCAL || host.startsWith("127.") || host.endsWith(".local")

    private fun norm(s: String?): String = (s ?: "").lowercase().filter(Char::isLetterOrDigit)

    // 너무 일반적인 토큰(서비스 식별자가 아님) — substring 오탐 방지(예: host 라벨 "api" → "twiceapi").
    private val STOP = setOf("api", "service", "client", "url", "host", "gateway", "server",
        "app", "web", "www", "com", "net", "io", "co", "stage", "live", "local", "dev", "prod")

    /**
     * Best project whose name aliases [token] (normalized alnum):
     * exact > project startsWith token > token startsWith project > either-contains.
     * substring(contains) 매칭은 토큰 길이 4+ 일 때만 허용하고, 일반 토큰(STOP)은 제외해 오탐을 막는다.
     * On ties prefer the shorter project name. null when nothing plausibly matches.
     */
    fun alias(token: String?, normNames: List<Pair<String, String>>): String? {
        val t = norm(token)
        if (t.length < 3 || t in STOP) return null
        var best: String? = null; var bestScore = Int.MAX_VALUE
        for ((name, p) in normNames) {
            val score = when {
                p == t -> 0
                p.startsWith(t) && t.length >= 3 -> 1
                t.startsWith(p) && p.length >= 3 -> 2
                (p.contains(t) || t.contains(p)) && t.length >= 4 -> 3
                else -> continue
            }
            // tie-break: shorter project name wins (avoids over-greedy substring owners)
            if (score < bestScore || (score == bestScore && best != null && name.length < best!!.length)) {
                best = name; bestScore = score
            }
        }
        return best
    }

    /** Flatten a yaml file to dotted-key → scalar string (lists indexed). Tolerant of malformed files. */
    private fun flatten(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val root = runCatching { Yaml().loadAll(file.reader()).toList() }.getOrNull() ?: return out
        fun walk(prefix: String, node: Any?) {
            when (node) {
                is Map<*, *> -> for ((k, v) in node) walk(if (prefix.isEmpty()) "$k" else "$prefix.$k", v)
                is List<*> -> node.forEachIndexed { i, v -> walk("$prefix[$i]", v) }
                null -> {}
                else -> out[prefix] = node.toString()
            }
        }
        for (doc in root) walk("", doc)   // multiple --- docs (e.g. profile groups) all contribute
        return out
    }

    /** All application*.yml/yaml under a project's source dirs (every profile). */
    private fun ymlsOf(dirs: List<File>): List<File> = dirs.flatMap { d ->
        if (!d.isDirectory) emptyList()
        else d.walkTopDown().filter {
            it.isFile && it.path.replace('\\', '/').contains("/src/main/resources/") &&
                Regex("application(-[^/]+)?\\.(yml|yaml)$").containsMatchIn(it.name)
        }.toList()
    }

    /**
     * host → owning project, built from every project's yml across all profiles.
     * [projectDirs] = (projectName, its source dirs); [names] = all analyzed project names.
     */
    fun build(projectDirs: List<Pair<String, List<File>>>, names: List<String>): Map<String, String> {
        val normNames = names.map { it to norm(it) }
        val byKey = LinkedHashMap<String, String>()    // host → project (via config key — higher confidence)
        val byHost = LinkedHashMap<String, String>()   // host → project (via host label)
        for ((_, dirs) in projectDirs) {
            for (f in ymlsOf(dirs)) {
                for ((k, v) in flatten(f)) {
                    val host = hostOf(v) ?: continue
                    if (isLocal(host)) continue
                    val leaf = k.substringAfterLast('.').substringBefore('[')
                    alias(leaf, normNames)?.let { byKey.putIfAbsent(host, it); }
                    if (host !in byKey) alias(host.substringBefore('.'), normNames)?.let { byHost.putIfAbsent(host, it) }
                }
            }
        }
        val out = LinkedHashMap<String, String>(byHost)
        out.putAll(byKey)   // key matches override host-label matches
        return out
    }

    /**
     * Resolve an EXTERNAL node to its server-to-server target project, or null.
     * Tries the host registry first (env-union), then the node's own hints
     * (resolved host label, `${...}` placeholder leaf, Feign/client name) via alias.
     */
    fun resolve(
        node: MethodNode, registry: Map<String, String>, normNames: List<Pair<String, String>>, self: String?,
    ): String? {
        val host = hostOf(node.externalUrl)
        if (host != null && !isLocal(host)) {
            registry[host]?.let { if (it != self) return it }
            alias(host.substringBefore('.'), normNames)?.let { if (it != self) return it }
        }
        // placeholder leaf: "${service-url.bank-broker}" → "bank-broker"
        node.urlPlaceholder?.let { ph ->
            Regex("\\$\\{([^}]*)}").findAll(ph).forEach { m ->
                alias(m.groupValues[1].substringAfterLast('.'), normNames)?.let { if (it != self) return it }
            }
        }
        alias(node.externalService, normNames)?.let { if (it != self) return it }
        return null
    }
}
