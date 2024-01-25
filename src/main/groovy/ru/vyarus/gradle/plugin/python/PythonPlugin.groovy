package ru.vyarus.gradle.plugin.python

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import ru.vyarus.gradle.plugin.python.cmd.docker.DockerFactory
import ru.vyarus.gradle.plugin.python.task.BasePythonTask
import ru.vyarus.gradle.plugin.python.task.CheckPythonTask
import ru.vyarus.gradle.plugin.python.task.PythonTask
import ru.vyarus.gradle.plugin.python.task.pip.BasePipTask
import ru.vyarus.gradle.plugin.python.task.pip.PipInstallTask
import ru.vyarus.gradle.plugin.python.task.pip.PipListTask
import ru.vyarus.gradle.plugin.python.task.pip.PipUpdatesTask
import ru.vyarus.gradle.plugin.python.task.pipx.BasePipxTask
import ru.vyarus.gradle.plugin.python.task.pipx.PipxInstallTask
import ru.vyarus.gradle.plugin.python.task.pipx.PipxListTask
import ru.vyarus.gradle.plugin.python.task.pipx.PipxUpdatesTask
import ru.vyarus.gradle.plugin.python.util.RequirementsReader

/**
 * Use-python plugin. Plugin requires python installed globally or configured path to python binary.
 * Alternatively, docker might be used.
 * <p>
 * Used to install required pip modules or revert installed versions if older required with {@code pipInstall} task
 * (guarantee exact modules versions). And use python modules, scripts, commands during gradle build
 * with {@link PythonTask}.
 * <p>
 * Also, plugin may be used as a base for building gradle plugin for specific python module.
 *
 * @author Vyacheslav Rusakov
 * @since 11.11.2017
 */
@CompileStatic
@SuppressWarnings('DuplicateStringLiteral')
class PythonPlugin implements Plugin<Project> {

    @Override
    @CompileStatic(TypeCheckingMode.SKIP)
    void apply(Project project) {
        PythonExtension extension = project.extensions.create('python', PythonExtension, project)

        // simplify direct tasks usage
        project.extensions.extraProperties.set(PipInstallTask.simpleName, PipInstallTask)
        project.extensions.extraProperties.set(PythonTask.simpleName, PythonTask)
        // configuration shortcut
        PythonExtension.Scope.values().each { project.extensions.extraProperties.set(it.name(), it) }

        // validate installed python
        TaskProvider<CheckPythonTask> checkTask = project.tasks.register('checkPython', CheckPythonTask) {
            it.with {
                description = 'Validate python environment'
            }
        }

        // default pip install task
        TaskProvider<PipInstallTask> installPipTask = project.tasks.register('pipInstall', PipInstallTask) {
            it.with {
                description = 'Install pip modules'
            }
        }
        TaskProvider<PipxInstallTask> installPipxTask = project.tasks.register('pixpInstall', PipxInstallTask) {
            it.with {
                description = 'Install pipx modules'
            }
        }
        project.tasks.register('pipUpdates', PipUpdatesTask) {
            it.with {
                description = 'Check if new versions available for declared pip modules'
            }
        }
        project.tasks.register('pipxUpdates', PipxUpdatesTask) {
            it.with {
                description = 'Check if new versions available for declared pipx modules'
            }
        }
        project.tasks.register('pipList', PipListTask) {
            it.with {
                description = 'Show all installed modules'
            }
        }
        project.tasks.register("pipxList", PipxListTask) {
            it.with {
                description = 'Show all installed modules'
            }
        }
        project.tasks.register('cleanPython', Delete) {
            it.with {
                group = 'python'
                description = 'Removes existing python environment (virtualenv)'
                delete extension.envPath
                onlyIf { project.file(extension.envPath).exists() }
            }
        }

        configureDefaults(project, extension, checkTask, installPipTask)
        configureDocker(project)
    }

    @SuppressWarnings('MethodSize')
    @CompileStatic(TypeCheckingMode.SKIP)
    private void configureDefaults(Project project,
                                   PythonExtension extension,
                                   TaskProvider<CheckPythonTask> checkTask,
                                   TaskProvider<PipInstallTask> installPipTask) {

        project.tasks.withType(BasePythonTask).configureEach { task ->
            task.with {
                // apply default path for all python tasks
                task.conventionMapping.with {
                    pythonPath = { extension.pythonPath }
                    pythonBinary = { extension.pythonBinary }
                    validateSystemBinary = { extension.validateSystemBinary }
                    // important to copy map because each task must have independent instance
                    environment = { extension.environment ? new HashMap<>(extension.environment) : null }
                }

                // can't be implemented with convention mapping, only with properties
                configureDockerInTask(project, extension.docker, task)

                // all python tasks must be executed after check task to use correct environment (switch to virtualenv)
                if (task.taskIdentity.type != CheckPythonTask) {
                    dependsOn checkTask
                }
            }
        }

        project.tasks.withType(PythonTask).configureEach { task ->
            // by default all python tasks must be executed after dependencies init
            task.dependsOn installPipTask
        }

        // apply defaults for pip tasks
        project.tasks.withType(BasePipTask).configureEach { task ->
            task.conventionMapping.with {
                modules = { extension.modules }
                // in case of virtualenv checkPython will manually disable it
                userScope = { extension.scope != PythonExtension.Scope.GLOBAL }
                useCache = { extension.usePipCache }
                trustedHosts = { extension.trustedHosts }
                extraIndexUrls = { extension.extraIndexUrls }
                requirements = { RequirementsReader.find(project, extension.requirements) }
                strictRequirements = { extension.requirements.strict }
            }
        }
        project.tasks.withType(BasePipxTask).configureEach { task ->
            task.conventionMapping.with {
                modules = { extension.modules }
                // in case of virtualenv checkPython will manually disable it
                userScope = { extension.scope != PythonExtension.Scope.GLOBAL }
                useCache = { extension.usePipCache }
                trustedHosts = { extension.trustedHosts }
                extraIndexUrls = { extension.extraIndexUrls }
                requirements = { RequirementsReader.find(project, extension.requirements) }
                strictRequirements = { extension.requirements.strict }
            }
        }
        // apply defaults for all pip install tasks (custom pip installs may be used)
        project.tasks.withType(PipInstallTask).configureEach { task ->
            task.conventionMapping.with {
                showInstalledVersions = { extension.showInstalledVersions }
                alwaysInstallModules = { extension.alwaysInstallModules }
            }
        }
        project.tasks.withType(PipxInstallTask).configureEach { task ->
            task.conventionMapping.with {
                showInstalledVersions = { extension.showInstalledVersions }
                alwaysInstallModules = { extension.alwaysInstallModules }
            }
        }
    }

    private void configureDockerInTask(Project project, PythonExtension.Docker docker, BasePythonTask task) {
        task.docker.use.convention(project.provider { docker.use })
        task.docker.image.convention(project.provider { docker.image })
        task.docker.windows.convention(project.provider { docker.windows })
        task.docker.ports.convention(project.provider { docker.ports })
        task.docker.exclusive.convention(false)
    }

    private void configureDocker(Project project) {
        project.gradle.buildFinished {
            // close started docker containers at the end (mainly for tests, because docker instances are
            // project-specific and there would be problem in gradle tests always started in new dir)
            DockerFactory.shutdownAll()
        }
    }
}
