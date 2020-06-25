package se.kth.castor.jdbl.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import se.kth.castor.jdbl.adapter.CustomClassReader;
import se.kth.castor.jdbl.coverage.UsageStatusEnum;

public class MyFileUtils
{
    /**
     * Counts the number of classes removed.
     */
    private int nbClassesRemoved;

    /**
     * The build outputDirectory.
     */
    private String outputDirectory;

    /**
     * Exclusion list of classes and package names that should not be removed.
     */
    private Set<String> exclusionSet;

    /**
     * Set of the binary names of classesUsed traced.
     */
    private Set<String> classesUsed;

    /**
     * Report path
     */
    private String projectBaseDir;

    /**
     * Class logger.
     */
    private static final Logger LOGGER = LogManager.getLogger(MyFileUtils.class.getName());

    /**
     * The classpath of the classes
     */
    private List<String> classpath;

    public MyFileUtils(String outputDirectory, Set<String> exclusionSet, Set<String> classesUsed, String projectBaseDir,
        List<String> classpath)
    {
        this.classpath = classpath;
        this.nbClassesRemoved = 0;
        this.outputDirectory = outputDirectory;
        this.exclusionSet = exclusionSet;
        this.classesUsed = classesUsed;
        this.projectBaseDir = projectBaseDir;
    }

    /**
     * Feed the list of non-removable classes.
     *
     * @param pathToFile The exclusion list file which contains the list of classes that will not be deleted.
     */
    public void setExclusionList(String pathToFile)
    {
        Path path = Paths.get(pathToFile);
        try (Stream<String> lines = Files.lines(path)) {
            lines.forEach(s -> this.exclusionSet.add(s.replaceAll("/", ".")));
        } catch (IOException e) {
            LOGGER.error(e);
        }
    }

    /**
     * Recursively remove unused classes in a directory.
     *
     * @param currentPath the absolute path of the directory to be processed.
     */
    public void deleteUnusedClasses(String currentPath) throws IOException
    {
        URLClassLoader urlClassLoader = null;
        MyFileWriter myFileWriter = new MyFileWriter(projectBaseDir);
        if (this.classpath != null) {
            URL[] urls = new URL[this.classpath.size()];
            for (int i = 0; i < this.classpath.size(); i++) {
                urls[i] = new File(this.classpath.get(i)).toURL();
            }
            urlClassLoader = new URLClassLoader(urls, null);
        }
        File file = new File(currentPath);
        File[] list = file.listFiles();
        assert list != null;
        for (File classFile : list) {
            if (classFile.isDirectory()) {
                // recursive call for directories
                deleteUnusedClasses(classFile.getAbsolutePath());
            } else if (classFile.getName().endsWith(".class")) {
                String classFilePath = classFile.getAbsolutePath();
                String currentClassName = getBinaryNameOfTestFile(classFilePath);
                FileType fileType = FileType.CLASS;
                try {
                    if (urlClassLoader != null) {
                        Class<?> aClass = urlClassLoader.loadClass(currentClassName);
                        if (aClass.isAnnotation()) {
                            fileType = FileType.ANNOTATION;
                            exclusionSet.add(currentClassName);
                        } else if (aClass.isInterface()) {
                            fileType = FileType.INTERFACE;
                            exclusionSet.add(currentClassName);
                        } else if (aClass.isEnum()) {
                            fileType = FileType.ENUM;
                            exclusionSet.add(currentClassName);
                        } else if (Modifier.isFinal(aClass.getModifiers())) {
                            fileType = FileType.CONSTANT;
                            exclusionSet.add(currentClassName);
                        } else {
                            try {
                                if (Modifier.isPrivate(aClass.getConstructor().getModifiers())) {
                                    fileType = FileType.CONSTANT;
                                    exclusionSet.add(currentClassName);
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                            try {
                                boolean allStatic = true;
                                for (Field field : aClass.getFields()) {
                                    allStatic = allStatic && Modifier.isStatic(field.getModifiers());
                                }
                                for (Method method : aClass.getMethods()) {
                                    allStatic = allStatic && Modifier.isStatic(method.getModifiers());
                                }
                                if (allStatic) {
                                    fileType = FileType.CONSTANT;
                                    exclusionSet.add(currentClassName);
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                            try {
                                if (Modifier.isStatic(aClass.getField("INSTANCE").getModifiers())) {
                                    fileType = FileType.CONSTANT;
                                    exclusionSet.add(currentClassName);
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                    }
                } catch (Throwable e) {
                    // ignore
                    fileType = FileType.UNKNOWN;
                }
                // do not remove interfaces
                CustomClassReader ccr = new CustomClassReader(new FileInputStream(classFilePath));

                if (!classesUsed.contains(currentClassName) &&
                    isRemovable(currentClassName.replace("/", ".")) &&
                    !exclusionSet.contains(currentClassName) &&
                    !ccr.isInterface() &&
                    !ccr.isException()) {
                    // get the current directory
                    File parent = new File(classFile.getParent());
                    // remove the file
                    LOGGER.info("Removed class: " + currentClassName);
                    // write report
                    myFileWriter.writeDebloatReport(UsageStatusEnum.BLOATED_CLASS.getName(), currentClassName, fileType);
                    Files.delete(classFile.toPath());
                    nbClassesRemoved++;
                    // remove the parent folder if is empty
                    while (parent.isDirectory() && Objects.requireNonNull(parent.listFiles()).length == 0) {
                        deleteDirectory(parent);
                        parent = parent.getParentFile();
                    }
                } else {
                    // write report
                    myFileWriter.writeDebloatReport(UsageStatusEnum.USED_CLASS.getName(), currentClassName, fileType);
                }
            }
        }
    }

    private String getBinaryNameOfTestFile(final String classFilePath)
    {
        return classFilePath
            .replaceAll("/", ".")
            .substring(outputDirectory.length() + 1, classFilePath.length() - 6);
    }

    public int nbClassesRemoved()
    {
        return nbClassesRemoved;
    }

    private boolean isRemovable(String className) throws IOException
    {
        //
        //        System.out.println("The classname: " + className);
        //
        //        boolean result;
        //
        //        BufferedReader reader;
        //        try {
        //            reader = new BufferedReader(new FileReader(
        //  "/home/cesarsv/Documents/papers/2019_papers/royal-debloat/jicocowrapper/experiments/clitools/loaded-classes"));
        //            String line = reader.readLine();
        //            while (line != null) {
        //                if (line.equals(className)) {
        //                    return false;
        //                }
        //                line = reader.readLine();
        //            }
        //            reader.close();
        //        } catch (IOException e) {
        //            e.printStackTrace();
        //        }

        return true;

        //        ClassReader cr = new ClassReader(new FileInputStream(new File(pathToClass)));
        //        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        //        ClassAdapter cv = new ClassAdapter(cw, outputDirectory);
        //        cr.accept(cv, 0);
        //
        //        return cv.isRemovable;
    }

    private void deleteDirectory(final File directory) throws IOException
    {
        if (!directory.exists()) {
            return;
        }
        if (!isSymlink(directory)) {
            cleanDirectory(directory);
        }
        if (!directory.delete()) {
            final String message = "Unable to delete directory " + directory + ".";
            throw new IOException(message);
        }
    }

    private boolean isSymlink(final File file)
    {
        if (file == null) {
            throw new NullPointerException("File must not be null");
        }
        return Files.isSymbolicLink(file.toPath());
    }

    private void cleanDirectory(final File directory) throws IOException
    {
        final File[] files = verifiedListFiles(directory);
        IOException exception = null;
        for (final File file : files) {
            try {
                forceDelete(file);
            } catch (final IOException ioe) {
                exception = ioe;
            }
        }
        if (null != exception) {
            throw exception;
        }
    }

    private File[] verifiedListFiles(final File directory) throws IOException
    {
        if (!directory.exists()) {
            final String message = directory + " does not exist";
            throw new IllegalArgumentException(message);
        }
        if (!directory.isDirectory()) {
            final String message = directory + " is not a directory";
            throw new IllegalArgumentException(message);
        }
        final File[] files = directory.listFiles();
        if (files == null) {  // null if security restricted
            throw new IOException("Failed to list contents of " + directory);
        }
        return files;
    }

    private void forceDelete(final File file) throws IOException
    {
        if (file.isDirectory()) {
            deleteDirectory(file);
        } else {
            final boolean filePresent = file.exists();
            if (!file.delete()) {
                if (!filePresent) {
                    throw new FileNotFoundException("File does not exist: " + file);
                }
                final String message = "Unable to delete file: " + file;
                throw new IOException(message);
            }
        }
    }
}
