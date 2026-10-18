package com.suarvis.butler.api.security.authorization.rules

import com.suarvis.butler.api.security.authorization.AuthorizationDecision
import com.suarvis.butler.api.security.authorization.AuthorizationRule
import org.springframework.stereotype.Component
import java.nio.file.Path

data class WorkingDirectoryRequest(
    val directory: Path,
    val homeDirectory: Path,
    val allowBroadDirectory: Boolean,
)

/**
 * Clawcode's broad-cwd guard: refuse to treat the home folder or the filesystem root as a
 * workspace unless explicitly allowed, because sessions and (later) file tools are scoped to it.
 */
@Component
class BroadWorkingDirectoryRule : AuthorizationRule<WorkingDirectoryRequest> {
    override fun evaluate(request: WorkingDirectoryRequest): AuthorizationDecision {
        if (request.allowBroadDirectory) return AuthorizationDecision.Granted
        val description =
            when {
                request.directory.parent == null -> "the filesystem root"
                request.directory == request.homeDirectory -> "your home folder"
                else -> return AuthorizationDecision.Granted
            }
        return AuthorizationDecision.Denied(
            kind = "broad_cwd",
            reason = "Refusing to use ${request.directory} as a workspace because it is $description",
            hint = "Run butler from a project folder, pass --cwd PATH, or add --allow-broad-cwd.",
        )
    }
}
