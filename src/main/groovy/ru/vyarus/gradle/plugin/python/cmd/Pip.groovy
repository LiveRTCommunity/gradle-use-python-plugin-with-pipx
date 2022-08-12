package ru.vyarus.gradle.plugin.python.cmd

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import ru.vyarus.gradle.plugin.python.util.CliUtils
import ru.vyarus.gradle.plugin.python.util.PythonExecutionFailed

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Pip commands execution utility. Use {@link Python} internally.
 *
 * @author Vyacheslav Rusakov
 * @since 15.11.2017
 */
@CompileStatic
@SuppressWarnings('ConfusingMethodName')
class Pip {

    private static final Pattern VERSION = Pattern.compile('pip ([\\d.]+)')

    public static final String USER = '--user'
    public static final String NO_CACHE = '--no-cache-dir'
    private static final String INSTALL_TASK = 'install'
    private static final String LIST_TASK = 'list'
    private static final List<String> USER_AWARE_COMMANDS = [INSTALL_TASK, LIST_TASK, 'freeze']
    private static final List<String> EXTRA_INDEX_AWARE_COMMANDS = [INSTALL_TASK, LIST_TASK, 'download', 'wheel']

    private final Python python
    // --user for install, list and freeze tasks
    private boolean userScope
    // --no-cache-dir for install task
    // may be changed externally
    boolean useCache
    // --extra-index-url
    List<String> extraIndexUrls = []
    // --trusted-host
    List<String> trustedHosts = []

    Pip(Project project) {
        this(project, null, null, true)
    }

    // reamains for compatibility with older releases
    Pip(Project project, String pythonPath, String binary, boolean userScope) {
        this(project, pythonPath, binary, userScope, true)
    }

    Pip(Project project, String pythonPath, String binary, boolean userScope, boolean useCache) {
        this(project, true, pythonPath, binary, userScope, useCache)
    }

    @SuppressWarnings('ParameterCount')
    Pip(Project project,
        boolean validateSystemBinary,
        String pythonPath,
        String binary,
        boolean userScope,
        boolean useCache) {
        this(new Python(project, pythonPath, binary, validateSystemBinary)
                .logLevel(LogLevel.LIFECYCLE), userScope, useCache)
    }

    // preferred way for construction because allows configured python instance re-usage
    Pip(Python python, boolean userScope, boolean useCache) {
        this.python = python
        this.userScope = userScope
        this.useCache = useCache

        // do not show passwords when external indexes used with credentials
        python.logCommandCleaner { CliUtils.hidePipCredentials(it) }
    }

    /**
     * Shortcut for {@link Python#workDir(java.lang.String)}.
     *
     * @param workDir python working directory
     * @return pip instance for chained calls
     */
    Pip workDir(String workDir) {
        python.workDir(workDir)
        return this
    }

    /**
     * Apply extra pip repositories (--extra-index-url). Applies only for commands supporting it.
     *
     * @param urls urls to pip repositories.
     * @return pip instance for chained calls
     * @see ru.vyarus.gradle.plugin.python.PythonExtension#extraIndexUrls
     */
    Pip extraIndexUrls(List<String> urls) {
        if (urls) {
            this.extraIndexUrls.addAll(urls)
        }
        return this
    }

    /**
     * Apply trusted hosts (--trusted-host). Applied only for {@code pip install}.
     *
     * @param hosts trusted hosts
     * @return pip instance for chained calls
     * @see ru.vyarus.gradle.plugin.python.PythonExtension#trustedHosts
     */
    Pip trustedHosts(List<String> hosts) {
        if (hosts) {
            this.trustedHosts.addAll(hosts)
        }
        return this
    }

    /**
     * Install module.
     *
     * @param module module name with version (e.g. 'some==12.3')
     */
    void install(String module) {
        exec("install $module")
    }

    /**
     * Uninstall module.
     *
     * @param module module name
     */
    void uninstall(String module) {
        exec("uninstall $module -y")
        if (isInstalled(module)) {
            // known problem
            throw new GradleException("Failed to uninstall module $module. Try to update pip: " +
                    '\'pip install -U pip\' or try to manually remove package ' +
                    '(probably not enough permissions)')
        }
    }

    /**
     * @param module module to check
     * @return true if module installed
     */
    boolean isInstalled(String module) {
        try {
            // has no output on error, so nothing will appear in log
            readOutput("show $module")
        } catch (PythonExecutionFailed ignored) {
            return false
        }
        return true
    }

    /**
     * Execute command on pip module. E.g. 'install some==12.3'.
     *
     * @param cmd pip command to execute
     */
    void exec(String cmd) {
        python.callModule('pip', applyFlags(cmd))
    }

    /**
     * Calls pip command and return output as string. Preferred way instead of direct python usage to correctly
     * apply --user flag for commands.
     *
     * @param cmd pip command to call
     * @return command execution output
     */
    String readOutput(String cmd) {
        python.withHiddenLog {
            python.readOutput("-m pip ${applyFlags(cmd)}")
        }
    }

    /**
     * May be used to change default configurations.
     *
     * @return python cli instance used to execute commands
     */
    Python getPython() {
        return python
    }

    /**
     * @return pip version string (minor.major.micro)
     */
    @Memoized
    String getVersion() {
        // first try to parse line to avoid duplicate python call
        Matcher matcher = VERSION.matcher(versionLine)
        if (matcher.find()) {
            // note: this will drop beta postfix (e.g. for 10.0.0b2 version will be 10.0.0)
            return matcher.group(1)
        }
        // if can't recognize version, ask directly
        return python.withHiddenLog {
            python.readOutput('-c \"import pip; print(pip.__version__)\"')
        }
    }

    /**
     * @return pip --version output
     */
    @Memoized
    String getVersionLine() {
        return python.withHiddenLog {
            python.readOutput('-m pip --version')
        }
    }

    /**
     * Execute pip methods within closure in global scope (no matter if user scope configured).
     *
     * @param closure closure with pip actions to be executed in global scope
     * @return closure result
     */
    public <T> T inGlobalScope(Closure closure) {
        boolean isUserScope = this.userScope
        this.userScope = false
        try {
            return (T) closure.call()
        } finally {
            this.userScope = isUserScope
        }
    }

    private String applyFlags(String cmd) {
        // -- user
        // explicit virtualenv check is required because flag will fail under virtualenv
        if (!cmd.contains(USER) && userScope && USER_AWARE_COMMANDS.contains(extractCommand(cmd))
                && !python.virtualenv) {
            cmd += " $USER"
        }
        // --no-cache-dir (only for install command)
        if (!useCache && !cmd.contains(NO_CACHE) && cmd.startsWith(INSTALL_TASK)) {
            cmd += " $NO_CACHE"
        }

        if (extraIndexUrls && EXTRA_INDEX_AWARE_COMMANDS.contains(extractCommand(cmd))) {
            extraIndexUrls.each { extraIndexUrl ->
                cmd += " --extra-index-url $extraIndexUrl"
            }
        }
        if (trustedHosts && cmd.startsWith(INSTALL_TASK)) {
            trustedHosts.each { trustedHost ->
                cmd += " --trusted-host $trustedHost"
            }
        }
        cmd
    }

    private String extractCommand(String cmdLine) {
        return cmdLine.split(' ')[0].toLowerCase()
    }
}
