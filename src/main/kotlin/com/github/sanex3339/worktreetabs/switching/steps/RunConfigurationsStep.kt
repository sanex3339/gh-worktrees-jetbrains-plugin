package com.github.sanex3339.worktreetabs.switching.steps

import com.github.sanex3339.worktreetabs.model.PathRewrite
import com.github.sanex3339.worktreetabs.switching.SwitchContext
import com.github.sanex3339.worktreetabs.switching.SwitchStep
import com.intellij.execution.RunManager
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element

private val LOG = logger<RunConfigurationsStep>()

/**
 * Run configurations follow the switch: every path under the old worktree (package.json, working directory, config
 * files, test files, …) is rewritten to the same place in the new one. Configurations saved in a branch's own
 * `.run.xml` files are left alone: the new worktree's copies load by themselves, and rewriting would change the files.
 */
class RunConfigurationsStep : SwitchStep {
    override val title = "run configurations"
    override val order = 200

    override suspend fun restore(context: SwitchContext, saved: Element?, carried: Element?) = withContext(Dispatchers.EDT) {
        val runManager = RunManager.getInstance(context.project)
        for (settings in runManager.allSettings) {
            if (settings.isStoredInArbitraryFileInProject) continue
            val configuration = settings.configuration
            try {
                val element = Element("configuration").also(configuration::writeExternal)
                val before = JDOMUtil.write(element)
                val after = PathRewrite.replaceRoot(before, context.from, context.to)
                if (after == before) continue
                configuration.readExternal(JDOMUtil.load(after))
                (runManager as? RunManagerImpl)?.fireRunConfigurationChanged(settings)
            } catch (e: Exception) {
                LOG.warn("Can't move run configuration '${settings.name}' to ${context.to}", e)
            }
        }
    }
}
