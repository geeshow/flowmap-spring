package com.flowmap.callgraph

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.types.TypeUtils

/**
 * Compile-time string resolution via the K1 frontend (BindingContext).
 *
 * The semantic win over regex: follows `const val` / companion / `val` / `@Value`
 * / `@ConfigurationProperties` references to the actual literal a URL is built from.
 * `BindingContext.COMPILE_TIME_VALUE` already inlines `const` references and folds
 * constant string concatenation, so most cases resolve in one lookup.
 */

data class ResolvedString(val literal: String?, val placeholder: String?) {
    val isEmpty get() = literal == null && placeholder == null
    companion object { val NONE = ResolvedString(null, null) }
}

private val VALUE_FQN = FqName("org.springframework.beans.factory.annotation.Value")
private val CONFIG_PROPS_FQN = FqName("org.springframework.boot.context.properties.ConfigurationProperties")

class ConstantEvaluator(private val bc: BindingContext) {

    fun resolveStringExpr(expr: KtExpression?, depth: Int = 0): ResolvedString {
        if (expr == null || depth > 12) return ResolvedString.NONE
        // 1) compile-time constant: literals, const val refs, const concat, "${..}" literal.
        compileTimeString(expr)?.let { return classify(it) }
        // 2) follow references the constant evaluator can't fold (@Value, runtime val, props).
        return when (expr) {
            is KtNameReferenceExpression -> resolveReference(expr, depth)
            is KtDotQualifiedExpression -> resolveDotQualified(expr, depth)
            // `base + path` concatenation the compiler can't fold (e.g. a @Value base).
            is KtBinaryExpression -> resolveConcat(expr, depth)
            else -> ResolvedString.NONE
        }
    }

    /**
     * String `+` concatenation where the constant evaluator couldn't fold the whole
     * expression (typically a runtime `@Value` base + a constant path). Combines the
     * resolved operands; a resolved literal absolute path (`/...`) is returned on its
     * own so the imperative resolver can compose it with the client's base URL.
     */
    private fun resolveConcat(expr: KtBinaryExpression, depth: Int): ResolvedString {
        if (expr.operationToken != org.jetbrains.kotlin.lexer.KtTokens.PLUS) return ResolvedString.NONE
        val left = resolveStringExpr(expr.left, depth + 1)
        val right = resolveStringExpr(expr.right, depth + 1)
        // Prefer a literal absolute path operand (host comes from the client's base URL).
        right.literal?.takeIf { it.startsWith("/") }?.let { return ResolvedString(it, null) }
        left.literal?.takeIf { it.startsWith("/") }?.let { return ResolvedString(it, null) }
        // Both literal → straight concatenation.
        if (left.literal != null && right.literal != null) {
            return classify(left.literal + right.literal)
        }
        // base placeholder + literal path → keep placeholder host, append literal path.
        val ph = left.placeholder ?: right.placeholder
        val lit = left.literal ?: right.literal
        if (ph != null && lit != null) return ResolvedString(null, ph + lit)
        return if (!left.isEmpty) left else right
    }

    /** URI(X) / UriComponentsBuilder.fromUriString(X) / RequestEntity.get(...) → resolve X. */
    fun resolveUrlBuildingCall(expr: KtExpression?, depth: Int = 0): ResolvedString {
        expr ?: return ResolvedString.NONE
        if (depth > 16) return ResolvedString.NONE
        val direct = resolveStringExpr(expr, depth)
        if (!direct.isEmpty) return direct
        for (child in expr.children) {
            if (child is KtExpression) {
                val r = resolveUrlBuildingCall(child, depth + 1)
                if (!r.isEmpty) return r
            }
        }
        return ResolvedString.NONE
    }

    private fun classify(s: String): ResolvedString =
        if (s.contains("\${")) ResolvedString(null, s) else ResolvedString(s, null)

    private fun compileTimeString(expr: KtExpression): String? =
        bc.get(BindingContext.COMPILE_TIME_VALUE, expr)
            ?.getValue(TypeUtils.NO_EXPECTED_TYPE) as? String

    private fun resolveReference(expr: KtNameReferenceExpression, depth: Int): ResolvedString {
        val target = bc.get(BindingContext.REFERENCE_TARGET, expr) as? VariableDescriptor
            ?: return ResolvedString.NONE
        // @Value("${...}") field → that placeholder (property fields only)
        (target as? PropertyDescriptor)?.let { valuePlaceholderOf(it)?.let { ph -> return ResolvedString(null, ph) } }
        // val with a (possibly non-const) literal initializer → follow PSI. Covers both
        // class properties and local `val`s (`val path = SomePaths.X; val url = base + path`).
        val psi = DescriptorToSourceUtils.descriptorToDeclaration(target) as? KtProperty
        psi?.initializer?.let { return resolveStringExpr(it, depth + 1) }
        return ResolvedString.NONE
    }

    private fun resolveDotQualified(expr: KtDotQualifiedExpression, depth: Int): ResolvedString {
        (expr.selectorExpression as? KtNameReferenceExpression)?.let { sel ->
            val direct = resolveReference(sel, depth)
            if (!direct.isEmpty) return direct
        }
        // @ConfigurationProperties chain → "${prefix.a.b}"
        configPropertyPlaceholder(expr)?.let { return ResolvedString(null, it) }
        return ResolvedString.NONE
    }

    private fun valuePlaceholderOf(desc: PropertyDescriptor): String? {
        val ann = desc.annotations.findAnnotation(VALUE_FQN) ?: return null
        return firstStringArg(ann)
    }

    /** Best-effort: teraUserProperties.niceBank.serverUrl → ${tera.user.nice-bank.server-url}. */
    private fun configPropertyPlaceholder(expr: KtDotQualifiedExpression): String? {
        val parts = ArrayList<String>()
        var cur: KtExpression? = expr
        var root: KtNameReferenceExpression? = null
        while (cur is KtDotQualifiedExpression) {
            (cur.selectorExpression as? KtNameReferenceExpression)?.let { parts.add(it.getReferencedName()) }
            val rec = cur.receiverExpression
            if (rec is KtNameReferenceExpression) { root = rec; break }
            cur = rec
        }
        root ?: return null
        parts.reverse()
        val rootDesc = bc.get(BindingContext.REFERENCE_TARGET, root) as? PropertyDescriptor ?: return null
        val cls = rootDesc.type.constructor.declarationDescriptor as? ClassDescriptor ?: return null
        val prefix = cls.annotations.findAnnotation(CONFIG_PROPS_FQN)?.let { firstStringArg(it, "prefix", "value") }
            ?: return null
        return "\${$prefix.${parts.joinToString(".")}}"
    }

    companion object {
        /** Read a string annotation argument by name (else the first/`value` argument). */
        fun firstStringArg(ann: AnnotationDescriptor, vararg names: String): String? {
            for (n in names) {
                stringOf(ann.allValueArguments[Name.identifier(n)])?.let { return it }
            }
            stringOf(ann.allValueArguments[Name.identifier("value")])?.let { return it }
            return ann.allValueArguments.values.firstNotNullOfOrNull { stringOf(it) }
        }

        /**
         * Unwrap a constant to its first string literal. Spring mapping annotations
         * declare `String[] value()/path()`, so a resolved `@GetMapping("/x")` arrives
         * as an [ArrayValue] of [StringValue]s, not a bare [StringValue].
         */
        private fun stringOf(v: ConstantValue<*>?): String? = when (v) {
            is StringValue -> v.value
            is ArrayValue -> v.value.firstNotNullOfOrNull { stringOf(it) }
            else -> null
        }
    }
}
