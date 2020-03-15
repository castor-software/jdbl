package se.kth.castor.jdbl;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import se.kth.castor.jdbl.debloat.AbstractMethodDebloat;
import se.kth.castor.jdbl.debloat.TestBasedMethodDebloat;
import se.kth.castor.jdbl.util.ClassesLoadedSingleton;
import se.kth.castor.jdbl.util.FileUtils;
import se.kth.castor.jdbl.util.JarUtils;
import se.kth.castor.jdbl.util.MavenUtils;
import se.kth.castor.jdbl.wrapper.DebloatTypeEnum;
import se.kth.castor.jdbl.wrapper.JacocoWrapper;

/**
 * This Mojo instruments the project according to the coverage of its test suite.
 * Probes are inserted in order to keep track of the classes and methods used.
 * Non covered classes are removed from the final jar file, the non covered
 * methods is replaced by an <code>UnsupportedOperationException</code>.
 */
@Mojo(name = "test-based-debloat", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class TestBasedDebloatMojo extends AbstractDebloatMojo
{
   @Override
   public void doExecute()
   {
      printCustomStringToConsole("S T A R T I N G    T E S T    B A S E D    D E B L O A T");

      Instant start = Instant.now();

      cleanReportFile();

      String outputDirectory = getProject().getBuild().getOutputDirectory();
      File baseDir = getProject().getBasedir();

      MavenUtils mavenUtils = new MavenUtils(getMavenHome(), baseDir);

      // copy the dependencies
      mavenUtils.copyDependencies(outputDirectory);

      // copy the resources
      mavenUtils.copyResources(outputDirectory);

      // decompress the copied dependencies
      JarUtils.decompressJars(outputDirectory);

      // run JaCoCo usage analysis
      Map<String, Set<String>> jaCoCoUsageAnalysis = this.getJaCoCoUsageAnalysis();
      Set<String> usedClasses = null;
      try {
         this.printClassesLoaded();
         usedClasses = TestBasedDebloatMojo.getUsedClasses(jaCoCoUsageAnalysis);
      } catch (RuntimeException e) {
         this.getLog().error("Error computing JaCoCo usage analysis.");
      }

      HashSet<JarUtils.DependencyFileMapper> dependencyFileMappers = JarUtils.getDependencyFileMappers();
      for (JarUtils.DependencyFileMapper fileMapper : dependencyFileMappers) {
         Iterator<String> dependencyIterator = fileMapper.getDependencyClassMap().keySet().iterator();
         while (dependencyIterator.hasNext()) {
            String dependencyJarName = dependencyIterator.next();
            System.out.println(dependencyJarName);
            Iterator<String> classIterator = fileMapper.getDependencyClassMap().get(dependencyJarName).iterator();
            while (classIterator.hasNext()) {
               String classInTheDependency = classIterator.next();
               if(usedClasses.contains(classInTheDependency)){
                  System.out.println("\t" + "UsedClass, " + classInTheDependency);
               }else{
                  System.out.println("\t" + "BloatedClass, " + classInTheDependency);
               }
            }
         }
      }


      // remove unused classes
      this.getLog().info("Starting removing unused classes...");
      this.removeUnusedClasses(outputDirectory, usedClasses);

      System.out.println("+" + usedClasses);



      // remove unused methods
      this.getLog().info("Starting removing unused methods...");
      this.removeUnusedMethods(outputDirectory, jaCoCoUsageAnalysis);

      // write log file with the plugin's execution time
      writeTimeElapsedReportFile(start);

      printCustomStringToConsole("T E S T S    B A S E D    D E B L O A T    F I N I S H E D");
   }

   private void writeTimeElapsedReportFile(final Instant start)
   {
      Instant finish = Instant.now();
      double timeElapsed = Duration.between(start, finish).toMillis();
      final String timeElapsedInSeconds = "Total debloat time: " + timeElapsed / 1000 + " s";
      this.getLog().info(timeElapsedInSeconds);
      try {
         final String reportExecutionTimeFileName = "debloat-execution-time.log";
         org.apache.commons.io.FileUtils.write(new File(getProject().getBasedir().getAbsolutePath() + File.pathSeparator +
               reportExecutionTimeFileName),
            timeElapsedInSeconds);
      } catch (IOException e) {
         this.getLog().error("Error creating time elapsed report file.");
      }
   }

   private void cleanReportFile()
   {
      try {
         org.apache.commons.io.FileUtils.write(new File(getProject().getBasedir().getAbsolutePath() + File.pathSeparator +
            getReportFileName()), "");
      } catch (IOException e) {
         this.getLog().error("Error cleaning report file.");
      }
   }

   private void printClassesLoaded()
   {
      final int nbOfClassesLoaded = ClassesLoadedSingleton.INSTANCE.getClassesLoaded().size();
      this.getLog().info("Loaded classes (" + nbOfClassesLoaded + ')');
      ClassesLoadedSingleton.INSTANCE.getClassesLoaded().stream().forEach(System.out::println);
      this.getLog().info(getLineSeparator());
   }

   private void removeUnusedMethods(final String outputDirectory, final Map<String, Set<String>> usageAnalysis)
   {
      AbstractMethodDebloat testBasedMethodDebloat = new TestBasedMethodDebloat(outputDirectory,
         usageAnalysis,
         new File(getProject().getBasedir().getAbsolutePath() + File.pathSeparator + getReportFileName()));
      try {
         testBasedMethodDebloat.removeUnusedMethods();
      } catch (IOException e) {
         this.getLog().error(String.format("Error: %s", e));
      }
   }

   private void removeUnusedClasses(final String outputDirectory, final Set<String> usedClasses)
   {
      FileUtils fileUtils = new FileUtils(outputDirectory,
         new HashSet<>(),
         usedClasses,
         new File(getProject().getBasedir().getAbsolutePath() + File.pathSeparator + getReportFileName()));
      try {
         fileUtils.deleteUnusedClasses(outputDirectory);
      } catch (IOException e) {
         this.getLog().error(String.format("Error deleting unused classes: %s", e));
      }
   }

   private static Set<String> getUsedClasses(final Map<String, Set<String>> usageAnalysis)
   {
      // get the union of the JaCoCo output and the JVM class loader results
      Set<String> usedClasses = new HashSet<>(ClassesLoadedSingleton.INSTANCE.getClassesLoaded());
      usageAnalysis
         .entrySet()
         .stream()
         .filter(e -> e.getValue() != null)
         .forEach(className -> usedClasses.add(className.getKey().replace('/', '.')));
      return usedClasses;
   }

   private Map<String, Set<String>> getJaCoCoUsageAnalysis()
   {
      JacocoWrapper jacocoWrapper = new JacocoWrapper(getProject(),
         new File(getProject().getBasedir().getAbsolutePath() + "/target/report.xml"),
         DebloatTypeEnum.TEST_DEBLOAT);
      Map<String, Set<String>> usageAnalysis = null;
      try {
         usageAnalysis = jacocoWrapper.analyzeUsages();
         this.printJaCoCoUsageAnalysisResults(usageAnalysis);
      } catch (IOException | ParserConfigurationException | SAXException e) {
         this.getLog().error(e);
      }
      return usageAnalysis;
   }

   private void printJaCoCoUsageAnalysisResults(final Map<String, Set<String>> usageAnalysis)
   {
      this.getLog().info("JaCoCo ANALYSIS RESULTS:");
      this.getLog().info(String.format("Total unused classes: %d",
         usageAnalysis.entrySet().stream().filter(e -> e.getValue() == null).count()));
      this.getLog().info(String.format("Total unused methods: %d",
         usageAnalysis.values().stream().filter(Objects::nonNull).mapToInt(Set::size).sum()));
      this.getLog().info(getLineSeparator());
   }
}
