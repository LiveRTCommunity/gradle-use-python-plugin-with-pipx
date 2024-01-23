package ru.vyarus.gradle.plugin.python.task.pipx

import groovy.transform.CompileStatic
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * List all installed modules in current scope. The same is displayed after pipInstall by default.
 * Task used just to be able to see installed modules list at any time (because pipInstall will show it only once).
 * <p>
 * When user scope used, use {@code all = true} to see modules from global scope.
 *
 * @author Vyacheslav Rusakov
 * @since 15.12.2017
 */
@CompileStatic
class PipxListTask extends BasePipxTask {

    /**
     * To see all modules from global scope, when user scope used.
     * Note that option will not take effect if global scope is configured or virtualenv is used.
     */
    @Input
    boolean all

    @TaskAction
    void run() {
        Closure action = { pipx.exec('list --json') }
        if (getAll()) {
            // show global scope
            pipx.inGlobalScope action
        } else {
            // show global or user (depends on scope configuration)
            action.call()
        }
    }
}
