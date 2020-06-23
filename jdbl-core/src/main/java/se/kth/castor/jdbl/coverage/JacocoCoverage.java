package se.kth.castor.jdbl.coverage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.log4j.LogManager;
import org.apache.maven.project.MavenProject;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.MultiReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.xml.XMLFormatter;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import se.kth.castor.jdbl.debloat.DebloatTypeEnum;
import se.kth.castor.jdbl.util.JarUtils;
import se.kth.castor.jdbl.util.MavenUtils;

/**
 * This class runs JaCoCo via CLI.
 *
 * @see <a https://www.jacoco.org/jacoco/trunk/doc/cli.html</a>
 */
public class JacocoCoverage extends AbstractCoverage
{
    private static final int TAB_WIDTH = 4;

    private Instrumenter instrumenter;
    private JacocoReportReader reportReader;

    private File dest;
    private File xml;
    private File report;

    private List<File> source;
    private List<File> execFiles;
    private List<File> classFiles;
    private List<File> sourceFiles;

    public JacocoCoverage(MavenProject mavenProject, File mavenHome, DebloatTypeEnum debloatTypeEnum)
    {
        super(mavenProject, mavenHome, debloatTypeEnum);
        report = new File(mavenProject.getBasedir().getAbsolutePath() + "/target/report.xml");
        LOGGER = LogManager.getLogger(JacocoCoverage.class.getName());
    }

    public JacocoCoverage(MavenProject mavenProject, File mavenHome, DebloatTypeEnum debloatTypeEnum,
        String entryClass, String entryMethod, String entryParameters)
    {
        super(mavenProject, mavenHome, debloatTypeEnum, entryClass, entryMethod, entryParameters);
        LOGGER = LogManager.getLogger(JacocoCoverage.class.getName());
    }

    protected UsageAnalysis executeConservativeAnalysis()
    {
        // TODO implement the conservative approach
        return null;
    }

    protected UsageAnalysis executeEntryPointAnalysis()
    {
        // TODO implement the entry point approach
        LOGGER.info("Output directory: " + mavenProject.getBuild().getOutputDirectory());
        LOGGER.info("entryClass: " + entryClass);
        LOGGER.info("entryMethod: " + entryMethod);
        LOGGER.info("entryParameters: " + entryParameters);
        return null;
    }

    protected UsageAnalysis executeTestBasedAnalysis()
    {
        // Write the JaCoCo coverage report to file
        try {
            writeCoverage();
        } catch (Exception e) {
            LOGGER.error("Error writing coverage file.");
        }

        // Read the jacoco report
        try {
            reportReader = new JacocoReportReader();
        } catch (ParserConfigurationException e) {
            LOGGER.error("Error parsing jacoco.xml file.");
        }

        // Retrieve the usage analysis report
        try {
            assert reportReader != null;
            return reportReader.getUsedClassesAndMethods(report);
        } catch (IOException | SAXException e) {
            LOGGER.error("Error getting unused classes and methods file.");
        }
        return null;
    }

    @Override
    protected void writeCoverage()
    {
        LOGGER.info("Running JaCoCo");
        final String baseDir = mavenProject.getBasedir().getAbsolutePath();
        final String classesDir = baseDir + "/target/classes";
        final String testDir = baseDir + "/target/test-classes";
        final String instrumentedDir = baseDir + "/target/instrumented";
        final String classesOriginalDir = baseDir + "/target/classes-original";

        source = Arrays.asList(new File(classesDir));
        dest = new File(instrumentedDir);
        execFiles = Arrays.asList(new File(baseDir + "/jacoco.exec"));
        classFiles = Arrays.asList(new File(classesDir));
        sourceFiles = mavenProject.getCompileSourceRoots().stream().map(File::new).collect(Collectors.toList());
        xml = new File(baseDir + "/target/report.xml");

        MavenUtils mavenUtils = copyDependencies(classesDir);
        deleteNonClassFiles(classesDir);
        try {
            executeInstrumentation();
        } catch (IOException e) {
            LOGGER.error("Error executing instrumentation.");
        }
        replaceInstrumentedClasses(classesDir, instrumentedDir, classesOriginalDir);
        addJaCoCoAsTestDependency(testDir, mavenUtils);
        runTests();
        restoreOriginalClasses(classesDir, classesOriginalDir);
        try {
            writeReports();
        } catch (IOException e) {
            LOGGER.error("Error writing coverage reports.");
        }
    }

    /**
     * Write the JaCoCo coverage reports.
     */
    private int writeReports() throws IOException
    {
        final ExecFileLoader loader = loadExecutionData();
        final IBundleCoverage bundle = analyze(loader.getExecutionDataStore());
        writeReports(bundle, loader);
        return 0;
    }

    /**
     * Reads the JaCoCo .exec data file.
     */
    private ExecFileLoader loadExecutionData() throws IOException
    {
        final ExecFileLoader loader = new ExecFileLoader();
        if (execFiles.isEmpty()) {
            LOGGER.warn("No execution data files provided.");
        } else {
            for (final File file : execFiles) {
                LOGGER.info("Loading execution data file " + file.getAbsolutePath());
                loader.load(file);
            }
        }
        return loader;
    }

    /**
     * Analyze the coverage of all the classes based on execution data.
     */
    private IBundleCoverage analyze(final ExecutionDataStore data) throws IOException
    {
        final CoverageBuilder builder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(data, builder);
        for (final File f : classFiles) {
            analyzer.analyzeAll(f);
        }
        printNoMatchWarning(builder.getNoMatchClasses());
        final String name = "JaCoCo Coverage Report";
        return builder.getBundle(name);
    }

    /**
     * Classes should match with the bytecode, otherwise the instrumentation could be inaccurate.
     */
    private void printNoMatchWarning(final Collection<IClassCoverage> nomatch)
    {
        if (!nomatch.isEmpty()) {
            LOGGER.warn("Some classes do not match with execution data.");
            LOGGER.warn("For report generation the same class files must be used as at runtime.");
            for (final IClassCoverage c : nomatch) {
                LOGGER.warn("Execution data for class" + c.getName() + "does not match.");
            }
        }
    }

    /**
     * Write coverage report.
     */
    private void writeReports(final IBundleCoverage bundle, final ExecFileLoader loader) throws IOException
    {
        LOGGER.info("Analyzing " + Integer.valueOf(bundle.getClassCounter().getTotalCount()) + " classes");
        final IReportVisitor visitor = createReportVisitor();
        visitor.visitInfo(loader.getSessionInfoStore().getInfos(), loader.getExecutionDataStore().getContents());
        visitor.visitBundle(bundle, getSourceLocator());
        visitor.visitEnd();
    }

    /**
     * The IReportVisitor is needed to visit all the bytecode instructions in the report.
     */
    private IReportVisitor createReportVisitor() throws IOException
    {
        final List<IReportVisitor> visitors = new ArrayList<>();
        final XMLFormatter formatter = new XMLFormatter();
        visitors.add(formatter.createVisitor(new FileOutputStream(xml)));
        return new MultiReportVisitor(visitors);
    }

    /**
     * The ISourceFileLocator is needed to look-up source files that will be included with the coverage report.
     */
    private ISourceFileLocator getSourceLocator()
    {
        final MultiSourceFileLocator multi = new MultiSourceFileLocator(TAB_WIDTH);
        for (final File f : sourceFiles) {
            multi.add(new DirectorySourceFileLocator(f, null, TAB_WIDTH));
        }
        return multi;
    }

    /**
     * Instrument the bytecode.
     */
    private void executeInstrumentation() throws IOException
    {
        final File absoluteDest = dest.getAbsoluteFile();
        instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());
        int total = 0;
        for (final File s : source) {
            if (s.isFile()) {
                total += instrument(s, new File(absoluteDest, s.getName()));
            } else {
                total += instrumentRecursive(s, absoluteDest);
            }
        }
        LOGGER.info(Integer.valueOf(total) + " classes instrumented to " + absoluteDest);
    }

    /**
     * Recursively instrument the bytecode of all the classes in the src directory and its sub-directories.
     */
    private int instrumentRecursive(final File src, final File dest) throws IOException
    {
        int total = 0;
        if (src.isDirectory()) {
            for (final File child : src.listFiles()) {
                total += instrumentRecursive(child, new File(dest, child.getName()));
            }
        } else {
            total += instrument(src, dest);
        }
        return total;
    }

    /**
     * Instrument the bytecode by inserting probes at the branch level.
     */
    private int instrument(final File src, final File dest) throws IOException
    {
        dest.getParentFile().mkdirs();
        try (InputStream input = new FileInputStream(src)) {
            try (OutputStream output = new FileOutputStream(dest)) {
                return instrumenter.instrumentAll(input, output,
                    src.getAbsolutePath());
            }
        } catch (final IOException e) {
            Files.delete(dest.toPath());
            throw e;
        }
    }

    /**
     * The instrumented classes need JaCoCo to compile with the inserted probes.
     */
    private void addJaCoCoAsTestDependency(String testDir, MavenUtils mavenUtils)
    {
        mavenUtils.copyDependency("org.jacoco:org.jacoco.agent:0.8.5", testDir);
        JarUtils.decompressJars(testDir);
    }
}
