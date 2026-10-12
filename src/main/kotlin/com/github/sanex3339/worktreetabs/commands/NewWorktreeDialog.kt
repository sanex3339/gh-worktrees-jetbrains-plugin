package com.github.sanex3339.worktreetabs.commands

import com.github.sanex3339.worktreetabs.model.NewWorktree
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import java.io.File
import javax.swing.JComponent

/**
 * New Worktree: a new branch from a base (the current branch by default) or an existing local branch that no worktree
 * has checked out, a location (next to the main checkout until edited by hand), and whether to switch to it.
 */
class NewWorktreeDialog(
    project: Project,
    private val mainCheckout: String,
    private val freeBranches: List<String>,
    currentBranch: String?,
) : DialogWrapper(project) {
    private var newBranch = true
    private var branchName = ""
    private var existingBranch: String? = freeBranches.firstOrNull()
    private var base = currentBranch ?: "HEAD"
    private var location = ""
    private var switchToIt = true

    private lateinit var newBranchRadio: JBRadioButton
    private lateinit var branchField: JBTextField
    private lateinit var locationField: TextFieldWithBrowseButton
    private var suggestedLocation = ""

    init {
        title = "New Worktree"
        setOKButtonText("Create")
        init()
    }

    val request: WorktreeCommands.NewWorktreeRequest
        get() = WorktreeCommands.NewWorktreeRequest(
            location = FileUtil.toSystemIndependentName(location.trim()),
            branch = if (newBranch) branchName.trim() else existingBranch.orEmpty(),
            newBranch = newBranch,
            base = base.trim().takeIf { newBranch && it.isNotEmpty() },
            switchToIt = switchToIt,
        )

    override fun createCenterPanel(): JComponent = panel {
        buttonsGroup {
            row {
                val newBranchCell = radioButton("New branch:", true)
                newBranchRadio = newBranchCell.component
                branchField = textField().bindText(::branchName).columns(24).focused().enabledIf(newBranchCell.selected)
                    .onChanged { suggestLocation() }.component
                label("from")
                textField().bindText(::base).columns(12).enabledIf(newBranchCell.selected)
            }
            if (freeBranches.isNotEmpty()) {
                row {
                    val existing = radioButton("Existing branch:", false)
                    comboBox(freeBranches).bindItem(::existingBranch.toNullableProperty()).enabledIf(existing.selected)
                        .onChanged { existingBranch = it.selectedItem as? String; suggestLocation() }
                    existing.component.addActionListener { suggestLocation() }
                }
            }
        }.bind(::newBranch)
        newBranchRadio.addActionListener { suggestLocation() }
        row("Location:") {
            locationField = textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Worktree Location"))
                .bindText(::location).align(AlignX.FILL).component
        }
        row {
            checkBox("Switch to it").bindSelected(::switchToIt)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = branchField

    /** Keeps the location next to the main checkout and named after the branch, until it is edited by hand. */
    private fun suggestLocation() {
        if (!::locationField.isInitialized) return
        val current = locationField.text
        if (current.isNotEmpty() && current != suggestedLocation) return
        val branch = if (newBranchRadio.isSelected) branchField.text else existingBranch.orEmpty()
        suggestedLocation = if (branch.isBlank()) "" else FileUtil.toSystemDependentName(NewWorktree.defaultLocation(mainCheckout, branch))
        locationField.text = suggestedLocation
    }

    override fun doValidate(): ValidationInfo? {
        if (newBranchRadio.isSelected) NewWorktree.branchNameProblem(branchField.text.trim())?.let { return ValidationInfo(it, branchField) }
        else if (existingBranch == null) return ValidationInfo("Choose a branch")
        val path = locationField.text.trim()
        if (path.isEmpty()) return ValidationInfo("Choose a location", locationField.textField)
        val folder = File(path)
        if (folder.exists() && (folder.list()?.isNotEmpty() == true)) return ValidationInfo("The folder exists and isn't empty", locationField.textField)
        return null
    }
}
