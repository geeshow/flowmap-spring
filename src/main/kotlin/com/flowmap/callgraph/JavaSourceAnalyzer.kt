package com.flowmap.callgraph

import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotationMemberValue
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayInitializerMemberValue
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiEnumConstant
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import java.io.File

/**
 * Structural Java-source -> IR builder. Parses `.java` files with the IntelliJ Java
 * PSI bundled inside kotlin-compiler-embeddable (no BindingContext) and emits the
 * SAME [IrFile]/[IrType]/[IrFunction] model the Kotlin path produces, so a single
 * [GraphBuilder] consumes Kotlin and Java uniformly.
 *
 * Call resolution is HEURISTIC, mirroring the Kotlin fallback path: a call
 * `receiver.method(...)` is resolved by the receiver's DECLARED type (field / param /
 * local) -> [simpleToFqcn] -> project fqcn. Java's mandatory explicit field/param
 * types make this reliable for the field-dispatched Spring calls that dominate a
 * controller/service/repository graph. Unresolvable calls (library/static/`var`
 * locals/deep chains) are dropped, exactly as the Kotlin resolver drops its
 * `Unresolved` cases.
 *
 * Two-phase use: [parse] all files first so [declaredTypes] can seed the shared
 * project fqcn / simple-name maps (enabling Java->Java and Kotlin<->Java edges),
 * then [build] the IR with those maps.
 */
class JavaSourceAnalyzer(private val project: Project, private val root: File) {

    private val factory = PsiFileFactory.getInstance(project)

    /** A parsed Java compilation unit: its provenance, source text, and declared classes. */
    class Parsed(
        val relPath: String,
        val project: String?,
        val module: String?,
        val text: String,
        val classes: List<PsiClass>,
    )

    /** Parse every `.java` file to PSI (best-effort; an unparseable file is skipped). */
    fun parse(files: List<File>): List<Parsed> = files.mapNotNull { f ->
        try {
            val text = f.readText()
            val psi = factory.createFileFromText(f.name, JavaLanguage.INSTANCE, text) as? PsiJavaFile
                ?: return@mapNotNull null
            val classes = PsiTreeUtil.findChildrenOfType(psi, PsiClass::class.java)
                .filter { it.qualifiedName != null }   // skip anonymous/local classes
            val (proj, module) = provenance(f.absolutePath)
            val rel = f.relativeToOrNull(root)?.path ?: f.path
            Parsed(rel, proj, module, text, classes)
        } catch (_: Throwable) { null }
    }

    /** (fqcn, simpleName) for every parsed Java class — used to seed the shared resolution maps. */
    fun declaredTypes(parsed: List<Parsed>): List<Pair<String, String>> =
        parsed.flatMap { p ->
            p.classes.mapNotNull { c -> c.qualifiedName?.let { it to (c.name ?: it.substringAfterLast('.')) } }
        }

    /** Build IR for all parsed files, resolving internal calls against the shared maps. */
    fun build(
        parsed: List<Parsed>,
        projectFqcns: Set<String>,
        simpleToFqcn: Map<String, String>,
    ): List<IrFile> {
        val b = Builder(projectFqcns, simpleToFqcn)
        return parsed.mapNotNull { try { b.buildFile(it) } catch (_: Throwable) { null } }
    }

    private fun provenance(absPath: String): Pair<String?, String?> {
        val rel = File(absPath).relativeToOrNull(root)?.path ?: return null to null
        val parts = rel.split(File.separator)
        return parts.getOrNull(0) to parts.getOrNull(1)
    }

    private inner class Builder(
        val projectFqcns: Set<String>,
        val simpleToFqcn: Map<String, String>,
    ) {
        fun buildFile(p: Parsed): IrFile {
            val lines = LineIndex(p.text)
            val types = p.classes.map { buildType(it, p, lines) }
            return IrFile(p.relPath, p.project, p.module, "java", types)
        }

        private fun buildType(cls: PsiClass, p: Parsed, lines: LineIndex): IrType {
            val annNames = annNamesOf(cls)
            val superNames = supertypeNamesOf(cls)
            val ownMethodNames = cls.methods.filterNot { it.isConstructor }.mapNotNull { it.name }.toSet()
            val funcs = cls.methods.filterNot { it.isConstructor }.map { buildFunction(cls, it, ownMethodNames, lines) }
            val isEnum = cls.isEnum
            return IrType(
                fqcn = cls.qualifiedName ?: (cls.name ?: "?"),
                simpleName = cls.name ?: "?",
                packageName = (cls.containingFile as? PsiJavaFile)?.packageName ?: "",
                kind = when {
                    isEnum -> "enum"
                    cls.isInterface -> "interface"
                    else -> "class"
                },
                annotationSimpleNames = annNames,
                supertypeSimpleNames = superNames,
                baseRequestPath = classBasePath(cls),
                isFeign = "FeignClient" in annNames,
                isHttpExchange = "HttpExchange" in annNames,
                functions = funcs,
                file = p.relPath,
                line = lines.lineAt(cls.textOffset),
                isEntity = "Entity" in annNames,
                tableName = if ("Entity" in annNames) (annArg(cls, "Table", "name") ?: cls.name) else null,
                repoEntity = repoEntityOf(cls),
                properties = cls.fields.filterNot { it is PsiEnumConstant }
                    .map { IrProperty(it.name, typeRef(it.type)) },
                enumEntries = if (isEnum) cls.fields.filterIsInstance<PsiEnumConstant>().map { it.name } else emptyList(),
            )
        }

        private fun buildFunction(cls: PsiClass, m: PsiMethod, ownMethodNames: Set<String>, lines: LineIndex): IrFunction {
            val annNames = annNamesOf(m)
            val (verb, path) = mappingOf(m)
            val symbols = symbolTable(cls, m)
            val body = m.body
            val callExprs = body?.let { PsiTreeUtil.findChildrenOfType(it, PsiMethodCallExpression::class.java).toList() }
                ?: emptyList()
            val calls = callExprs.map { call ->
                IrCall(lines.lineAt(call.textOffset), false, resolveCall(call, cls, symbols, ownMethodNames))
            }
            return IrFunction(
                name = m.name,
                visibility = visibilityOf(m),
                isSuspend = false,
                annotationSimpleNames = annNames,
                returnTypeSimple = typeSimple(m.returnType),
                httpMethod = verb,
                path = path,
                apiDescription = annArg(m, "Operation", "summary") ?: annArg(m, "Operation", "description"),
                isBean = "Bean" in annNames,
                returnTypeRef = m.returnType?.let { typeRef(it) },
                parameters = m.parameterList.parameters.map { vp ->
                    IrParam(vp.name, typeRef(vp.type), annNamesOf(vp))
                },
                line = lines.lineAt(m.textOffset),
                calls = calls,
                batchWiring = batchWiring(callExprs),
                kafkaProduced = kafkaProduced(callExprs, symbols),
                kafkaConsumed = kafkaConsumed(m),
            )
        }

        // ---- call resolution (heuristic, declared-type based) ----

        private fun resolveCall(
            call: PsiMethodCallExpression,
            enclosing: PsiClass,
            symbols: Map<String, String>,
            ownMethodNames: Set<String>,
        ): CallResolution {
            val ref = call.methodExpression
            val method = ref.referenceName ?: return CallResolution.Unresolved
            val qualifier = ref.qualifierExpression

            // Unqualified call (this.x() / x()) -> internal only when this class declares it.
            if (qualifier == null || qualifier is PsiThisExpression) {
                val fqcn = enclosing.qualifiedName ?: return CallResolution.Unresolved
                return if (method in ownMethodNames)
                    CallResolution.Internal(fqcn, method, false) else CallResolution.Unresolved
            }

            val recvType = receiverType(qualifier, symbols) ?: return CallResolution.Unresolved

            // Infra resources take precedence over the generic project/external path.
            if (recvType in Classify.KAFKA_TEMPLATE_TYPES) return CallResolution.Unresolved // via kafkaProduced
            if (recvType in Classify.REDIS_TEMPLATE_TYPES) return CallResolution.Resource("redis", "redis", "Redis", "redis:io")
            if (recvType in Classify.JDBC_TEMPLATE_TYPES) return CallResolution.Resource("db:jdbc", "db-table", "JDBC", "db:io")
            if (recvType in Classify.EXTERNAL_SIMPLE_TYPES) {
                return CallResolution.ImperativeClient(
                    clientType = recvType, clientPackage = null, method = method,
                    service = enclosing.name, httpMethod = null, url = null, urlPlaceholder = null,
                )
            }

            val recvFqcn = simpleToFqcn[recvType] ?: return CallResolution.Unresolved
            if (recvFqcn !in projectFqcns) return CallResolution.Unresolved
            if (method in Classify.REPOSITORY_INHERITED_METHODS) {
                return CallResolution.RepositoryInherited(recvFqcn, method)
            }
            return CallResolution.Internal(recvFqcn, method, false)
        }

        /** Declared receiver type (simple name) for a call qualifier: a field/param/local reference. */
        private fun receiverType(qualifier: PsiExpression, symbols: Map<String, String>): String? {
            // `field` / `local` / `param`
            if (qualifier is PsiReferenceExpression) {
                val q = qualifier.qualifierExpression
                if (q == null) return symbols[qualifier.referenceName]
                // `this.field`
                if (q is PsiThisExpression) return symbols[qualifier.referenceName]
            }
            // Fall back to a resolved/static type when available (usually null without resolution).
            return typeSimple(qualifier.type)
        }

        /** name -> declared type simple name for class fields, method params, and local vars. */
        private fun symbolTable(cls: PsiClass, m: PsiMethod): Map<String, String> {
            val map = HashMap<String, String>()
            cls.fields.forEach { f: PsiField -> typeSimple(f.type)?.let { map[f.name] = it } }
            m.parameterList.parameters.forEach { p -> typeSimple(p.type)?.let { map[p.name] = it } }
            m.body?.let { PsiTreeUtil.findChildrenOfType(it, PsiLocalVariable::class.java) }
                ?.forEach { lv -> typeSimple(lv.type)?.let { map[lv.name] = it } }
            return map
        }

        // ---- batch / kafka extraction ----

        private fun batchWiring(calls: List<PsiMethodCallExpression>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (call in calls) {
                val name = call.methodExpression.referenceName ?: continue
                val relation = Classify.BATCH_WIRING_METHODS[name] ?: continue
                val arg = call.argumentList.expressions.firstOrNull() as? PsiReferenceExpression ?: continue
                arg.referenceName?.let { out.add(relation to it) }
            }
            return out
        }

        private fun kafkaProduced(calls: List<PsiMethodCallExpression>, symbols: Map<String, String>): List<String> {
            val out = LinkedHashSet<String>()
            for (call in calls) {
                val name = call.methodExpression.referenceName ?: continue
                if (name !in Classify.KAFKA_SEND_METHODS) continue
                val recv = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)
                    ?.let { symbols[it.referenceName] }
                if (recv != null && recv !in Classify.KAFKA_TEMPLATE_TYPES) continue
                literalOf(call.argumentList.expressions.firstOrNull())?.let { out.add(it) }
            }
            return out.toList()
        }

        private fun kafkaConsumed(m: PsiMethod): List<String> {
            val ann = m.modifierList.annotations.firstOrNull {
                shortName(it) in Classify.KAFKA_LISTENER_ANNOTATIONS
            } ?: return emptyList()
            return annArgList(ann, "topics").ifEmpty { annArgList(ann, "value") }
        }

        // ---- mapping / annotation helpers ----

        private fun mappingOf(m: PsiMethod): Pair<String?, String?> {
            for (ann in m.modifierList.annotations) {
                val verb = Classify.MAPPING_VERBS[shortName(ann)] ?: continue
                return verb to (annArg(ann, "value") ?: annArg(ann, "path"))
            }
            return null to null
        }

        private fun classBasePath(cls: PsiClass): String? {
            val ann = cls.modifierList?.annotations?.firstOrNull {
                shortName(it) == "RequestMapping" || shortName(it) == "HttpExchange"
            } ?: return null
            return annArg(ann, "value") ?: annArg(ann, "path") ?: annArg(ann, "url")
        }

        private fun repoEntityOf(cls: PsiClass): String? {
            val refs = (cls.extendsList?.referenceElements?.toList().orEmpty() +
                cls.implementsList?.referenceElements?.toList().orEmpty())
            for (r in refs) {
                if (r.referenceName !in Classify.REPOSITORY_GENERIC_BASES) continue
                val firstArg = r.typeParameters.firstOrNull() ?: continue
                return typeSimple(firstArg)
            }
            return null
        }
    }

    // ---- shared PSI utilities (resolution-free) ----

    private fun annNamesOf(owner: PsiModifierListOwner): Set<String> =
        owner.modifierList?.annotations?.mapNotNull { shortName(it) }?.toSet().orEmpty()

    private fun supertypeNamesOf(cls: PsiClass): Set<String> =
        (cls.extendsList?.referenceElements?.toList().orEmpty() +
            cls.implementsList?.referenceElements?.toList().orEmpty())
            .mapNotNull { it.referenceName }.toSet()

    private fun shortName(ann: PsiAnnotation): String? =
        ann.nameReferenceElement?.referenceName?.substringAfterLast('.')

    private fun visibilityOf(m: PsiModifierListOwner): String = when {
        m.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
        m.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
        else -> "public"   // package-private treated as public, matching the Kotlin default
    }

    private fun typeSimple(t: PsiType?): String? {
        if (t == null) return null
        (t as? PsiClassType)?.let { return it.className }
        return t.presentableText.substringBefore('<').substringAfterLast('.').removeSuffix("[]").trim()
            .ifEmpty { null }
    }

    private fun typeRef(t: PsiType?): IrTypeRef {
        val simple = typeSimple(t) ?: "Any"
        val args = (t as? PsiClassType)?.parameters?.map { typeRef(it) } ?: emptyList()
        return IrTypeRef(simple, fqcn = null, nullable = false, args = args)
    }

    /** First string literal of an annotation arg ([names] or positional `value`); null if non-literal/absent. */
    private fun annArg(owner: PsiModifierListOwner, annName: String, vararg argNames: String): String? {
        val ann = owner.modifierList?.annotations?.firstOrNull { shortName(it) == annName } ?: return null
        return annArg(ann, *argNames)
    }

    private fun annArg(ann: PsiAnnotation, vararg argNames: String): String? {
        val wanted = if (argNames.isEmpty()) setOf("value") else argNames.toSet()
        for (attr in ann.parameterList.attributes) {
            val name = attr.name ?: "value"
            if (name in wanted) literalOf(attr.value)?.let { return it }
        }
        return null
    }

    private fun annArgList(ann: PsiAnnotation, argName: String): List<String> {
        for (attr in ann.parameterList.attributes) {
            if ((attr.name ?: "value") != argName) continue
            val v = attr.value
            if (v is PsiArrayInitializerMemberValue) return v.initializers.mapNotNull { literalOf(it) }
            return listOfNotNull(literalOf(v))
        }
        return emptyList()
    }

    private fun literalOf(v: PsiElement?): String? = when (v) {
        is PsiLiteralExpression -> v.value as? String
        is PsiArrayInitializerMemberValue -> v.initializers.firstOrNull()?.let { literalOf(it) }
        is PsiAnnotationMemberValue -> (v as? PsiLiteralExpression)?.value as? String
        else -> null
    }

    /** Precomputed newline offsets -> O(log n) offset-to-(1-based)-line. */
    private class LineIndex(text: String) {
        private val newlines = buildList { for (i in text.indices) if (text[i] == '\n') add(i) }
        fun lineAt(offset: Int): Int {
            if (offset < 0) return 1
            var lo = 0; var hi = newlines.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (newlines[mid] < offset) lo = mid + 1 else hi = mid }
            return lo + 1
        }
    }
}
