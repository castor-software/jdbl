package se.kth.castor.jdbl.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

public class CmdExec
{

   //--------------------------------/
   //-------- CLASS FIELD/S --------/
   //------------------------------/

   private static final Logger LOGGER = LogManager.getLogger(CmdExec.class.getName());

   //--------------------------------/
   //------- PUBLIC METHOD/S -------/
   //------------------------------/

   /**
    * Creates a system process to execute a Java class with its parameters via command line.
    * E.g, java -verbose:class -jar target/clitools-1.0.0-SNAPSHOT-jar-with-dependencies.jar whoami | grep "\[Loaded " |
    * grep -v " from /usr/lib" | cut -d ' ' -f2 | sort > loaded-classes
    *
    * @param classPath               Path to the .class file
    * @param clazzFullyQualifiedName Fully qualified name of the class to execute
    * @param parameters              The parameters (i.e, -verbose:class)
    * @return The set of classes in the classpath that were loaded during the execution of the class
    */
   public Set<String> execProcess(String classPath, String clazzFullyQualifiedName, String[] parameters)
   {
      Set<String> result = new HashSet<>();
      try {
         String line;
         String[] cmd = {"java",
            "-verbose:class",
            "-classpath",
            classPath,
            clazzFullyQualifiedName};
         cmd = ArrayUtils.addAll(cmd, parameters);
         for (String s : cmd) {
            System.out.print(s + " ");
         }
         System.out.println();
         Process p = Runtime.getRuntime().exec(cmd);
         BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()));
         while ((line = input.readLine()) != null) {
            System.out.println(line);
            if (line.startsWith("[Loaded ") && line.endsWith("target/classes" + "/]")) {
               result.add(line.split(" ")[1]);
            }
         }
         input.close();
      } catch (Exception e) {
         LOGGER.error("Failed to run: " + e);
      }
      return result;
   }
}
