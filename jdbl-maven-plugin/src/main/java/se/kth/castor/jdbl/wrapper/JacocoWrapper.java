package se.kth.castor.jdbl.wrapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.maven.project.MavenProject;
import org.xml.sax.SAXException;

import eu.stamp_project.testrunner.EntryPoint;
import javax.xml.parsers.ParserConfigurationException;
import se.kth.castor.jdbl.util.ClassesLoadedSingleton;
import se.kth.castor.jdbl.util.CmdExec;
import se.kth.castor.jdbl.util.JarUtils;
import se.kth.castor.jdbl.util.MavenUtils;

public class JacocoWrapper
{

   private static final Logger LOGGER = LogManager.getLogger(JacocoWrapper.class.getName());
   private MavenProject mavenProject;
   private String entryClass;
   private String entryMethod;
   private String entryParameters;
   private List<String> tests;
   private File mavenHome;
   private File report;
   private DebloatTypeEnum debloatTypeEnum;
   private boolean isJunit5 = false;

   public JacocoWrapper(MavenProject mavenProject,
      File report,
      DebloatTypeEnum debloatTypeEnum)
   {
      this.mavenProject = mavenProject;
      this.report = report;
      this.debloatTypeEnum = debloatTypeEnum;
      this.tests = new ArrayList<>();
      if (report.exists()) {
         FileUtils.deleteQuietly(report);
      }
   }

   public JacocoWrapper(MavenProject mavenProject,
      File report,
      DebloatTypeEnum debloatTypeEnum,
      String entryClass,
      String entryMethod,
      String entryParameters,
      File mavenHome)
   {
      this.mavenProject = mavenProject;
      this.report = report;
      this.debloatTypeEnum = debloatTypeEnum;
      this.entryClass = entryClass;
      this.entryMethod = entryMethod;
      this.entryParameters = entryParameters;
      this.mavenHome = mavenHome;
      if (report.exists()) {
         FileUtils.deleteQuietly(report);
      }
   }

   public Map<String, Set<String>> analyzeUsages() throws IOException, ParserConfigurationException, SAXException
   {
      MavenUtils mavenUtils = new MavenUtils(this.mavenHome, this.mavenProject.getBasedir());
      Properties propertyTestClasspath = new Properties();
      propertyTestClasspath.setProperty("mdep.outputFile", this.mavenProject.getBasedir().getAbsolutePath() + "/target/test-classpath");
      propertyTestClasspath.setProperty("scope", "test");

      // write all the test classpath to a local file
      mavenUtils.runMaven(Collections.singletonList("dependency:build-classpath"), propertyTestClasspath);

      Properties propertyCopyDependencies = new Properties();
      propertyCopyDependencies.setProperty("outputDirectory", this.mavenProject.getBasedir().getAbsolutePath() + "/target/classes");
      propertyCopyDependencies.setProperty("includeScope", "compile");
      mavenUtils.runMaven(Collections.singletonList("dependency:copy-dependencies"), propertyCopyDependencies);
      JarUtils.decompressJars(this.mavenProject.getBasedir().getAbsolutePath() + "/target/classes");

      // instrument the code
      mavenUtils.runMaven(Collections.singletonList("org.jacoco:jacoco-maven-plugin:0.8.4:instrument"), null);

      switch (this.debloatTypeEnum) {
         case TEST_DEBLOAT:
            this.testBasedDebloat();
            break;
         case ENTRY_POINT_DEBLOAT:
            this.entryPointDebloat();
            break;
         case CONSERVATIVE_DEBLOAT:
            // TODO implement the conservative approach
            break;
      }

      // move the jacoco exec file to the target dir
      // FileUtils.moveFile(new File(this.mavenProject.getBasedir(), "jacoco.exec"), new File(this.mavenProject.getBasedir(), "target/jacoco.exec"));

      // restore instrumented classes and generate the jacoco xml report
      mavenUtils.runMaven(Arrays.asList(
         "org.jacoco:jacoco-maven-plugin:0.8.4:restore-instrumented-classes",
         "org.jacoco:jacoco-maven-plugin:0.8.4:report"), null);

      // move the jacoco xml report
      FileUtils.moveFile(new File(this.mavenProject.getBasedir(), "target/site/jacoco/jacoco.xml"), this.report);

      // read the jacoco report
      JacocoReportReader reportReader = new JacocoReportReader();

      return reportReader.getUnusedClassesAndMethods(this.report);
   }

   private void entryPointDebloat() throws IOException
   {
      LOGGER.info("Output directory: " + mavenProject.getBuild().getOutputDirectory());
      LOGGER.info("entryClass: " + entryClass);
      LOGGER.info("entryParameters: " + entryParameters);
      // add jacoco to the classpath
      String classpath = addJacocoToClasspath(mavenProject.getBasedir().getAbsolutePath() + "/target/test-classpath");
      // execute the application from entry point
      CmdExec cmdExecEntryPoint = new CmdExec();
      Set<String> classesLoaded = cmdExecEntryPoint.execProcess(classpath, entryClass, entryParameters.split(" "));
      // print info about the number of classes loaded
      LOGGER.info("Number of classes loaded: " + classesLoaded.size());
      ClassesLoadedSingleton.INSTANCE.setClassesLoaded(classesLoaded);
      // list the classes loaded
      ClassesLoadedSingleton.INSTANCE.printClassesLoaded();
   }

   private void testBasedDebloat() throws IOException
   {
      // add jacoco to the classpath
      String classpathTest = this.addJacocoToClasspath(String.format("%s/target/test-classpath",
         this.mavenProject.getBasedir().getAbsolutePath()));

      // collect test classes
      StringBuilder entryParametersTest = new StringBuilder();
      for (String test : this.findTestFiles(this.mavenProject.getBuild().getTestOutputDirectory())) {
         StringBuilder testSb = new StringBuilder(test);

         // do not consider inner classes in test
         if (!testSb.toString().contains("$")) {
            entryParametersTest.append(testSb.append(" "));
         }
      }

      // execute all the tests classes
      EntryPoint.JVMArgs = "-verbose:class";
      EntryPoint.persistence = true;
      EntryPoint.verbose = true;
      EntryPoint.jUnit5Mode = this.isJunit5;

      Set<String> classesLoadedTestDebloat = new HashSet<>();
      try {
         ByteArrayOutputStream outStream = new ByteArrayOutputStream();
         PrintStream outPrint = new PrintStream(outStream);
         EntryPoint.outPrintStream = outPrint;
         EntryPoint.runTests(classpathTest + ":" +
            this.mavenProject.getBuild().getOutputDirectory() + ":" +
            this.mavenProject.getBuild().getTestOutputDirectory(), entryParametersTest.toString().split(" "));
         final String[] lines = outStream.toString().split("\n");
         for (String line : lines) {
            if (line.startsWith("[Loaded ") && line.endsWith("target/classes" + "/]")) {
               classesLoadedTestDebloat.add(line.split(" ")[1]);
            }
         }
      } catch (TimeoutException e) {
         LOGGER.error("Error getting the loaded classes after executing the test cases.");
      }

      // CmdExec cmdExecTestDebloat = new CmdExec();
      // Set<String> classesLoadedTestDebloat = cmdExecTestDebloat.execProcess(
      //    classpathTest + ":" + this.mavenProject.getBuild().getOutputDirectory() + ":" + this.mavenProject.getBuild().getTestOutputDirectory(),
      //    "org.junit.runner.JUnitCore",
      //    entryParametersTest.toString().split(" "));

      // print info about the number of classes loaded
      ClassesLoadedSingleton.INSTANCE.setClassesLoaded(classesLoadedTestDebloat);
      // print the list of classes loaded
      ClassesLoadedSingleton.INSTANCE.printClassesLoaded();
   }

   private String addJacocoToClasspath(String file) throws IOException
   {
      StringBuilder rawFile;
      try (BufferedReader buffer = new BufferedReader(new FileReader(file))) {
         rawFile = new StringBuilder(this.mavenProject.getBasedir().getAbsolutePath() + "/target/classes/:");
         String line;
         while ((line = buffer.readLine()) != null) {
            rawFile.append(line);
         }
      }
      return rawFile.toString();
   }

   private List<String> findTestFiles(String testOutputDirectory)
   {
      File file = new File(testOutputDirectory);
      File[] list = file.listFiles();
      assert list != null;
      for (File testFile : list) {
         if (testFile.isDirectory()) {
            this.findTestFiles(testFile.getAbsolutePath());
         } else if (testFile.getName().endsWith(".class")) {
            String testName = testFile.getAbsolutePath();
            // Get the binary name of the test file
            tests.add(testName.replaceAll("/", ".")
               .substring(mavenProject.getBuild().getTestOutputDirectory().length() + 1, testName.length() - 6));
         }
      }
      return tests;
   }
}
