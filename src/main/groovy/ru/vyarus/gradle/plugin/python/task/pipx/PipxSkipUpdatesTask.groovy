package ru.vyarus.gradle.plugin.python.task.pipx

import groovy.transform.CompileStatic
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Print available new versions for the registered pip modules.
 *
 * @author Théo Minarini
 * @since 32.01.2024
 */
@CompileStatic
class PipxSkipUpdatesTask extends BasePipxTask {
    //PIPX_SKIP_CHECK_UPDATES=true
    @Input
    boolean skipUpdates = true

    @TaskAction
    void run() {
        String env = System.getenv("PIPX_SKIP_CHECK_UPDATES")
        logger.lifecycle('Skip updates')
        if (env == null) {
            logger.lifecycle('PIPX_SKIP_CHECK_UPDATES is not set')
        } else {
            logger.lifecycle('PIPX_SKIP_CHECK_UPDATES is set: ', env)
            def pb = new ProcessBuilder()
            pb.environment().put('PIPX_SKIP_CHECK_UPDATES', skipUpdates.booleanValue().toString())
            Process p = pb.start()
            p.waitFor()
            logger.lifecycle('PIPX_SKIP_CHECK_UPDATES is now set: ', env)
        }

    }
}