package dev.agentry.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * A project-scoped [Disposable] anchor. Anything Agentry registers with the platform
 * (VFS listeners, message-bus connections) parents on this so it's cleaned up when the
 * project closes — without each call site re-implementing lifecycle.
 */
@Service(Service.Level.PROJECT)
class AgentryDisposable : Disposable {
    override fun dispose() {}

    companion object {
        fun forProject(project: Project): Disposable =
            project.getService(AgentryDisposable::class.java)
    }
}
