package com.github.sanex3339.worktreetabs.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object WorktreeNotifications {
    fun warn(project: Project, text: String) = notify(project, text, NotificationType.WARNING)

    fun error(project: Project, text: String) = notify(project, text, NotificationType.ERROR)

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Worktree Tabs").createNotification(text, type).notify(project)
    }
}
