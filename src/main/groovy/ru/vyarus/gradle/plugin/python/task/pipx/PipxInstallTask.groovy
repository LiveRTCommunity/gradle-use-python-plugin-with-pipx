package ru.vyarus.gradle.plugin.python.task.pipx

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import org.gradle.api.tasks.*
import ru.vyarus.gradle.plugin.python.PythonExtension
import ru.vyarus.gradle.plugin.python.util.RequirementsReader

import java.util.concurrent.ConcurrentHashMap

/**
 * Install required modules (with correct versions) into python using pip.
 * Default task is registered as pipInstall to install all modules declared in
 * {@link ru.vyarus.gradle.plugin.python.PythonExtension#modules} and (optional) requirements file.
 * {@link ru.vyarus.gradle.plugin.python.task.CheckPythonTask} always run before pip install task to validate
 * (and create, if required) environment.
 * <p>
 * All {@link ru.vyarus.gradle.plugin.python.task.PythonTask}s are depend on pipInstall by default.
 *
 * @author Vyacheslav Rusakov
 * @since 11.11.2017
 */
@CompileStatic
class PipxInstallTask extends BasePipxTask {

    // sync to avoid parallel installation into THE SAME environment
    private static final Map<String, Object> SYNC = new ConcurrentHashMap<>()

    /**
     * True to show list of all installed python modules (not only modules installed by plugin!).
     * By default use {@link ru.vyarus.gradle.plugin.python.PythonExtension#showInstalledVersions} value.
     */
    @Console
    boolean showInstalledVersions

    /**
     * True to always call 'pip install' for configured modules, otherwise pip install called only
     * if module is not installed or different version installed. For requirements file this option might be useful
     * if requirements file links other files, which changes plugin would not be able to track.
     * By default use {@link ru.vyarus.gradle.plugin.python.PythonExtension#alwaysInstallModules} value.
     */
    @Input
    boolean alwaysInstallModules

    /**
     * Extra {@code pip install} arguments not covered directly by api.
     * @see <a href="https://pip.pypa.io/en/stable/reference/pip_install/#options">options</a>
     */
    @Input
    @Optional
    List<String> options = []

    PipxInstallTask() {
        // useful only when dependencies declared (directly or with requirements file)
        onlyIf { modulesInstallationRequired }
        // task will always run for the first time (even if deps are ok), but all consequent runs will be up-to-date
        // note: for requirements file up-to-date check will correctly count file modification date.
        // "alwaysInstallModules" might be used in case when requirements file links other files and so dependent
        // file change would not trigger task execution (because referenced file not changed)
        outputs.upToDateWhen { modulesToInstall.empty && !isAlwaysInstallModules() }
    }

    @TaskAction
    @SuppressWarnings('UnnecessaryGetter')
    void run() {
        pipx.python.extraArgs(getOptions())
        File file = getRequirements()
        boolean directReqsInstallRequired = !getStrictRequirements() && file && file.exists()
        // sync is required for multi-module projects with parallel execution enabled to avoid concurrent
        // installation into THE SAME environment
        synchronized (getSync(pipx.python.binaryDir)) {
            if (directReqsInstallRequired) {
                // process requirements with pip
                pipx.exec("install -r ${RequirementsReader.relativePath(project, file)}")
            }
            // in non strict mode requirements would be parsed manually and installed as separate modules
            // see BasePipTask.getAllModules()

            modulesToInstall.each { pipx.install(it) }
        }
        // apply options only for install calls! otherwise, following pip calls will fail
        pipx.python.clearExtraArgs()

        // could be at first run (upToDateWhen requires at least one task execution)
        if (modulesToInstall.empty && !directReqsInstallRequired) {
            logger.lifecycle('All required modules are already installed with correct versions')
        } else {
            // chown created files so user could remove them on host (unroot)
            dockerChown(project.extensions.getByType(PythonExtension).envPath)
        }

        if (isShowInstalledVersions()) {
            // show all installed modules versions (to help problems resolution)
            // note: if some modules are already installed in global scope and user scope is used,
            // then global modules will not be shown
            pipx.exec('list --format=columns')
        }
    }

    /**
     * Add extra {@code pip install} options, applied to command.
     *
     * @param args arguments
     */
    @SuppressWarnings('ConfusingMethodName')
    void options(String... args) {
        if (args) {
            getOptions().addAll(args)
        }
    }

    @Internal
    protected List<String> getModulesToInstall() {
        buildModulesToInstall()
    }

    // synchronization objects per target python path to avoid parallel installation into the same environment
    @SuppressWarnings('SynchronizedMethod')
    private synchronized static Object getSync(String path) {
        if (!SYNC.containsKey(path)) {
            SYNC.put(path, new Object())
        }
        return SYNC.get(path)
    }

    @Memoized
    private List<String> buildModulesToInstall() {
        List<String> res = []
        if (!modulesList.empty) {
            // use list of installed modules to check if 'pip install' is required for module
            // have to always use global list (even if user scope used) to avoid redundant installation attempts
            List<String> installed = (isAlwaysInstallModules() ? ''
                    : pipx.inGlobalScope { pipx.readOutput('freeze') } as String).toLowerCase().readLines()
            // install modules
            modulesList.each { PipxModule mod ->
                boolean found = false
                mod.toFreezeStrings().each {
                    if (installed.contains(it.toLowerCase())) {
                        found = true
                    }
                }
                // don't install if already installed (assume dependencies are also installed)
                if (!found) {
                    logger.info('Required pip module not installed: {}', mod)
                    res.add(mod.toPipxInstallString())
                }

            }
        }
        return res
    }
}
