package ru.vyarus.gradle.plugin.python.cmd

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import ru.vyarus.gradle.plugin.python.cmd.docker.DockerConfig
import ru.vyarus.gradle.plugin.python.util.CliUtils
import ru.vyarus.gradle.plugin.python.util.PythonExecutionFailed

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Pipx commands execution utility. Use {@link Python} internally.
 *
 * @author Vyacheslav Rusakov
 * @since 15.11.2017
 */
@CompileStatic
@SuppressWarnings('ConfusingMethodName')
class Pipx {

    private static final Pattern VERSION = Pattern.compile('pipx ([\\d.]+)')

    public static final String USER = '--user'
    public static final String NO_CACHE = '--no-cache-dir'
    private static final String INSTALL_TASK = 'install'
    private static final String LIST_TASK = 'list'
    private static final String RUN_TASK = 'run'
    private static final String INJECT_TASK = 'inject'
    private static final List<String> USER_AWARE_COMMANDS = [INSTALL_TASK, LIST_TASK, 'freeze']
    private static final List<String> EXTRA_INDEX_AWARE_COMMANDS = [INSTALL_TASK, LIST_TASK, 'download', 'wheel']

    private final Python python
    // --user for install, list and freeze tasks
    private boolean userScope = true
    // --no-cache-dir for install task
    // may be changed externally
    boolean useCache = true
    // --extra-index-url
    List<String> extraIndexUrls = []
    // --trusted-host
    List<String> trustedHosts = []

    Pipx(Project project) {
        this(project, null, null)
    }

    Pipx(Project project, String pythonPath, String binary) {
        this(new Python(project, pythonPath, binary).logLevel(LogLevel.LIFECYCLE))
    }

    // preferred way for construction because allows configured python instance re-usage
    Pipx(Python python) {
        this.python = python

        // do not show passwords when external indexes used with credentials
        python.logCommandCleaner { CliUtils.hidePipCredentials(it) }
    }

    /**
     * Execute install, list and freeze tasks with --user flag (in user scope).
     * Enabled by default!
     *
     * @param inUserScope false to switch to global scope
     * @return pipx instance for chained calls
     */
    Pipx userScope(boolean inUserScope) {
        this.userScope = inUserScope
        return this
    }

    /**
     * By default pipx install will use case, Set to false in order to disable pipx installation cache (--no-cache-dir
     * would be applied).
     *
     * @param cache false to disable pipx install cache
     * @return pipx instance for chained calls
     */
    Pipx useCache(boolean cache) {
        this.useCache = cache
        return this
    }

    /**
     * System binary search is performed only for global python (when pythonPath is not specified). Enabled by default.
     *
     * @param validate true to search python binary in system path and fail if not found
     * @return cli instance for chained calls
     */
    Pipx validateSystemBinary(boolean validate) {
        this.python.validateSystemBinary(validate)
        return this
    }

    /**
     * Enable docker support: all python commands would be executed under docker container.
     *
     * @param docker docker configuration (may be null)
     * @return cli instance for chained calls
     */
    Pipx withDocker(DockerConfig docker) {
        this.python.withDocker(docker)
        return this
    }

    /**
     * Shortcut for {@link Python#workDir(java.lang.String)}.
     *
     * @param workDir python working directory
     * @return pipx instance for chained calls
     */
    Pipx workDir(String workDir) {
        python.workDir(workDir)
        return this
    }

    /**
     * Shortcut for {@link Python#environment(java.util.Map)}.
     *
     * @param env environment map
     * @return pipx instance for chained calls
     */
    Pipx environment(Map<String, Object> env) {
        python.environment(env)
        return this
    }

    /**
     * Apply extra pipx repositories (--extra-index-url). Applies only for commands supporting it.
     *
     * @param urls urls to pipx repositories.
     * @return pipx instance for chained calls
     * @see ru.vyarus.gradle.plugin.python.PythonExtension#extraIndexUrls
     */
    Pipx extraIndexUrls(List<String> urls) {
        if (urls) {
            this.extraIndexUrls.addAll(urls)
        }
        return this
    }

    /**
     * Apply trusted hosts (--trusted-host). Applied only for {@code pipx install}.
     *
     * @param hosts trusted hosts
     * @return pipx instance for chained calls
     * @see ru.vyarus.gradle.plugin.python.PythonExtension#trustedHosts
     */
    Pipx trustedHosts(List<String> hosts) {
        if (hosts) {
            this.trustedHosts.addAll(hosts)
        }
        return this
    }

    /**
     * Perform pre-initialization and, if required, validate global python binary correctness. Calling this method is
     * NOT REQUIRED: initialization will be performed automatically before first execution. But it might be called
     * in order to throw possible initialization error before some other logic (related to exception handling).
     *
     * @return pipx instance for chained calls
     */
    Pipx validate() {
        python.validate()
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
     * @param module module name
     */
    void uninstall(String module) {
        exec("uninstall $module -y")
        if (isInstalled(module)) {
            // known problem
            throw new GradleException("Failed to uninstall module $module. Try to update pipx: " +
                    '\'pipx install -U pipx\' or try to manually remove package ' +
                    '(probably not enough permissions)')
        }
    }
    /**
     * @param module module name, params params names
     */
    void run(String module, String... params) {
        exec("run $module ${params.join(' ')}")
    }
    /**
     * @param package package name, dependencies dependencies names, params params names
     */
    void inject(String pack, List<String> dependency, List<String> params) {
        isInstalled(pack) || exec("install $pack")
        exec("inject $pack ${dependency.join(' ')} ${params.join(' ')}")
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
     * Execute command on pipx module. E.g. 'install some==12.3'.
     *
     * @param cmd pipx command to execute
     */
    void exec(String cmd) {
        python.callModule('pipx', applyFlags(cmd))
    }

    /**
     * Calls pipx command and return output as string. Preferred way instead of direct python usage to correctly
     * apply --user flag for commands.
     *
     * @param cmd pipx command to call
     * @return command execution output
     */
    String readOutput(String cmd) {
        python.withHiddenLog {
            python.readOutput("-m pipx ${applyFlags(cmd)}")
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
     * @return pipx version string (minor.major.micro)
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
            python.readOutput('-c \"import pipx; print(pipx.__version__)\"')
        }
    }

    /**
     * @return pipx --version output
     */
    @Memoized
    String getVersionLine() {
        return python.withHiddenLog {
            python.readOutput('-m pipx --version')
        }
    }

    /**
     * Execute pipx methods within closure in global scope (no matter if user scope configured).
     *
     * @param closure closure with pipx actions to be executed in global scope
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
