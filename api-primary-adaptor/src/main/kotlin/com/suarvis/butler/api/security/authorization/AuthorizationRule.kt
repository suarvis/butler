package com.suarvis.butler.api.security.authorization

/**
 * A request-level policy enforced by the primary adaptor before any use case runs. In the CLI the
 * caller is always the local OS user, so rules guard *what* is requested rather than *who* asks;
 * the same interface carries user/role rules once a web adaptor exists.
 */
interface AuthorizationRule<in R> {
    fun evaluate(request: R): AuthorizationDecision

    fun authorize(request: R) {
        val decision = evaluate(request)
        if (decision is AuthorizationDecision.Denied) throw AuthorizationDeniedException(decision)
    }
}

sealed interface AuthorizationDecision {
    data object Granted : AuthorizationDecision

    /** [kind] is the machine-readable error kind surfaced to scripts (e.g. `broad_cwd`). */
    data class Denied(val kind: String, val reason: String, val hint: String?) : AuthorizationDecision
}

class AuthorizationDeniedException(val decision: AuthorizationDecision.Denied) : RuntimeException(decision.reason)
