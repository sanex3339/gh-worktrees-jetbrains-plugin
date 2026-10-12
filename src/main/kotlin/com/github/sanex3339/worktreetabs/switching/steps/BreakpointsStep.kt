package com.github.sanex3339.worktreetabs.switching.steps

import com.github.sanex3339.worktreetabs.model.WorktreePaths
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.lang.Language
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element

private val LOG = logger<BreakpointsStep>()

/**
 * Each worktree keeps its own line breakpoints: the old worktree's leave the Breakpoints list with it and come back
 * when it is active again; a first visit gets copies of them. A breakpoint whose type is unknown stays where it is.
 */
class BreakpointsStep : SwitchStep {
    override val title = "breakpoints"
    override val order = 120
    override val snapshotKey = "breakpoints"

    override suspend fun capture(context: SwitchContext): Element? = withContext(Dispatchers.EDT) {
        val manager = XDebuggerManager.getInstance(context.project).breakpointManager
        val mine = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
            .filter { it.fileUrl == context.fromUrl || WorktreePaths.isStrictlyUnder(it.fileUrl, context.fromUrl) }
        val snapshot = Element(ROOT)
        val captured = mutableListOf<XLineBreakpoint<*>>()
        for (breakpoint in mine) {
            val element = runCatching { write(breakpoint) }.onFailure { LOG.warn("Can't save breakpoint ${breakpoint.fileUrl}:${breakpoint.line}", it) }.getOrNull() ?: continue
            snapshot.addContent(element)
            captured += breakpoint
        }
        if (captured.isNotEmpty()) edtWriteAction { captured.forEach(manager::removeBreakpoint) }
        snapshot
    }

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) {
        val snapshot = saved ?: carried?.let { movedTo(it, context) } ?: return
        val manager = XDebuggerManager.getInstance(context.project).breakpointManager
        val types = XBreakpointType.EXTENSION_POINT_NAME.extensionList.filterIsInstance<XLineBreakpointType<*>>()
        withContext(Dispatchers.EDT) {
            for (element in snapshot.getChildren("breakpoint")) {
                val type = types.firstOrNull { it.id == element.getAttributeValue("type") } ?: continue
                runCatching { add(manager, type, element) }.onFailure { LOG.warn("Can't restore breakpoint ${element.getAttributeValue("url")}", it) }
            }
        }
    }

    private fun write(breakpoint: XLineBreakpoint<*>): Element = Element("breakpoint").apply {
        setAttribute("type", breakpoint.type.id)
        setAttribute("url", breakpoint.fileUrl)
        setAttribute("line", breakpoint.line.toString())
        setAttribute("enabled", breakpoint.isEnabled.toString())
        setAttribute("suspend", breakpoint.suspendPolicy.name)
        setAttribute("log-message", breakpoint.isLogMessage.toString())
        setAttribute("log-stack", breakpoint.isLogStack.toString())
        setAttribute("temporary", breakpoint.isTemporary.toString())
        breakpoint.conditionExpression?.let { addContent(expression("condition", it)) }
        breakpoint.logExpressionObject?.let { addContent(expression("log-expression", it)) }
        breakpoint.properties?.state?.let { addContent(Element("properties").addContent(XmlSerializer.serialize(it))) }
    }

    private fun expression(name: String, expression: XExpression): Element = Element(name).apply {
        setAttribute("text", expression.expression)
        expression.language?.id?.let { setAttribute("language", it) }
        expression.customInfo?.let { setAttribute("custom-info", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <P : XBreakpointProperties<*>> add(manager: XBreakpointManager, type: XLineBreakpointType<P>, element: Element) {
        val url = element.getAttributeValue("url")
        val line = element.getAttributeValue("line").toInt()
        val properties = VirtualFileManager.getInstance().findFileByUrl(url)?.let { type.createBreakpointProperties(it, line) }
        val state = element.getChild("properties")?.children?.firstOrNull()
        val current = properties?.state
        if (state != null && current != null) (properties as XBreakpointProperties<Any>).loadState(XmlSerializer.deserialize(state, current.javaClass))
        val breakpoint = edtWriteAction { manager.addLineBreakpoint(type, url, line, properties, element.getAttributeValue("temporary") == "true") }
        breakpoint.isEnabled = element.getAttributeValue("enabled") != "false"
        breakpoint.suspendPolicy = runCatching { SuspendPolicy.valueOf(element.getAttributeValue("suspend")) }.getOrDefault(breakpoint.suspendPolicy)
        breakpoint.isLogMessage = element.getAttributeValue("log-message") == "true"
        breakpoint.isLogStack = element.getAttributeValue("log-stack") == "true"
        element.getChild("condition")?.let { breakpoint.conditionExpression = expression(it) }
        element.getChild("log-expression")?.let { breakpoint.logExpressionObject = expression(it) }
    }

    private fun expression(element: Element): XExpression = XDebuggerUtil.getInstance().createExpression(
        element.getAttributeValue("text").orEmpty(),
        element.getAttributeValue("language")?.let(Language::findLanguageByID),
        element.getAttributeValue("custom-info"),
        EvaluationMode.EXPRESSION,
    )

    private fun movedTo(snapshot: Element, context: SwitchContext): Element = snapshot.clone().apply {
        for (breakpoint in getChildren("breakpoint")) {
            breakpoint.setAttribute("url", WorktreePaths.rebase(breakpoint.getAttributeValue("url"), context.fromUrl, context.toUrl))
        }
    }

    private companion object {
        const val ROOT = "breakpoints"
    }
}
