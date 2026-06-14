package com.flowmap.callgraph

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoots
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType
import java.io.File

/**
 * K1 (kotlin-compiler-embeddable) implementation of [Resolver].
 *
 * Builds a [KotlinCoreEnvironment], runs the JVM front-end to a [BindingContext],
 * then walks PSI resolving calls/constants semantically. Library symbols are
 * handled by short-name fallback (no classpath required).
 */
class AnalysisSession : Resolver {

    private val skipDirs = setOf(".git", "build", "out", "target", "node_modules", ".gradle", ".idea")

    /** `JpaRepository<Entity, Id>` -> entity type argument. Mirrors the Python `_REPO_GENERIC_RE`. */
    private val REPO_GENERIC_RE =
        Regex("\\b(${Classify.REPOSITORY_GENERIC_BASES.joinToString("|")})\\s*<\\s*([\\w.]+)")

    override fun analyze(
        repoRoot: String,
        projectFilter: String?,
        profile: String?,
        extraProps: Map<String, String>,
    ): List<IrFile> {
        val root = File(repoRoot).absoluteFile
        val sourceRoots = discoverSourceRoots(root, projectFilter)
        if (sourceRoots.isEmpty()) return emptyList()

        val disposable = Disposer.newDisposable("callgraph-analysis")
        try {
            val configuration = CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MODULE_NAME, projectFilter ?: "repo")
                put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
                addKotlinSourceRoots(sourceRoots.map { it.absolutePath })
                // Put the analyzer's own runtime classpath (which bundles the Spring
                // annotation/base-type jars declared in build.gradle.kts) on the analysis
                // classpath, so Spring stereotypes, @*Mapping args, @FeignClient, Spring Data
                // base interfaces, and Batch types RESOLVE in the binding context.
                addJvmClasspathRoots(
                    (System.getProperty("java.class.path") ?: "")
                        .split(File.pathSeparator)
                        .filter { it.isNotBlank() }
                        .map { File(it) }
                        .filter { it.exists() }
                )
                // JDK_HOME lets KotlinCoreEnvironment configure the JDK itself; we avoid
                // the version-unstable configureJdkClasspathRoots() extension. Library
                // (Spring/JDK) symbols stay unresolved by design — short-name fallback covers them.
                put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))
                put(JVMConfigurationKeys.NO_JDK, false)
            }
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val ktFiles: List<KtFile> = environment.getSourceFiles()

            val analysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                environment.project,
                ktFiles,
                NoScopeRecordCliBindingTrace(environment.project),
                environment.configuration,
                environment::createPackagePartProvider,
            )
            val bc = analysisResult.bindingContext

            // First pass: project class fqcns (PSI only).
            val projectFqcns = HashSet<String>()
            val simpleToFqcn = HashMap<String, String>()
            for (kt in ktFiles) kt.collectDescendantsOfType<KtClassOrObject>().forEach { c ->
                c.fqName?.asString()?.let { fq ->
                    projectFqcns.add(fq)
                    c.name?.let { simpleToFqcn.putIfAbsent(it, fq) }
                }
            }

            val constEval = ConstantEvaluator(bc)
            val ext = ExternalResolver(constEval)
            val ymlCache = HashMap<String, YamlPropertyResolver>()
            val builder = IrBuilder(bc, root, projectFqcns, simpleToFqcn, constEval, ext) { moduleDir ->
                ymlCache.getOrPut(moduleDir?.path ?: "_") {
                    if (moduleDir != null) YamlPropertyResolver.forModule(moduleDir, profile, extraProps)
                    else YamlPropertyResolver.fromProps(extraProps)
                }
            }
            return ktFiles.mapNotNull { builder.buildFile(it) }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    // ---- IR construction over a fixed BindingContext ----

    private inner class IrBuilder(
        val bc: BindingContext,
        val root: File,
        val projectFqcns: Set<String>,
        val simpleToFqcn: Map<String, String>,
        val constEval: ConstantEvaluator,
        val ext: ExternalResolver,
        val ymlFor: (File?) -> YamlPropertyResolver,
    ) {
        fun buildFile(kt: KtFile): IrFile? {
            val vpath = kt.virtualFile?.path ?: return null
            val relPath = File(vpath).relativeToOrNull(root)?.path ?: vpath
            val (project, module) = provenance(vpath)
            val moduleDir = if (project != null && module != null)
                File(root, "$project${File.separator}$module") else null
            val yml = ymlFor(moduleDir)
            val types = kt.collectDescendantsOfType<KtClassOrObject>().map { buildType(it, kt, relPath, yml) }
            return IrFile(relPath, project, module, if (vpath.endsWith(".java")) "java" else "kotlin", types)
        }

        private fun buildType(cls: KtClassOrObject, kt: KtFile, relPath: String, yml: YamlPropertyResolver): IrType {
            val desc = bc.get(BindingContext.CLASS, cls)
            val annNames = desc?.annotations?.mapNotNull { it.fqName?.shortName()?.asString() }?.toSet().orEmpty()
            val superNames = desc?.typeConstructor?.supertypes
                ?.mapNotNull { it.constructor.declarationDescriptor?.name?.asString() }?.toSet().orEmpty()
            val funcs = cls.declarations.filterIsInstance<KtNamedFunction>().map { buildFunction(it, yml) }
            val (isEntity, tableName) = entityTableOf(cls)
            val isEnum = desc?.kind == ClassKind.ENUM_CLASS
            return IrType(
                fqcn = cls.fqName?.asString() ?: (cls.name ?: "?"),
                simpleName = cls.name ?: "?",
                packageName = kt.packageFqName.asString(),
                kind = when {
                    isEnum -> "enum"
                    cls is org.jetbrains.kotlin.psi.KtClass && cls.isInterface() -> "interface"
                    cls is org.jetbrains.kotlin.psi.KtObjectDeclaration -> "object"
                    else -> "class"
                },
                annotationSimpleNames = annNames,
                supertypeSimpleNames = superNames,
                baseRequestPath = desc?.let { ext.basePathOf(it) },
                isFeign = desc?.let { ext.isFeign(it) } ?: false,
                isHttpExchange = desc?.let { ext.isHttpExchange(it) } ?: false,
                functions = funcs,
                file = relPath,
                line = lineOf(cls),
                isEntity = isEntity,
                tableName = tableName,
                repoEntity = repoEntityOf(cls),
                properties = propertiesOf(cls, desc),
                enumEntries = if (isEnum) cls.declarations.filterIsInstance<KtEnumEntry>().map { it.name ?: "?" }
                else emptyList(),
            )
        }

        /**
         * DTO field schema source: primary-constructor `val`/`var` params (in declaration
         * order) followed by class-body properties declared on this type. Inherited and
         * synthetic members are excluded. Types are resolved via [typeRefOf].
         */
        private fun propertiesOf(cls: KtClassOrObject, desc: ClassDescriptor?): List<IrProperty> {
            if (desc == null) return emptyList()
            val out = LinkedHashMap<String, IrProperty>()
            val valVar = (cls as? KtClass)?.primaryConstructorParameters
                ?.filter { it.hasValOrVar() }?.mapNotNull { it.name }?.toSet().orEmpty()
            desc.unsubstitutedPrimaryConstructor?.valueParameters
                ?.filter { it.name.asString() in valVar }
                ?.forEach { vp -> out[vp.name.asString()] = IrProperty(vp.name.asString(), typeRefOf(vp.type)) }
            try {
                desc.unsubstitutedMemberScope.getContributedDescriptors()
                    .filterIsInstance<PropertyDescriptor>()
                    .filter { it.containingDeclaration == desc }
                    .forEach { p -> out.putIfAbsent(p.name.asString(), IrProperty(p.name.asString(), typeRefOf(p.type))) }
            } catch (_: Throwable) { /* member scope unavailable; ctor props suffice */ }
            return out.values.toList()
        }

        /** Resolve a [KotlinType] into an Analysis-API-free [IrTypeRef], recursing into generics. */
        private fun typeRefOf(t: KotlinType): IrTypeRef {
            val decl = t.constructor.declarationDescriptor
            val simple = decl?.name?.asString() ?: "Any"
            val fqcn = (decl as? ClassDescriptor)?.fqNameSafe?.asString()
            val args = t.arguments
                .filterNot { it.isStarProjection }
                .map { typeRefOf(it.type) }
            return IrTypeRef(simple, fqcn, t.isMarkedNullable, args)
        }

        private fun buildFunction(fn: KtNamedFunction, yml: YamlPropertyResolver): IrFunction {
            val desc = bc.get(BindingContext.FUNCTION, fn)
            val annNames = desc?.annotations?.mapNotNull { it.fqName?.shortName()?.asString() }?.toSet().orEmpty()
            val (verb, path) = desc?.let { ext.mappingOf(it) } ?: (null to null)
            val ret = desc?.returnType?.constructor?.declarationDescriptor?.name?.asString()
            val retRef = desc?.returnType?.let { typeRefOf(it) }
            val params = desc?.valueParameters?.map { vp ->
                IrParam(
                    name = vp.name.asString(),
                    type = typeRefOf(vp.type),
                    annotationSimpleNames = vp.annotations.mapNotNull { it.fqName?.shortName()?.asString() }.toSet(),
                )
            }.orEmpty()
            val body = fn.bodyBlockExpression ?: fn.bodyExpression
            val calls = body?.collectDescendantsOfType<KtCallExpression>()?.map { classifyCall(it, yml) }.orEmpty()
            val batch = body?.let { collectBatchWiring(it) }.orEmpty()
            return IrFunction(
                name = fn.name ?: "?",
                visibility = visibilityOf(fn),
                isSuspend = desc?.isSuspend ?: fn.hasModifier(KtTokens.SUSPEND_KEYWORD),
                annotationSimpleNames = annNames,
                returnTypeSimple = ret,
                httpMethod = verb,
                path = path,
                apiDescription = desc?.let { ext.apiDescriptionOf(it) },
                isBean = "Bean" in annNames,
                returnTypeRef = retRef,
                parameters = params,
                line = lineOf(fn),
                calls = calls,
                batchWiring = batch,
                kafkaProduced = body?.let { kafkaProducedTopics(it) }.orEmpty(),
                kafkaConsumed = kafkaConsumedTopics(fn),
            )
        }

        private fun classifyCall(callExpr: KtCallExpression, yml: YamlPropertyResolver): IrCall {
            val line = lineOf(callExpr)
            val inAsync = isInAsyncContext(callExpr)
            val resolution = try {
                resolve(callExpr, yml)
            } catch (_: Throwable) {
                CallResolution.Unresolved
            }
            return IrCall(line, inAsync, resolution)
        }

        private fun resolve(callExpr: KtCallExpression, yml: YamlPropertyResolver): CallResolution {
            val callee = callExpr.getResolvedCall(bc)?.resultingDescriptor as? FunctionDescriptor
            val owner = callee?.containingDeclaration as? ClassDescriptor

            if (callee != null && owner != null) {
                if (ext.isFeign(owner) || ext.isHttpExchange(owner)) {
                    return ext.resolveDeclarative(callee, owner, yml)
                }
                val ownerFqcn = owner.fqNameSafe.asString()
                if (ownerFqcn in projectFqcns) {
                    return CallResolution.Internal(ownerFqcn, callee.name.asString(), isAsyncCallee(callee))
                }
            }

            val recvType = receiverDeclaredType(callExpr) ?: return CallResolution.Unresolved
            val method = (callExpr.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                ?: return CallResolution.Unresolved
            // Infra resources take precedence over the generic external-client path.
            if (recvType in Classify.KAFKA_TEMPLATE_TYPES) {
                return CallResolution.Unresolved // wired separately from IrFunction.kafkaProduced
            }
            if (recvType in Classify.REDIS_TEMPLATE_TYPES) {
                return CallResolution.Resource("redis", "redis", "Redis", "redis:io")
            }
            if (recvType in Classify.JDBC_TEMPLATE_TYPES) {
                return CallResolution.Resource("db:jdbc", "db-table", "JDBC", "db:io")
            }
            if (recvType in Classify.EXTERNAL_SIMPLE_TYPES) {
                return ext.resolveImperative(callExpr, recvType, method, enclosingClassDesc(callExpr), yml)
            }
            // Fluent HTTP client chains (WebClient/RestClient): the URL-bearing `.uri(...)`
            // call's direct receiver is a spec type, not the client. Walk to the chain's
            // root receiver — if that is a WebClient/RestClient, treat this as a client call.
            if (method in Classify.FLUENT_URL_METHODS) {
                fluentClientRootType(callExpr)?.let { rootType ->
                    return ext.resolveImperative(callExpr, rootType, method, enclosingClassDesc(callExpr), yml)
                }
            }
            val recvFqcn = simpleToFqcn[recvType]
            if (recvFqcn != null && method in Classify.REPOSITORY_INHERITED_METHODS) {
                return CallResolution.RepositoryInherited(recvFqcn, method)
            }
            return CallResolution.Unresolved
        }

        private fun isAsyncCallee(desc: FunctionDescriptor): Boolean {
            if (desc.isSuspend) return true
            if (desc.annotations.any { it.fqName?.shortName()?.asString() in Classify.ASYNC_ANNOTATIONS }) return true
            val ret = desc.returnType?.constructor?.declarationDescriptor?.name?.asString()
            return ret != null && ret in Classify.ASYNC_RETURN_TYPES
        }

        /** Declared receiver type: resolved type first, else syntactic type text (no classpath). */
        private fun receiverDeclaredType(callExpr: KtCallExpression): String? {
            val parent = callExpr.parent as? KtDotQualifiedExpression ?: return null
            val recv = parent.receiverExpression
            bc.getType(recv)?.constructor?.declarationDescriptor?.name?.asString()?.let { return stripGenerics(it) }
            if (recv is KtNameReferenceExpression) {
                val tgt = bc.get(BindingContext.REFERENCE_TARGET, recv)
                val psi = tgt?.let { DescriptorToSourceUtils.descriptorToDeclaration(it) }
                val typeText = when (psi) {
                    is KtParameter -> psi.typeReference?.text
                    is KtProperty -> psi.typeReference?.text
                        ?: (psi.initializer as? KtCallExpression)?.calleeExpression?.text
                    else -> null
                }
                return typeText?.let { stripGenerics(it) }
            }
            return null
        }

        /**
         * For a fluent HTTP-client chain like `webClient.get().uri(url).retrieve()`, the
         * `.uri(...)` call's immediate receiver is a builder/spec type. Walk leftward
         * through the dot-qualified chain to the root receiver and return its declared
         * type when it is a WebClient/RestClient (else null).
         */
        private fun fluentClientRootType(callExpr: KtCallExpression): String? {
            var node: KtExpression = (callExpr.parent as? KtDotQualifiedExpression) ?: return null
            var root: KtExpression = node
            while (node is KtDotQualifiedExpression) {
                root = node.receiverExpression
                node = root
            }
            bc.getType(root)?.constructor?.declarationDescriptor?.name?.asString()
                ?.let { val t = stripGenerics(it); if (t in Classify.EXTERNAL_SIMPLE_TYPES) return t }
            if (root is KtNameReferenceExpression) {
                val tgt = bc.get(BindingContext.REFERENCE_TARGET, root)
                val psi = tgt?.let { DescriptorToSourceUtils.descriptorToDeclaration(it) }
                val typeText = when (psi) {
                    is KtParameter -> psi.typeReference?.text
                    is KtProperty -> psi.typeReference?.text
                        ?: (psi.initializer as? KtCallExpression)?.calleeExpression?.text
                    else -> null
                }
                typeText?.let { val t = stripGenerics(it); if (t in Classify.EXTERNAL_SIMPLE_TYPES) return t }
            }
            return null
        }

        private fun enclosingClassDesc(el: PsiElement): ClassDescriptor? {
            var p: PsiElement? = el
            while (p != null) {
                if (p is KtClassOrObject) return bc.get(BindingContext.CLASS, p)
                p = p.parent
            }
            return null
        }

        private fun provenance(absPath: String): Pair<String?, String?> {
            val rel = File(absPath).relativeToOrNull(root)?.path ?: return null to null
            val parts = rel.split(File.separator)
            return parts.getOrNull(0) to parts.getOrNull(1)
        }

        // ---- infra resource extraction (PSI; classpath-independent string args) ----

        /** (isEntity, tableName). Default JPA table = entity simple name (lowercased later). */
        private fun entityTableOf(cls: KtClassOrObject): Pair<Boolean, String?> {
            val isEntity = cls.annotationEntries.any { it.shortName?.asString() in Classify.ENTITY_ANNOTATIONS }
            if (!isEntity) return false to null
            val table = cls.annotationEntries.firstOrNull { it.shortName?.asString() == "Table" }
                ?.let { annNamedStringArg(it, "name") }
            return true to (table ?: cls.name)
        }

        /** Entity simple name from `JpaRepository<Entity, Id>` (and friends), via supertype text. */
        private fun repoEntityOf(cls: KtClassOrObject): String? {
            for (st in cls.superTypeListEntries) {
                val txt = st.typeReference?.text ?: continue
                REPO_GENERIC_RE.find(txt)?.let { return it.groupValues[2].substringAfterLast('.') }
            }
            return null
        }

        /** Topics from `KafkaTemplate.send("topic", ...)` / `sendDefault("topic")` calls. */
        private fun kafkaProducedTopics(body: KtExpression): List<String> {
            val out = LinkedHashSet<String>()
            body.collectDescendantsOfType<KtCallExpression>().forEach { call ->
                val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (name !in Classify.KAFKA_SEND_METHODS) return@forEach
                val recv = receiverDeclaredType(call)
                if (recv != null && recv !in Classify.KAFKA_TEMPLATE_TYPES) return@forEach
                val first = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return@forEach
                constEval.resolveStringExpr(first).literal?.let { out.add(it) }
            }
            return out.toList()
        }

        /** Topics from `@KafkaListener(topics = [...])` (else first positional arg). */
        private fun kafkaConsumedTopics(fn: KtNamedFunction): List<String> {
            val entry = fn.annotationEntries
                .firstOrNull { it.shortName?.asString() in Classify.KAFKA_LISTENER_ANNOTATIONS } ?: return emptyList()
            val arg = entry.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "topics" }
                ?: entry.valueArguments.firstOrNull()
            val expr = arg?.getArgumentExpression() ?: return emptyList()
            val exprs = (expr as? KtCollectionLiteralExpression)?.getInnerExpressions() ?: listOf(expr)
            return exprs.mapNotNull { constEval.resolveStringExpr(it).literal }
        }

        private fun annNamedStringArg(entry: KtAnnotationEntry, argName: String): String? {
            val arg = entry.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == argName }
                ?: return null
            val expr = arg.getArgumentExpression() ?: return null
            return constEval.resolveStringExpr(expr).literal
        }
    }

    // ---- PSI helpers (no resolution needed) ----

    private fun collectBatchWiring(body: KtExpression): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        body.collectDescendantsOfType<KtCallExpression>().forEach { call ->
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            val relation = name?.let { Classify.BATCH_WIRING_METHODS[it] } ?: return@forEach
            val argName = call.valueArguments.firstOrNull()?.getArgumentExpression()
                ?.let { (it as? KtNameReferenceExpression)?.getReferencedName() } ?: return@forEach
            out.add(relation to argName)
        }
        return out
    }

    private fun isInAsyncContext(el: PsiElement): Boolean {
        var p: PsiElement? = el.parent
        while (p != null) {
            if (p is KtCallExpression) {
                val name = (p.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if (name != null && name in Classify.ASYNC_BUILDERS) return true
            }
            if (p is KtNamedFunction) break
            p = p.parent
        }
        return false
    }

    private fun visibilityOf(fn: KtNamedFunction): String = when {
        fn.hasModifier(KtTokens.PRIVATE_KEYWORD) -> "private"
        fn.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        fn.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> "public"
    }

    private fun lineOf(el: PsiElement): Int? {
        val text = el.containingFile?.text ?: return null
        val offset = el.textOffset
        if (offset < 0 || offset > text.length) return null
        var line = 1
        for (i in 0 until offset) if (text[i] == '\n') line++
        return line
    }

    private fun stripGenerics(t: String): String =
        t.substringBefore("<").substringAfterLast(".").trim().removeSuffix("?")

    private fun discoverSourceRoots(root: File, projectFilter: String?): List<File> {
        val base = if (projectFilter != null) File(root, projectFilter) else root
        if (!base.isDirectory) return emptyList()
        val srcRoots = base.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter {
                it.isDirectory && (it.name == "kotlin" || it.name == "java") &&
                    it.path.contains("${File.separator}src${File.separator}")
            }
            .toList()
        return srcRoots.ifEmpty { listOf(base) }
    }
}
