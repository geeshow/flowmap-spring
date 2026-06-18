package com.flowmap.callgraph

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
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
 * Lightweight PSI parser (no BindingContext) that maps Kotlin OR Java source *text*
 * to its declared methods and their 1-based line ranges, keyed by the SAME node id the
 * call graph uses (`<fqcn>#<method>`, synthetic `.Companion` stripped — mirrors
 * GraphBuilder.normalizeFqcn). [Impact] feeds it the content of a changed file *at a
 * given revision*, so a commit's changed line ranges map onto graph node ids without
 * re-running full semantic analysis. Cheap enough to call per changed file per commit.
 *
 * Java is handled with the bundled IntelliJ Java PSI so a Java PR's changed methods
 * map to the same `<fqcn>#<method>` ids [JavaSourceAnalyzer] emits for the graph.
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
    private val javaFactory = PsiFileFactory.getInstance(env.project)

    /**
     * A declared function with its 1-based inclusive line range, its visibility, and
     * (for @*Mapping methods) its HTTP endpoint — all from PSI literals.
     */
    data class FnRange(
        val nodeId: String, val startLine: Int, val endLine: Int,
        val visibility: String = "public",
        val isEndpoint: Boolean = false, val httpMethod: String? = null, val endpoint: String? = null,
    ) {
        val isPublic: Boolean get() = visibility == "public"
    }

    /** Declared functions (inside classes/objects) with line ranges + visibility + endpoint metadata. */
    fun functions(fileName: String, text: String): List<FnRange> =
        if (fileName.endsWith(".java")) functionsJava(fileName, text) else functionsKotlin(fileName, text)

    /**
     * 1-based line numbers that carry CODE — a line holds a non-whitespace token that is NOT
     * inside a comment (line `//`, block `/* */`, or doc `/** */` / KDoc). Blank and
     * comment-only lines are absent. [Impact] uses this to IGNORE comment-only diff hunks:
     * a method counts as "changed" only when a changed line is also a code line. On parse
     * failure it returns every line (fail-safe — nothing is excluded).
     */
    fun codeLines(fileName: String, text: String): Set<Int> {
        val file = parseFile(fileName, text) ?: return (1..(text.count { it == '\n' } + 1)).toSet()
        val li = LineIndex(text)
        val code = HashSet<Int>()
        for (leaf in leaves(file)) {
            if (leaf.textLength == 0 || leaf is PsiWhiteSpace || isComment(leaf)) continue
            val r = leaf.textRange
            for (ln in li.lineAt(r.startOffset)..li.lineAt(r.endOffset - 1)) code.add(ln)
        }
        return code
    }

    private fun parseFile(fileName: String, text: String): PsiFile? = try {
        if (fileName.endsWith(".java"))
            javaFactory.createFileFromText(fileName.substringAfterLast('/'), JavaLanguage.INSTANCE, text)
        else factory.createFile(if (fileName.endsWith(".kt")) fileName.substringAfterLast('/') else "Snippet.kt", text)
    } catch (_: Throwable) { null }

    /** A leaf (or any element) is comment text if it is, or sits inside, a comment / KDoc. */
    private fun isComment(el: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(el, PsiComment::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(el, KDoc::class.java, false) != null

    /** All leaf elements (childless nodes) under [root], in document order. */
    private fun leaves(root: PsiElement): List<PsiElement> {
        val out = ArrayList<PsiElement>()
        fun rec(e: PsiElement) {
            var c: PsiElement? = e.firstChild
            if (c == null) { out.add(e); return }
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(root)
        return out
    }

    // ---- Kotlin ----

    private fun functionsKotlin(fileName: String, text: String): List<FnRange> {
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
                    visibility = ktVisibility(fn),
                    isEndpoint = ep != null, httpMethod = verb, endpoint = ep,
                ))
            }
        }
        return out
    }

    private fun ktVisibility(fn: KtNamedFunction): String = when {
        fn.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        fn.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        fn.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> "public"
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

    // ---- Java ----

    private fun functionsJava(fileName: String, text: String): List<FnRange> {
        val name = fileName.substringAfterLast('/')
        val psi = try { javaFactory.createFileFromText(name, JavaLanguage.INSTANCE, text) as? PsiJavaFile }
        catch (_: Throwable) { null } ?: return emptyList()
        val lineIndex = LineIndex(text)
        val out = ArrayList<FnRange>()
        PsiTreeUtil.findChildrenOfType(psi, PsiClass::class.java).forEach { cls ->
            val fqcn = cls.qualifiedName ?: return@forEach
            val annNames = cls.modifierList?.annotations?.mapNotNull { javaShortName(it) }?.toSet().orEmpty()
            val isExternalClient = "FeignClient" in annNames || "HttpExchange" in annNames
            val basePath = cls.modifierList?.annotations
                ?.firstOrNull { javaShortName(it) == "RequestMapping" || javaShortName(it) == "HttpExchange" }
                ?.let { javaAnnArg(it, "value", "path", "url") }
            cls.methods.filterNot { it.isConstructor }.forEach { m ->
                val r = m.textRange ?: return@forEach
                var verb: String? = null
                var ep: String? = null
                if (!isExternalClient) {
                    for (ann in m.modifierList.annotations) {
                        val v = Classify.MAPPING_VERBS[javaShortName(ann)] ?: continue
                        verb = v; ep = compose(basePath, javaAnnArg(ann, "value", "path")); break
                    }
                }
                out.add(FnRange(
                    "$fqcn#${m.name}", lineIndex.lineAt(r.startOffset), lineIndex.lineAt(r.endOffset),
                    visibility = javaVisibility(m),
                    isEndpoint = ep != null, httpMethod = verb, endpoint = ep,
                ))
            }
        }
        return out
    }

    private fun javaShortName(ann: PsiAnnotation): String? =
        ann.nameReferenceElement?.referenceName?.substringAfterLast('.')

    private fun javaVisibility(m: PsiMethod): String = when {
        m.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
        m.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
        else -> "public"   // package-private treated as public, matching the analyzer's IR
    }

    /** First string literal of a Java annotation arg ([names] or positional `value`); null if non-literal/absent. */
    private fun javaAnnArg(ann: PsiAnnotation, vararg names: String): String? {
        val wanted = names.toSet()
        for (attr in ann.parameterList.attributes) {
            val n = attr.name ?: "value"
            if (n !in wanted) continue
            (attr.value as? PsiLiteralExpression)?.value?.let { if (it is String) return it }
        }
        return null
    }

    // ---- shared ----

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
