package com.flowmap.callgraph

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Lightweight PSI parser (no BindingContext) that maps Kotlin source *text* to its
 * declared methods and their 1-based line ranges, keyed by the SAME node id the call
 * graph uses (`<fqcn>#<method>`, synthetic `.Companion` stripped — mirrors
 * GraphBuilder.normalizeFqcn). [Impact] feeds it the content of a changed file *at a
 * given revision*, so a commit's changed line ranges map onto graph node ids without
 * re-running full semantic analysis. Cheap enough to call per changed file per commit.
 */
class PsiSourceParser : AutoCloseable {
    private val disposable = Disposer.newDisposable("psi-source-parser")
    private val env: KotlinCoreEnvironment = KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "psi")
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
    )
    private val factory = KtPsiFactory(env.project)

    /**
     * A declared function with its 1-based inclusive line range and (for
     * @RestController/@Controller methods) its HTTP endpoint, all from PSI literals.
     */
    data class FnRange(
        val nodeId: String, val startLine: Int, val endLine: Int,
        val isEndpoint: Boolean = false, val httpMethod: String? = null, val endpoint: String? = null,
    )

    /** Declared functions (inside classes/objects) with line ranges + endpoint metadata. */
    fun functions(fileName: String, text: String): List<FnRange> {
        val name = if (fileName.endsWith(".kt")) fileName.substringAfterLast('/') else "Snippet.kt"
        val kt = try { factory.createFile(name, text) } catch (_: Throwable) { return emptyList() }
        val lineIndex = LineIndex(text)
        val out = ArrayList<FnRange>()
        kt.collectDescendantsOfType<KtClassOrObject>().forEach { cls ->
            val fqcn = (cls.fqName?.asString() ?: cls.name ?: return@forEach).removeSuffix(".Companion")
            val annNames = cls.annotationEntries.mapNotNull { it.shortName?.asString() }.toSet()
            // Endpoints are detected by the method's own @*Mapping — so a @Service that
            // serves HTTP is treated as a controller too. Only outbound @FeignClient/
            // @HttpExchange clients are excluded (their methods use @*Exchange anyway).
            val isExternalClient = "FeignClient" in annNames || "HttpExchange" in annNames
            val basePath = cls.annotationEntries
                .firstOrNull { it.shortName?.asString() == "RequestMapping" || it.shortName?.asString() == "HttpExchange" }
                ?.let { annStringArg(it, "value", "path", "url") }
            cls.declarations.filterIsInstance<KtNamedFunction>().forEach { fn ->
                val fnName = fn.name ?: return@forEach
                val r = fn.textRange ?: return@forEach
                var verb: String? = null
                var ep: String? = null
                if (!isExternalClient) {
                    for (ae in fn.annotationEntries) {
                        val v = Classify.MAPPING_VERBS[ae.shortName?.asString()] ?: continue
                        verb = v; ep = compose(basePath, annStringArg(ae, "value", "path")); break
                    }
                }
                out.add(FnRange(
                    "$fqcn#$fnName", lineIndex.lineAt(r.startOffset), lineIndex.lineAt(r.endOffset),
                    isEndpoint = ep != null, httpMethod = verb, endpoint = ep,
                ))
            }
        }
        return out
    }

    /** First string-literal value of an annotation arg (positional or one of [names]); null if `${}`/absent. */
    private fun annStringArg(entry: KtAnnotationEntry, vararg names: String): String? {
        for (va in entry.valueArguments) {
            val argName = va.getArgumentName()?.asName?.asString()
            if (argName != null && argName !in names) continue
            stringLiteralOf(va.getArgumentExpression())?.let { return it }
        }
        return null
    }

    /** Pure string literal (unwraps a single-element array literal); null if it contains `${}` interpolation. */
    private fun stringLiteralOf(expr: KtExpression?): String? {
        val e = (expr as? KtCollectionLiteralExpression)?.getInnerExpressions()?.firstOrNull() ?: expr
        val st = e as? KtStringTemplateExpression ?: return null
        if (st.entries.any { it !is KtLiteralStringTemplateEntry }) return null
        return st.entries.joinToString("") { it.text }
    }

    /** base + method path → normalized "/a/b" (mirror of GraphBuilder.compose). */
    private fun compose(base: String?, path: String?): String {
        val segs = ("${base ?: ""}/${path ?: ""}").split("/").filter { it.isNotEmpty() }
        return if (segs.isEmpty()) "/" else "/" + segs.joinToString("/")
    }

    override fun close() = Disposer.dispose(disposable)

    /** Precomputed newline offsets → O(log n) offset-to-(1-based)-line. */
    private class LineIndex(text: String) {
        private val newlines = buildList { for (i in text.indices) if (text[i] == '\n') add(i) }
        fun lineAt(offset: Int): Int {
            var lo = 0; var hi = newlines.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (newlines[mid] < offset) lo = mid + 1 else hi = mid }
            return lo + 1
        }
    }
}
