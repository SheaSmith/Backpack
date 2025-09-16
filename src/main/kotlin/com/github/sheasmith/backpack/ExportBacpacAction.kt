package com.github.sheasmith.backpack

import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.view.getSelectedDbElementFromEditor
import com.intellij.database.view.getSelectedSingleNode
import com.intellij.database.view.getSelectionRelatedSingleDataSource
import com.intellij.database.view.structure.DvTreeNodeRank
import com.intellij.database.view.structure.treeNodeRank
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import java.net.URI

internal class ExportBacpacAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        var applicable = false
        val dc = e.dataContext
        val singleNode = dc.getSelectedSingleNode()
        if (singleNode != null) {
            val rank = singleNode.treeNodeRank
            applicable = rank == DvTreeNodeRank.TL_NAMESPACE
        }

        if (applicable) {
            val ds = dc.getSelectionRelatedSingleDataSource()?.localDataSource
            val url = ds?.url
            applicable = url != null && url.startsWith("jdbc:sqlserver:")
        }

        e.presentation.isVisible = applicable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dc = e.dataContext
        val dataSource = dc.getSelectionRelatedSingleDataSource()!!.localDataSource!!

        val uri = URI(dataSource.url!!.removePrefix("jdbc:sqlserver:"))
        val host = uri.host
        var port: Int? = uri.port

        // Descriptor for selecting a single file (can be outside the project)
        val saveDescriptor = FileSaverDescriptor("Save BACPAC As", "Choose location and filename", "bacpac")
        val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(saveDescriptor, e.project)

        // Suggest a default filename based on the selected DB
        val suggestedName = dc.getSelectedDbElementFromEditor()!!.name + ".bacpac"

        // Show the Save As dialog; null base means any location on disk
        val saved = saveDialog.save(null as java.nio.file.Path?, suggestedName) ?: return
        val initialPath = saved.file.absolutePath ?: return

        // Sanitize path: remove trailing separators, ensure filename and .bacpac extension
        var targetPath = initialPath.trimEnd('\\','/')
        val targetFile = java.io.File(targetPath)
        if (targetFile.isDirectory) {
            // If a directory was selected, append the suggested filename
            val ensuredName = if (suggestedName.endsWith(".bacpac", ignoreCase = true)) suggestedName else "$suggestedName.bacpac"
            targetPath = java.io.File(targetFile, ensuredName).absolutePath
        }
        if (!targetPath.endsWith(".bacpac", ignoreCase = true)) {
            targetPath += ".bacpac"
        }

        if (port == -1)
            port = null

        val commands = mutableListOf(
            "SqlPackage",
            "/Action:Export",
            "/TargetFile:$targetPath",
            "/SourceServerName:$host${port?.let { ",$it" } ?: ""}",
            "/SourceDatabaseName:${dc.getSelectedDbElementFromEditor()!!.name}",
            "/SourceEncryptConnection:false"
        )

        if (dataSource.authProviderId == "ms-sso") {
//            commands.add("/UniversalAuthentication:true")
        }
        else {
            val credentials = DatabaseCredentials.getInstance().getCredentials(dataSource)
        }

        val project = e.project ?: return

        // Run the command in a background process and show output in the Run tool window
        val commandLine = GeneralCommandLine(commands)
        val handler = OSProcessHandler(commandLine)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)

        val descriptor = RunContentDescriptor(console, handler, console.component, "Export BACPAC")
        descriptor.isActivateToolWindowWhenAdded = true
        RunContentManager.getInstance(project)
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)


        handler.startNotify()
    }

}