package com.github.sheasmith.backpack

import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.view.getSelectedSingleNode
import com.intellij.database.view.getSelectionRelatedSingleDataSource
import com.intellij.database.view.structure.DvTreeNodeRank
import com.intellij.database.view.structure.treeNodeRank
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.net.URI

internal class ImportBacpacAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        var applicable = false
        val dc = e.dataContext
        val singleNode = dc.getSelectedSingleNode()
        if (singleNode != null) {
            val rank = singleNode.treeNodeRank
            applicable = rank == DvTreeNodeRank.TL_ROOT
        }

        if (applicable) {
            val ds = dc.getSelectionRelatedSingleDataSource()?.localDataSource
            val url = ds?.url
            applicable = url != null && url.startsWith("jdbc:sqlserver:")
        }

        e.presentation.isVisible = applicable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dc = e.dataContext
        val dataSource = dc.getSelectionRelatedSingleDataSource()!!.localDataSource!!

        val uri = URI(dataSource.url!!.removePrefix("jdbc:sqlserver:"))
        val host = uri.host
        var port: Int? = uri.port
        if (port == -1) port = null

        // 1) Pick a .bacpac file (can be outside the project)
        val openDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("bacpac")
        val vFile = FileChooser.chooseFile(openDescriptor, project, null) ?: return
        val sourcePath = vFile.path

        // 2) Ask for target database name
        val defaultDbName = vFile.nameWithoutExtension
        val dbName = Messages.showInputDialog(
            project,
            "Enter the target database name:",
            "Import BACPAC",
            Messages.getQuestionIcon(),
            defaultDbName,
            null
        )?.trim()
        if (dbName.isNullOrEmpty()) return

        // 3) Build SqlPackage command for Import and run in Run tool window
        val commands = mutableListOf(
            "SqlPackage",
            "/Action:Import",
            "/SourceFile:$sourcePath",
            "/TargetServerName:$host${port?.let { ",${it}" } ?: ""}",
            "/TargetDatabaseName:$dbName",
            "/TargetEncryptConnection:false"
        )

        if (dataSource.authProviderId == "ms-sso") {
            // commands.add("/UniversalAuthentication:true")
        } else {
            val credentials = DatabaseCredentials.getInstance().getCredentials(dataSource)
            // Not used yet; relying on integrated auth/SSO or saved creds in environment
        }

        val commandLine = GeneralCommandLine(commands)
        val handler = OSProcessHandler(commandLine)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)

        val descriptor = RunContentDescriptor(console, handler, console.component, "Import BACPAC")
        descriptor.isActivateToolWindowWhenAdded = true
        RunContentManager.getInstance(project)
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        handler.startNotify()
    }
}