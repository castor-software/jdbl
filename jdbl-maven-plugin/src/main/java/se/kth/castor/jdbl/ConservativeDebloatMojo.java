package se.kth.castor.jdbl;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import se.kth.castor.jdbl.callgraph.JCallGraphModified;
import se.kth.castor.jdbl.debloat.AbstractMethodDebloat;
import se.kth.castor.jdbl.debloat.ConservativeMethodDebloat;
import se.kth.castor.jdbl.util.FileUtils;
import se.kth.castor.jdbl.util.JarUtils;
import se.kth.castor.jdbl.util.MavenUtils;

/**
 * This Maven mojo statically instruments the project and its dependencies in order to remove unused API members.
 */
@Mojo(name = "conservative-debloat", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class ConservativeDebloatMojo extends AbstractMojo
{

   /**
    * The maven home file, assuming either an environment variable M2_HOME, or that mvn command exists in PATH.
    */
   private static final File mavenHome = new File(System.getenv().get("M2_HOME"));
   private static final String LINE_SEPARATOR = "------------------------------------------------------------------------";

   @Parameter(defaultValue = "${project}", readonly = true)
   private MavenProject project;

   @Override
   public void execute()
   {
      printToConsole("S T A R T I N G    C O N S E R V A T I V E    D E B L O A T");

      String outputDirectory = this.project.getBuild().getOutputDirectory();
      File baseDir = this.project.getBasedir();

      MavenUtils mavenUtils = new MavenUtils(ConservativeDebloatMojo.mavenHome, baseDir);

      // copy the dependencies
      mavenUtils.copyDependencies(outputDirectory);

      // copy the resources
      mavenUtils.copyResources(outputDirectory);

      // decompress the copied dependencies
      JarUtils.decompressJars(outputDirectory);

      JCallGraphModified jCallGraphModified = new JCallGraphModified();

      // run de static usage analysis
      Map<String, Set<String>> usageAnalysis = jCallGraphModified.runUsageAnalysis(this.project.getBuild().getOutputDirectory());
      Set<String> classesUsed = usageAnalysis.keySet();

      this.getLog().info(String.format("#Total classes: %d",
         (long) usageAnalysis.entrySet().size()));
      this.getLog().info(String.format("#Unused classes: %d",
         usageAnalysis.entrySet().stream().filter(e -> e.getValue() == null).count()));
      this.getLog().info(String.format("#Unused methods: %d",
         usageAnalysis.values().stream().filter(Objects::nonNull).mapToInt(Set::size).sum()));

      // delete unused classes
      FileUtils fileUtils = new FileUtils(outputDirectory,
         new HashSet<>(),
         classesUsed,
         new File(this.project.getBasedir().getAbsolutePath() + "/" + "debloat-report.csv"));
      try {
         fileUtils.deleteUnusedClasses(outputDirectory);
      } catch (IOException e) {
         this.getLog().error(String.format("Error deleting unused classes: %s", e));
      }

      // delete unused methods
      AbstractMethodDebloat conservativeMethodDebloat = new ConservativeMethodDebloat(outputDirectory,
         usageAnalysis,
         new File(this.project.getBasedir().getAbsolutePath() + "/" + "debloat-report.csv"));
      try {
         conservativeMethodDebloat.removeUnusedMethods();
      } catch (IOException e) {
         this.getLog().error(String.format("Error: %s", e));
      }

      printToConsole("C O N S E R V A T I V E    D E B L O A T    F I N I S H E D");
   }

   private void printToConsole(final String s)
   {
      this.getLog().info(LINE_SEPARATOR);
      this.getLog().info(s);
      this.getLog().info(LINE_SEPARATOR);
   }
}