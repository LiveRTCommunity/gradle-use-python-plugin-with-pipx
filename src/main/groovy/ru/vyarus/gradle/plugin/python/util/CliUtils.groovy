package ru.vyarus.gradle.plugin.python.util

import groovy.transform.CompileStatic
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.process.internal.ExecException

/**
 * Cli helper utilities.
 *
 * @author Vyacheslav Rusakov
 * @since 20.11.2017
 */
@CompileStatic
final class CliUtils {

    private static final String[] EMPTY = []
    private static final String SPACE = ' '
    private static final String VERSION_SPLIT = '\\.'

    private CliUtils() {
    }

    /**
     * Apply prefix for all lines in incoming string.
     *
     * @param output string to apply prefix
     * @param prefix lines prefix
     * @return string with applied prefixes
     */
    static String prefixOutput(String output, String prefix) {
        prefix ? output.readLines().collect { "$prefix $it" }.join('\n') : output
    }

    /**
     * Merge arguments.
     *
     * @param args1 array, collection or simple string (null allowed)
     * @param args2 array, collection or simple string (null allowed)
     * @return merged collection
     */
    static String[] mergeArgs(Object args1, Object args2) {
        String[] args = []
        args += parseArgs(args1)
        args += parseArgs(args2)
        return args
    }

    /**
     * Parse arguments from multiple formats.
     *
     * @param args array, collection or string
     * @return parsed arguments
     */
    @SuppressWarnings('Instanceof')
    static String[] parseArgs(Object args) {
        String[] res = EMPTY
        if (args) {
            if (args instanceof CharSequence) {
                res = parseCommandLine(args.toString())
            } else {
                res = args as String[]
            }
        }
        return res
    }

    /**
     * Parse arguments from simple string. Support quotes (but not nested).
     *
     * @param command arguments string
     * @return parsed arguments
     */
    @SuppressWarnings('CouldBeElvis')
    static String[] parseCommandLine(String command) {
        String cmd = command.trim()
        if (cmd) {
            cmd = cmd.replaceAll('\\s{2,}', SPACE)
            List<String> res = []
            String scope
            StringBuilder tmp = new StringBuilder()
            cmd.each {
                if (it == SPACE && !scope) {
                    res << tmp.toString()
                    tmp = new StringBuilder()
                    return
                }
                if (it in ['"', '\'']) {
                    // only look quotes after separator/line start
                    if (!scope) {
                        //start quote
                        scope = it
                    } else if (scope == it) {
                        // end quote
                        scope = null
                    }
                }
                tmp.append(it)
            }
            // last arg
            res << tmp.toString()
            return res as String[]
        }
        return EMPTY
    }

    /**
     * @param version checked version in format major.minor.micro
     * @param required version constraint (could be null)
     * @return true if version matches requirement (>=)
     */
    static boolean isVersionMatch(String version, String required) {
        boolean valid = true
        if (required) {
            String[] req = required.split(VERSION_SPLIT)
            String[] ver = version.split(VERSION_SPLIT)
            if (req.length > 3) {
                throw new IllegalArgumentException(
                        "Invalid version format: $required. Accepted format: major.minor.micro")
            }
            valid = isPositionMatch(ver, req, 0)
        }
        return valid
    }

    /**
     * Wraps command ('-c print('smth')') into exec() for linux: '-c "print('smth')"' will not be
     * called on linux at all (when used from java).
     *
     * @param command command expression to wrap
     * @return wrapped expression or original command if its already exec()
     */
    static String wrapCommand(String command) {
        boolean isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
        if (isWindows || command.startsWith('exec(')) {
            return command
        }
        String cmd = command.replaceAll(/^"|"$/, '')
        return "exec(\"$cmd\")"
    }

    /**
     * Prepare arguments to call python with cmd.
     *
     * @param executable python executable
     * @param projectHome project home directory
     * @param args python arguments
     * @return arguments for cmd
     */
    static String[] wincmdArgs(String executable, File projectHome, String[] args, boolean workDirUsed) {
        File file = new File(projectHome, executable)
        // manual check to unify win/linux behaviour
        if (!file.exists()) {
            throw new ExecException("Cannot run program \"$executable\": error=2, No such file or directory")
        }
        // when work dir not used we can use relative path, but with work dir only absolute path
        String exec = workDirUsed ? file.canonicalPath : executable
        return mergeArgs(['/c', exec.contains(SPACE) ? "\"\"$exec\"\"" : exec], args)
    }

    private static boolean isPositionMatch(String[] ver, String[] req, int pos) {
        boolean valid = (ver[pos] as Integer) >= (req[pos] as Integer)
        if (valid && ver[pos] == req[pos] && req.length > pos + 1) {
            return isPositionMatch(ver, req, pos + 1)
        }
        return valid
    }
}
