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
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
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
            return IrType(
                fqcn = cls.fqName?.asString() ?: (cls.name ?: "?"),
                simpleName = cls.name ?: "?",
                packageName = kt.packageFqName.asString(),
                kind = if (cls is org.jetbrains.kotlin.psi.KtClass && cls.isInterface()) "interface"
                else if (cls is org.jetbrains.kotlin.psi.KtObjectDeclaration) "object" else "class",
                annotationSimpleNames = annNames,
                supertypeSimpleNames = superNames,
                baseRequestPath = desc?.let { ext.basePathOf(it) },
                isFeign = desc?.let { ext.isFeign(it) } ?: false,
                isHttpExchange = desc?.let { ext.isHttpExchange(it) } ?: false,
                functions = funcs,
                file = relPath,
                line = lineOf(cls),
            )
        }

        private fun buildFunction(fn: KtNamedFunction, yml: YamlPropertyResolver): IrFunction {
            val desc = bc.get(BindingContext.FUNCTION, fn)
            val annNames = desc?.annotations?.mapNotNull { it.fqName?.shortName()?.asString() }?.toSet().orEmpty()
            val (verb, path) = desc?.let { ext.mappingOf(it) } ?: (null to null)
            val ret = desc?.returnType?.constructor?.declarationDescriptor?.name?.asString()
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
                isBean = "Bean" in annNames,
                line = lineOf(fn),
                calls = calls,
                batchWiring = batch,
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
            if (recvType in Classify.EXTERNAL_SIMPLE_TYPES) {
                return ext.resolveImperative(callExpr, recvType, method, enclosingClassDesc(callExpr), yml)
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
