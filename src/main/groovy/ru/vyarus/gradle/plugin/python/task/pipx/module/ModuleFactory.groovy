package ru.vyarus.gradle.plugin.python.task.pipx.module

import groovy.transform.CompileStatic
import ru.vyarus.gradle.plugin.python.task.pipx.PipxModule

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Module descriptor parser. Supports versioned vcs modules, special exact syntax (name:version) and
 * feature-enabled exact syntax (name[feature1,feature2]:version).
 *
 * @author Vyacheslav Rusakov
 * @since 18.05.2018
 */
@CompileStatic
@SuppressWarnings('DuplicateNumberLiteral')
class ModuleFactory {
    private static final Pattern VCS_FORMAT = Pattern.compile('@[^#]+#egg=([^&]+)')
    private static final String VERSION_SEPARATOR = ':'
    private static final String VCS_VERSION_SEPARATOR = '-'

    private static final Pattern FEATURE_FORMAT = Pattern.compile('(.+)\\[(.+)]\\s*:\\s*(.+)')
    private static final String QUALIFIER_START = '['
    private static final String QUALIFIER_END = ']'

    private static final String START_GIT = 'git+'
    private static final String START_SVN = 'svn+'
    private static final String START_HG = 'hg+'
    private static final String START_BZR = 'bzr+'
    private static final String START_GIT_SHORT = 'git://'
    private static final String START_SVN_SHORT = 'svn://'

    private static final String START_GIT_HTTPS = 'https://'
    private static final String START_GIT_HTTP = 'http://'
    private static final String START_GIT_SSH = 'ssh://'
    private static final String START_GIT_FILE = 'file://'

    private static final List<String> VCS_STARTS = [
            START_GIT, START_SVN, START_HG, START_BZR,
            START_GIT_SHORT, START_SVN_SHORT,
            START_GIT_HTTPS, START_GIT_HTTP, START_GIT_SSH, START_GIT_FILE
    ]

    private static final String PARAM_DIRECTORY_OR_VERSIONING_SYSTEM = "--editable"


    private static final int DECL_PARTS = 2

    /**
     * @param descriptor module descriptor string
     * @return parsed module instance (normal or vcs)
     */
    static PipxModule create(String descriptor) {
        PipxModule res
        if (
                (descriptor.contains('#egg=') || descriptor.contains('/'))
                        && descriptor.contains(PARAM_DIRECTORY_OR_VERSIONING_SYSTEM)
        ) {
            res = parseVcsModule(descriptor) // VCS = Versioning Control System
        } else if (descriptor.contains(QUALIFIER_START) && descriptor.contains(QUALIFIER_END)) {
            res = parseFeatureModule(descriptor)
        } else {
            res = parseModule(descriptor)
        }
        return res
    }

    /**
     * Search module by name in provided declarations. Supports normal and vcs syntax.
     *
     * @param name module name
     * @param modules module declarations to search in
     * @return found module name or null if not found
     */
    static String findModuleDeclaration(String name, List<String> modules) {
        String nm = name.toLowerCase() + VERSION_SEPARATOR
        String qualifNm = name.toLowerCase() + QUALIFIER_START
        String vcsNm = "#egg=${name.toLowerCase()}-"
        return modules.find {
            String mod = it.toLowerCase()
            if (mod.contains(QUALIFIER_START)) {
                // qualified definition
                return mod.startsWith(qualifNm)
            }
            // vcs and simple definitions
            return mod.startsWith(nm) || mod.contains(vcsNm)
        }
    }

    /**
     * Parse vsc module declaration. Only declaration with exact vcs and package versions is acceptable.
     *
     * @param desc descriptor
     * @return parsed module instance
     * @see <a href="https://pip.pypa.io/en/stable/reference/pip_install/#vcs-support"  >  pip vsc support</a>
     */
    private static PipxModule parseVcsModule(String desc) {
        if (!desc.contains('@') || !desc.contains('#egg=')) {
            throw new IllegalArgumentException("${wrongVcs(desc)} '@version' part is required and " +
                    "'#egg=name-version' part is required")
        }
        Matcher matcherVersion = VCS_FORMAT.matcher(desc)
        Boolean containsVcsStarts = VCS_STARTS.contains(desc)
        if (!matcherVersion.find() || !containsVcsStarts) {
            throw new IllegalArgumentException("${wrongVcs(desc)} Module name not found")
        }
        String name = matcherVersion.group(1).trim()
        // '-' could not appear in module name
        if (!name.contains(VCS_VERSION_SEPARATOR)) {
            throw new IllegalArgumentException(
                    "${wrongVcs(desc)} Module version is required in module (#egg=name-version): '$name'. " +
                            'This is important to be able to check up-to-date state without python run')
        }
        String[] split = name.split(VCS_VERSION_SEPARATOR)
        String version = split.last().trim()
        // remove version part because pip fails to install with it
        String pkgName = name[0..name.lastIndexOf(VCS_VERSION_SEPARATOR) - 1].trim()
        String shortDesc = desc.replace(name, pkgName)
        return new VcsPipModule(shortDesc, pkgName, version)
    }

    /**
     * Feature enabled module declaration: name[qualifier]:version.
     *
     * @param desc module descriptor
     * @return simple module if qualifier is empty or feature module
     */
    private static PipxModule parseFeatureModule(String desc) {
        Matcher matcher = FEATURE_FORMAT.matcher(desc)
        if (!matcher.matches()) {
            throw new IllegalArgumentException('Incorrect pip module declaration (expected ' +
                    "'module[qualifier,qualifier2]:version'): '$desc'")
        }
        String name = matcher.group(1).trim()
        String qualifier = matcher.group(2).trim()
        String version = matcher.group(3).trim()
        return qualifier ?
                new FeaturePipModule(name, qualifier, version)
                // no qualifier ([]) - silently create simple module
                : new PipxModule(name, version)
    }

    /**
     * Parse module declaration in format 'module:version'.
     *
     * @param declaration module declaration to parse
     * @return parsed module pojo
     * @throws IllegalArgumentException if module format does not match
     */
    private static PipxModule parseModule(String desc) {
        String[] parts = desc.split(VERSION_SEPARATOR)
        if (parts.length != DECL_PARTS) {
            throw new IllegalArgumentException(
                    "Incorrect pip module declaration (must be 'module:version'): $desc")
        }
        return new PipxModule(parts[0].trim() ?: null, parts[1].trim() ?: null)
    }

    private static String wrongVcs(String desc) {
        return "Incorrect pip vsc module declaration: '$desc' (required format is " +
                "'vcs+protocol://repo_url/@vcsVersion#egg=name-pkgVersion')."
    }
}
