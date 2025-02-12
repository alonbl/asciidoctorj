package org.asciidoctor.jruby.cli;

import com.beust.jcommander.JCommander;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.jruby.DirectoryWalker;
import org.asciidoctor.jruby.GlobDirectoryWalker;
import org.asciidoctor.jruby.internal.IOUtils;
import org.asciidoctor.jruby.internal.JRubyAsciidoctor;
import org.asciidoctor.jruby.internal.JRubyRuntimeContext;
import org.asciidoctor.jruby.internal.RubyUtils;
import org.jruby.Main;
import org.jruby.runtime.builtin.IRubyObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.stream.Collectors;

import static org.asciidoctor.jruby.cli.AsciidoctorCliOptions.TIMINGS_OPTION_NAME;

public class AsciidoctorInvoker {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    public void invoke(String... parameters) {

        AsciidoctorCliOptions asciidoctorCliOptions = new AsciidoctorCliOptions();
        JCommander jCommander = new JCommander(asciidoctorCliOptions);
        jCommander.parse(parameters);

        if (asciidoctorCliOptions.isHelp() || parameters.length == 0) {
            jCommander.setProgramName("asciidoctor");
            jCommander.usage();
        } else {

            JRubyAsciidoctor asciidoctor = buildAsciidoctorJInstance(asciidoctorCliOptions);

            if (asciidoctorCliOptions.isVersion()) {
                System.out.println("AsciidoctorJ " + getAsciidoctorJVersion() + " (Asciidoctor " + asciidoctor.asciidoctorVersion() + ") [https://asciidoctor.org]");
                Object rubyVersionString = JRubyRuntimeContext.get(asciidoctor).evalScriptlet("\"#{JRUBY_VERSION} (#{RUBY_VERSION})\"");
                System.out.println("Runtime Environment: jruby " + rubyVersionString);
                return;
            }

            List<File> inputFiles = getInputFiles(asciidoctorCliOptions);

            if (inputFiles.isEmpty()) {
                System.err.println("asciidoctor: FAILED: input file(s) '"
                        + asciidoctorCliOptions.getParameters()
                        + "' missing or cannot be read");
                throw new IllegalArgumentException(
                        "asciidoctor: FAILED: input file(s) '"
                                + asciidoctorCliOptions.getParameters()
                                + "' missing or cannot be read");
            }

            Options options = asciidoctorCliOptions.parse();

            if (asciidoctorCliOptions.isRequire()) {
                for (String require : asciidoctorCliOptions.getRequire()) {
                    RubyUtils.requireLibrary(asciidoctor.getRubyRuntime(), require);
                }
            }

            setTimingsMode(asciidoctor, asciidoctorCliOptions, options);

            setVerboseLevel(asciidoctor, asciidoctorCliOptions);

            convertInput(asciidoctor, options, inputFiles);

            if (asciidoctorCliOptions.isTimings()) {
                Map<String, Object> optionsMap = options.map();
                IRubyObject timings = (IRubyObject) optionsMap.get(TIMINGS_OPTION_NAME);
                timings.callMethod(JRubyRuntimeContext.get(asciidoctor).getCurrentContext(), "print_report");
            }
        }
    }

    private String getAsciidoctorJVersion() {
        InputStream in = getClass().getResourceAsStream("/META-INF/asciidoctorj-version.properties");
        if (in == null) {
            return "N/A";
        }
        Properties versionProps = new Properties();
        try {
            versionProps.load(in);
            return versionProps.getProperty("version.asciidoctorj");
        } catch (IOException e) {
            return "N/A";
        }
    }

    private void setTimingsMode(Asciidoctor asciidoctor, AsciidoctorCliOptions asciidoctorCliOptions, Options options) {
        if (asciidoctorCliOptions.isTimings()) {
            options.setOption(TIMINGS_OPTION_NAME,
                    JRubyRuntimeContext.get(asciidoctor).evalScriptlet("Asciidoctor::Timings.new"));
        }
    }

    private void setVerboseLevel(JRubyAsciidoctor asciidoctor, AsciidoctorCliOptions asciidoctorCliOptions) {
        if (asciidoctorCliOptions.isVerbose()) {
            RubyUtils.setGlobalVariable(asciidoctor.getRubyRuntime(), "VERBOSE", "true");
        } else {
            if (asciidoctorCliOptions.isQuiet()) {
                RubyUtils.setGlobalVariable(asciidoctor.getRubyRuntime(), "VERBOSE", "nil");
            }
        }
    }

    private JRubyAsciidoctor buildAsciidoctorJInstance(AsciidoctorCliOptions asciidoctorCliOptions) {
        ClassLoader oldTccl = Thread.currentThread().getContextClassLoader();
        try {
            if (asciidoctorCliOptions.isClassPaths()) {
                URLClassLoader tccl = createUrlClassLoader(asciidoctorCliOptions.getClassPaths());
                Thread.currentThread().setContextClassLoader(tccl);
            }
            JRubyAsciidoctor asciidoctor;
            if (asciidoctorCliOptions.isLoadPaths()) {
                asciidoctor = JRubyAsciidoctor.create(asciidoctorCliOptions.getLoadPaths());
            } else {
                asciidoctor = JRubyAsciidoctor.create();
            }
            return asciidoctor;
        } finally {
            Thread.currentThread().setContextClassLoader(oldTccl);
        }
    }

    private URLClassLoader createUrlClassLoader(List<String> classPaths) {
        List<URL> cpUrls = new ArrayList<>();
        for (String cp : classPaths) {
            try {
                DirectoryWalker globDirectoryWalker = new GlobDirectoryWalker(cp);
                for (File f : globDirectoryWalker.scan()) {
                    cpUrls.add(f.toURI().toURL());
                }
            } catch (Exception e) {
                System.err.println(String.format("asciidoctor: WARNING: Could not resolve classpath '%s': %s", cp, e.getMessage()));
            }
        }
        return new URLClassLoader(cpUrls.toArray(new URL[cpUrls.size()]));
    }

    private void convertInput(Asciidoctor asciidoctor, Options options, List<File> inputFiles) {

        if (inputFiles.size() == 1 && "-".equals(inputFiles.get(0).getName())) {
            asciidoctor.convert(readInputFromStdIn(), options);
        }

        findInvalidInputFile(inputFiles)
                .ifPresent(inputFile -> {
                    System.err.println("asciidoctor: FAILED: input file(s) '"
                            + inputFile.getAbsolutePath()
                            + "' missing or cannot be read");
                    throw new IllegalArgumentException(
                            "asciidoctor: FAILED: input file(s) '"
                                    + inputFile.getAbsolutePath()
                                    + "' missing or cannot be read");
                });

        inputFiles.forEach(inputFile -> asciidoctor.convertFile(inputFile, options));
    }

    private Optional<File> findInvalidInputFile(List<File> inputFiles) {
        return inputFiles.stream()
                .filter(inputFile -> !inputFile.canRead())
                .findFirst();
    }

    private String readInputFromStdIn() {
        return IOUtils.readFull(System.in);
    }

    private List<File> getInputFiles(AsciidoctorCliOptions asciidoctorCliOptions) {

        List<String> parameters = asciidoctorCliOptions.getParameters();

        if (parameters.stream().anyMatch(String::isEmpty)) {
            System.err.println("asciidoctor: FAILED: empty input file name");
            throw new IllegalArgumentException("asciidoctor: FAILED: empty input file name");
        }

        return parameters.stream()
                .map(globExpression -> new GlobDirectoryWalker(globExpression))
                .flatMap(walker -> walker.scan().stream())
                .collect(Collectors.toList());
    }

    public static void main(String args[]) {

        // Process .jrubyrc file
        Main.processDotfile();

        new AsciidoctorInvoker().invoke(args);
    }

}
