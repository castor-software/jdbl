package se.kth.castor.jdbl.coverage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.log4j.LogManager;
import org.apache.maven.project.MavenProject;

import com.fasterxml.jackson.databind.ObjectMapper;
import se.kth.castor.jdbl.debloat.DebloatTypeEnum;
import se.kth.castor.jdbl.util.JarUtils;
import se.kth.castor.jdbl.util.MavenUtils;
import se.kth.castor.offline.CoverageInstrumenter;
import se.kth.castor.yajta.api.MalformedTrackingClassException;

public class YajtaCoverage extends AbstractCoverage
{
    public YajtaCoverage(MavenProject mavenProject, File mavenHome, DebloatTypeEnum debloatTypeEnum)
    {
        super(mavenProject, mavenHome, debloatTypeEnum);
        LOGGER = LogManager.getLogger(YajtaCoverage.class.getName());
    }

    public YajtaCoverage(MavenProject mavenProject, File mavenHome, DebloatTypeEnum debloatTypeEnum,
        String entryClass, String entryMethod, String entryParameters)
    {
        super(mavenProject, mavenHome, debloatTypeEnum, entryClass, entryMethod, entryParameters);
        LOGGER = LogManager.getLogger(YajtaCoverage.class.getName());
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
        writeCoverage();
        UsageAnalysis usageAnalysis = new UsageAnalysis();
        final String projectBasedir = mavenProject.getBasedir().getAbsolutePath();
        Set<String> filesInBasedir = listFilesInDirectory(projectBasedir);
        // Yajta could produce more than one coverage file (in case of parallel testing), so we need to read all of them
        for (String fileName : filesInBasedir) {
            if (fileName.startsWith("yajta_coverage")) {
                String json;
                try {
                    json = new String(Files.readAllBytes(Paths.get(projectBasedir +
                        "/" + fileName)), StandardCharsets.UTF_8);
                    ObjectMapper mapper = new ObjectMapper();
                    // Convert JSON string to Map
                    Map<String, ArrayList<String>> map = mapper.readValue(json, Map.class);
                    Iterator it = map.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry pair = (Map.Entry) it.next();
                        // Add the yajta coverage results to the jacoco analysis
                        final String className = String.valueOf(pair.getKey()).replace(".", "/");
                        ArrayList<String> yajtaMethods = map.get(pair.getKey());
                        usageAnalysis.getAnalysis().put(className, new HashSet<>(yajtaMethods));
                    }
                } catch (IOException e) {
                    LOGGER.error("Error reading the yajta coverage file.");
                }
            }
        }
        return usageAnalysis;
    }

    public void writeCoverage()
    {
        LOGGER.info("Running yajta");
        final String classesDir = mavenProject.getBasedir().getAbsolutePath() + "/target/classes";
        final String testDir = mavenProject.getBasedir().getAbsolutePath() + "/target/test-classes";
        final String instrumentedDir = mavenProject.getBasedir().getAbsolutePath() + "/target/instrumented";
        final String classesOriginalDir = mavenProject.getBasedir().getAbsolutePath() + "/target/classes-original";
        MavenUtils mavenUtils = copyDependencies(classesDir);
        deleteNonClassFiles(classesDir);
        instrument(classesDir, instrumentedDir);
        replaceInstrumentedClasses(classesDir, instrumentedDir, classesOriginalDir);
        addYajtaAsTestDependency(testDir, mavenUtils);
        restoreOriginalClasses(classesDir, classesOriginalDir);
    }

    /**
     * The instrumented classes need yajta to compile with the inserted probes.
     */
    private void addYajtaAsTestDependency(final String testDir, final MavenUtils mavenUtils)
    {
        mavenUtils.copyDependency("se.kth.castor:yajta-core:2.0.2", testDir);
        mavenUtils.copyDependency("se.kth.castor:yajta-offline:2.0.2", testDir);
        JarUtils.decompressJars(testDir);
        super.runTests();
    }

    /**
     * Instrument classes with yajta.
     */
    private void instrument(final String classesDir, final String instrumentedDir)
    {
        try {
            CoverageInstrumenter.main(new String[]{
                "-i", classesDir,
                "-o", instrumentedDir});
        } catch (MalformedTrackingClassException e) {
            LOGGER.error("Error executing yajta.");
        }
    }

    /**
     * Recursively retrieve the absolute paths of al the files in a directory.
     */
    private Set<String> listFilesInDirectory(String dir)
    {
        return Stream.of(new File(dir).listFiles())
            .filter(file -> !file.isDirectory())
            .map(File::getName)
            .collect(Collectors.toSet());
    }
}
