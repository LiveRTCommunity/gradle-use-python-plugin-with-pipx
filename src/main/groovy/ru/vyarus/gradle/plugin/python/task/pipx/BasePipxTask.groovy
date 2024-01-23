package ru.vyarus.gradle.plugin.python.task.pipx

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import ru.vyarus.gradle.plugin.python.cmd.Pip
import ru.vyarus.gradle.plugin.python.cmd.Pipx
import ru.vyarus.gradle.plugin.python.task.BasePythonTask
import ru.vyarus.gradle.plugin.python.util.RequirementsReader

/**
 * Base task for pipx tasks.
 *
 * @author Vyacheslav Rusakov
 * @since 01.12.2017
 */
@CompileStatic
class BasePipxTask extends BasePythonTask {

    /**
     * List of modules to install. Module declaration format: 'name:version'.
     * For default pipInstall task modules are configured in
     * {@link ru.vyarus.gradle.plugin.python.PythonExtension#modules}.
     * Also, modules could be declared in {@link #requirements} file.
     */
    @Input
    @Optional
    List<String> modules = []

    /**
     * Work with packages in user scope (--user pipx option). When false - work with global scope.
     * Note that on linux it is better to work with user scope to overcome permission problems.
     * <p>
     * Enabled by default (see {@link ru.vyarus.gradle.plugin.python.PythonExtension#scope})
     */
    @Input
    boolean userScope

    /**
     * Affects only {@code pipx install} by applying {@code --no-cache-dir}. Disables cache during package resolution.
     * May be useful in problematic cases (when cache leads to incorrect version installation or to force vcs
     * modules re-building each time (pipx 20 cache vcs resolved modules by default)).
     * <p>
     * Enabled by default (see {@link ru.vyarus.gradle.plugin.python.PythonExtension#usePipCache})
     */
    @Input
    boolean useCache = true

    /**
     * Affects only {@code pipx install} by applying {@code --trusted-host} (other pipx commands does not support
     * this option).
     * <p>
     * No extra index urls are given by default (see
     * {@link ru.vyarus.gradle.plugin.python.PythonExtension#trustedHosts})
     */
    @Input
    @Optional
    List<String> trustedHosts = []

    /**
     * Affects only {@code pipx install}, {@code pipx download}, {@code pipx list} and {@code pipx wheel} by applying
     * {@code --extra-index-url}.
     * <p>
     * No extra index urls are given by default (see
     * {@link ru.vyarus.gradle.plugin.python.PythonExtension#extraIndexUrls})
     */
    @Input
    @Optional
    List<String> extraIndexUrls = []

    /**
     * Requirements file to use (for default value see
     * {@link ru.vyarus.gradle.plugin.python.PythonExtension.Requirements#file}).
     */
    @InputFile
    @Optional
    File requirements

    /**
     * Strict mode: requirements file read by plugin and all declarations used the same way as if they were
     * manually declared. This way, modules declared in requirements file using pipx syntax, but still only exact
     * versions allowed. Using this mode allows other tools to read and update standard python declarations.
     * <p>
     * In non-strict mode, requirements file processing is delegated to pipx (without any limits like prohibited
     * version ranges).
     */
    @Input
    boolean strictRequirements

    /**
     * Shortcut for {@link #pipx(java.lang.Iterable)}.
     *
     * @param modules modules to install
     */
    void pipx(String... modules) {
        pipx(Arrays.asList(modules))
    }

    /**
     * Add modules to install. Module format: 'name:version'. Duplicate declarations are allowed: in this case the
     * latest declaration will be used.
     *
     * @param modules modules to install
     */
    void pipx(Iterable<String> modules) {
        getModules().addAll(modules)
    }

    /**
     * Calling this method would trigger requirements file parsing (if it wasn't parsed already).
     *
     * @return modules from requirements file (if strict mode enabled) together with directly configured modules
     */
    @Internal
    List<String> getAllModules() {
        List<String> reqs = requirementsModules
        if (reqs) {
            List<String> res = []
            res.addAll(reqs)
            res.addAll(getModules())
            return res
        }
        return getModules()
    }

    /**
     * Resolve modules list for installation by removing duplicate definitions (the latest definition wins).
     * In strict mode, requirements file modules would also be included (and duplicates removed).
     *
     * @return pipx modules to install
     */
    @Internal
    protected List<PipxModule> getModulesList() {
        buildModulesList()
    }

    /**
     * Modules could be configured directly in gradle or specified in requirements file (in strict mode). In
     * non-strict mode (when requirements file processing is delegated to pipx), requirements file existence assumed
     * to mean existing requirements.
     *
     * @return true if there are dependencies to install
     */
    @Internal
    @SuppressWarnings('UnnecessaryGetter')
    protected boolean isModulesInstallationRequired() {
        return !getAllModules().empty || (!getStrictRequirements() && getRequirements() && getRequirements().exists())
    }

    /**
     * @return configured pipx utility instance
     */
    @Internal
    protected Pipx getPipx() {
        buildPipx()
    }

    /**
     * Calling this method would trigger requirements file parsing (if it was not disabled) and will return
     * all modules.
     * <p>
     * NOTE: requirements would be read at least 2 times (checkPython and pipInstall)! But it's required to be able
     * to configure "general" pipx task separately (independently of global extension configuration).
     *
     * @return pipx modules from requirements file (in plugin's format) or empty list
     */
    @Memoized
    @SuppressWarnings('UnnecessaryGetter')
    private List<String> getRequirementsModules() {
        if (getStrictRequirements()) {
            File file = getRequirements()
            List<String> res = RequirementsReader.read(file)
            if (!res.isEmpty()) {
                logger.warn('{} modules to install read from requirements file: {} (strict mode)',
                        res.size(), RequirementsReader.relativePath(project, file))
            }
            return res
        }
        return Collections.emptyList()
    }

    @Memoized
    private List<PipxModule> buildModulesList() {
        Map<String, PipxModule> mods = [:] // linked map
        // sequential parsing in order to override duplicate definitions
        // (latter defined module overrides previous definition) and preserve definition order
        allModules.each {
            PipxModule mod = PipxModule.parse(it)
            mods[mod.name] = mod
        }
        return new ArrayList(mods.values())
    }

    @Memoized
    private Pipx buildPipx() {
        return new Pipx(python)
                .userScope(getUserScope())
                .useCache(getUseCache())
                .trustedHosts(getTrustedHosts())
                .extraIndexUrls(getExtraIndexUrls())
    }
}
