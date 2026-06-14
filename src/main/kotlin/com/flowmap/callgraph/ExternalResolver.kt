package com.flowmap.callgraph

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

/**
 * Per-client external-call resolution (K1 descriptors).
 * Produces a [CallResolution] carrying service identity (incl. package), HTTP verb,
 * path, and resolved/placeholder URL.
 */
class ExternalResolver(private val constEval: ConstantEvaluator) {

    fun isFeign(cls: ClassDescriptor): Boolean =
        cls.annotations.any { shortName(it) in Classify.FEIGN_CLIENT_ANNOTATIONS }

    /**
     * A Spring 6 HTTP-interface client. Detected by a class-level `@HttpExchange`
     * OR by any method carrying a `@GetExchange`/`@PostExchange`/… annotation —
     * the class-level annotation is optional (it only sets a base path), so
     * interfaces that declare exchanges per-method must still be recognized.
     */
    fun isHttpExchange(cls: ClassDescriptor): Boolean =
        cls.annotations.any { shortName(it) in Classify.HTTP_EXCHANGE_ANNOTATIONS } ||
            cls.unsubstitutedMemberScope.getContributedDescriptors()
                .filterIsInstance<FunctionDescriptor>()
                .any { fn -> fn.annotations.any { shortName(it) in Classify.EXCHANGE_VERBS.keys } }

    fun resolveDeclarative(
        callee: FunctionDescriptor,
        owner: ClassDescriptor,
        yml: YamlPropertyResolver,
    ): CallResolution.DeclarativeClient {
        val feign = owner.annotations.firstOrNull { shortName(it) in Classify.FEIGN_CLIENT_ANNOTATIONS }
        val service: String?
        val baseExpr: String?
        if (feign != null) {
            service = ConstantEvaluator.firstStringArg(feign, "name", "value", "contextId")
                ?: owner.name.asString()
            baseExpr = ConstantEvaluator.firstStringArg(feign, "url")
        } else {
            val ex = owner.annotations.firstOrNull { shortName(it) in Classify.HTTP_EXCHANGE_ANNOTATIONS }
            service = owner.name.asString()
            baseExpr = ex?.let { ConstantEvaluator.firstStringArg(it, "url", "value") }
        }
        val (verb, path) = mappingOf(callee)
        val placeholder = baseExpr?.takeIf { it.contains("\${") }
        val resolvedBase = yml.resolve(baseExpr) ?: baseExpr
        return CallResolution.DeclarativeClient(
            calleeFqcn = owner.fqNameSafe.asString(),
            simpleName = owner.name.asString(),
            method = callee.name.asString(),
            service = service,
            clientPackage = owner.fqNameSafe.parent().asString().ifEmpty { null },
            httpMethod = verb,
            path = path,
            url = composeUrl(resolvedBase, path),
            urlPlaceholder = placeholder,
        )
    }

    fun resolveImperative(
        callExpr: KtCallExpression,
        clientType: String,
        method: String,
        enclosing: ClassDescriptor?,
        yml: YamlPropertyResolver,
    ): CallResolution.ImperativeClient {
        val urlArg = callExpr.valueArguments
            .mapNotNull { it.getArgumentExpression() }
            .map { constEval.resolveUrlBuildingCall(it) }
            .firstOrNull { !it.isEmpty } ?: ResolvedString.NONE

        val verb = httpVerbInChain(callExpr)
        var url = urlArg.literal
        var placeholder = urlArg.placeholder
        if (url != null && url.startsWith("/") && enclosing != null) {
            baseUrlFromValueField(enclosing, yml)?.let { (base, ph) ->
                url = composeUrl(base, url); placeholder = placeholder ?: ph
            }
        } else if (url == null && placeholder != null) {
            url = placeholder
        }
        return CallResolution.ImperativeClient(
            clientType = clientType,
            clientPackage = enclosing?.fqNameSafe?.parent()?.asString()?.ifEmpty { null },
            method = method,
            service = enclosing?.name?.asString(),
            httpMethod = verb,
            url = url,
            urlPlaceholder = placeholder,
        )
    }

    fun mappingOf(fn: FunctionDescriptor): Pair<String?, String?> {
        for (ann in fn.annotations) {
            val short = shortName(ann) ?: continue
            Classify.MAPPING_VERBS[short]?.let { return it to ConstantEvaluator.firstStringArg(ann, "value", "path") }
            Classify.EXCHANGE_VERBS[short]?.let { return it to ConstantEvaluator.firstStringArg(ann, "value", "url") }
        }
        return null to null
    }

    /**
     * Swagger/OpenAPI endpoint description, so a project documented with Swagger
     * annotations yields the same endpoint descriptions as Spring REST Docs.
     * Reads springdoc/OAS-v3 `@Operation(summary|description)` and the legacy
     * springfox `@ApiOperation(value|notes)`. (Requires the swagger-annotations
     * jar on the analysis classpath to resolve the annotation values.)
     */
    fun apiDescriptionOf(fn: FunctionDescriptor): String? {
        for (ann in fn.annotations) when (shortName(ann)) {
            "Operation" -> return ConstantEvaluator.firstStringArg(ann, "summary", "description")?.ifBlank { null }
            "ApiOperation" -> return ConstantEvaluator.firstStringArg(ann, "value", "notes")?.ifBlank { null }
        }
        return null
    }

    fun basePathOf(cls: ClassDescriptor): String? {
        for (ann in cls.annotations) {
            when (shortName(ann)) {
                "RequestMapping", "HttpExchange" ->
                    return ConstantEvaluator.firstStringArg(ann, "value", "path", "url")
            }
        }
        return null
    }

    // ---- internals ----

    private fun baseUrlFromValueField(cls: ClassDescriptor, yml: YamlPropertyResolver): Pair<String?, String?>? {
        val scopes = buildList {
            add(cls)
            cls.typeConstructor.supertypes.forEach { st ->
                (st.constructor.declarationDescriptor as? ClassDescriptor)?.let { add(it) }
            }
        }
        for (c in scopes) {
            val props = c.unsubstitutedMemberScope.getContributedDescriptors()
                .filterIsInstance<PropertyDescriptor>()
                .filter { p -> p.annotations.any { shortName(it) == "Value" } }
            val pick = props.firstOrNull { it.name.asString().contains("base", ignoreCase = true) }
                ?: props.firstOrNull() ?: continue
            val ann = pick.annotations.first { shortName(it) == "Value" }
            val ph = ConstantEvaluator.firstStringArg(ann) ?: continue
            val placeholder = ph.takeIf { it.contains("\${") }
            return (yml.resolve(ph) ?: ph) to placeholder
        }
        return null
    }

    private fun httpVerbInChain(callExpr: KtCallExpression): String? {
        // Walk up to the outermost fluent-chain expression so receiver-side verb
        // methods (`webClient.get()...`) are visible, not just this call's own text.
        var top: org.jetbrains.kotlin.psi.KtExpression = callExpr
        var p = callExpr.parent
        while (p is org.jetbrains.kotlin.psi.KtDotQualifiedExpression || p is KtCallExpression) {
            (p as? org.jetbrains.kotlin.psi.KtExpression)?.let { top = it }
            p = p.parent
        }
        val text = top.text
        return listOf(
            "RequestEntity.post" to "POST", "RequestEntity.get" to "GET",
            "RequestEntity.put" to "PUT", "RequestEntity.delete" to "DELETE",
            "postForObject" to "POST", "getForObject" to "GET",
            "postForEntity" to "POST", "getForEntity" to "GET",
            ".post(" to "POST", ".get(" to "GET", ".put(" to "PUT",
            ".delete(" to "DELETE", ".patch(" to "PATCH",
        ).firstOrNull { text.contains(it.first) }?.second
    }

    private fun shortName(ann: org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor): String? =
        ann.fqName?.shortName()?.asString()

    private fun composeUrl(base: String?, path: String?): String? {
        if (base.isNullOrEmpty() && path.isNullOrEmpty()) return null
        val b = (base ?: "").trimEnd('/')
        var p = path ?: ""
        if (p.isNotEmpty() && !p.startsWith("/")) p = "/$p"
        return (b + p).ifEmpty { null }
    }
}
