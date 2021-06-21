package com.kezong.fataar

import com.android.build.gradle.BaseExtension
import javassist.ByteArrayClassPath
import javassist.ClassMap
import javassist.ClassPool
import javassist.CtClass
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.TaskProvider

/**
 * process jars and classes
 * Created by Vigi on 2017/1/20.
 * Modified by kezong on 2018/12/18
 */
class ExplodedHelper {

    static void processLibsIntoLibs(Project project,
                                    Collection<AndroidArchiveLibrary> androidLibraries,
                                    Collection<File> jarFiles,
                                    File folderOut) {
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            if (androidLibrary.localJars.isEmpty()) {
                Utils.logInfo("Not found jar file, Library: ${androidLibrary.name}")
            } else {
                Utils.logInfo("Merge ${androidLibrary.name} local jar files")
                project.copy {
                    from(androidLibrary.localJars)
                    into(folderOut)
                }
            }
        }
        for (jarFile in jarFiles) {
            if (jarFile.exists()) {
                Utils.logInfo("Copy jar from: $jarFile to $folderOut.absolutePath")
                project.copy {
                    from(jarFile)
                    into(folderOut)
                }
            } else {
                Utils.logInfo('[warning]' + jarFile + ' not found!')
            }
        }
    }

    static void processClassesJarInfoClasses(Project project,
                                             Collection<AndroidArchiveLibrary> androidLibraries,
                                             File folderOut) {
        Utils.logInfo('Merge ClassesJar')
        Collection<File> allJarFiles = new ArrayList<>()
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            allJarFiles.add(androidLibrary.classesJarFile)
        }
        for (jarFile in allJarFiles) {
            if (!jarFile.exists()) {
                continue;
            }
            project.copy {
                from project.zipTree(jarFile)
                into folderOut
                exclude 'META-INF/'
            }
        }
    }

    static void processLibsIntoClasses(Project project,
                                       Collection<AndroidArchiveLibrary> androidLibraries,
                                       Collection<File> jarFiles,
                                       File folderOut) {
        Utils.logInfo('Merge Libs')
        Collection<File> allJarFiles = new ArrayList<>()
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            Utils.logInfo('[androidLibrary]' + androidLibrary.getName())
            allJarFiles.addAll(androidLibrary.localJars)
        }
        for (jarFile in jarFiles) {
            if (jarFile.exists()) {
                allJarFiles.add(jarFile)
            }
        }
        for (jarFile in allJarFiles) {
            project.copy {
                from project.zipTree(jarFile)
                into folderOut
                exclude 'META-INF/'
            }
        }
    }

    static TaskProvider processRefractoryClasses(Project project,
                                                Map<String, String> replacePackagePath,
                                                Map<String, String> replaceClassPath,
                                                List<String> deletePaths,
                                                 List<File> clsDirs) {
        Map<String, String> pendingDeleteMap = new HashMap<>()
        Map<String, String> pendingCreateNewClass = new HashMap<>()
        Map<String, String> pendingReplaceClass = new HashMap<>()

        List<File> preDeleteClassFiles = new ArrayList<>();
        List<File> requestDeleteClassFiles = new ArrayList<>();

        ClassPool classPool = new ClassPool()
        clsDirs.forEach { classInputDir ->
            String classInputDirPath =classInputDir.absolutePath
            if(!classInputDirPath.endsWith("/")) {
                classInputDirPath += "/"
            }
            String classCopyInputDirPath = "${project.buildDir}/intermediates/fataar_classes/"
            File classCopyInputDirFile = new File(classCopyInputDirPath)
            if(classCopyInputDirFile.exists()) {
                println "delete copy dir $classCopyInputDirPath"
                classCopyInputDirFile.delete()
            }

            FileTree allClassFilter = project.fileTree(classInputDirPath)
            List<File> allClassFiles = new ArrayList<>();
            allClassFilter.filter { file ->
                file.name.endsWith(".class")
            }.toList().forEach {file ->
                allClassFiles.add(file)
            }

            project.copy {
                from(classInputDir)
                into(classCopyInputDirFile)
            }

            allClassFiles.forEach { classFile ->
                String classFullPackagePath = classFile.absolutePath.replace(classInputDirPath, "")
                        .replace("/", ".")
                String clsSuffix = ".class"
                if(classFullPackagePath.endsWith(clsSuffix)) {
                    classFullPackagePath = classFullPackagePath.substring(0, classFullPackagePath.length() - clsSuffix.length())
                }

                classPool.appendClassPath(new ByteArrayClassPath(
                        classFullPackagePath,
                        new File(classFile.absolutePath.replace(classInputDirPath, classCopyInputDirPath)).newInputStream().bytes
                ))
            }

            allClassFiles.forEach { curClassFile ->
                try {

                    String curClassPackagePath = curClassFile.absolutePath.replace(classInputDirPath, "")
                            .replace("/", ".")
                    String clsSuffix = ".class"
                    if(curClassPackagePath.endsWith(clsSuffix)) {
                        curClassPackagePath = curClassPackagePath.substring(0, curClassPackagePath.length() - clsSuffix.length())
                    }

//                    classPool.appendClassPath(new ByteArrayClassPath(
//                            curClassPackagePath,
//                            new File(curClassFile.absolutePath.replace(classInputDirPath, classCopyInputDirPath)).newInputStream().bytes
//                    ))


                    if(deletePaths.contains(curClassPackagePath)) {
                        requestDeleteClassFiles.add(curClassFile)
                    }

//                    classPool.appendClassPath(new ByteArrayClassPath(classPackagePath, classFile.newInputStream().bytes))
                    replacePackagePath.forEach { oldClassPath, newClassPath ->
//                        println("[SCAN] ${curClassPackagePath}  oldClassPath ${oldClassPath}")

                        if(curClassPackagePath.startsWith(oldClassPath)) {
                            CtClass oldClass = classPool.getOrNull(oldClassPath)
                            CtClass newClass = classPool.getOrNull(newClassPath)

                            println("[MATCH OLD] ${oldClass?.name}  oldClassPath ${newClass?.name}")

                            if(curClassPackagePath == oldClassPath && newClass != null && oldClass != null) {
                                pendingReplaceClass.put(
                                        curClassPackagePath,
                                        curClassPackagePath.replace(oldClassPath, newClassPath)
                                )
                            } else {
                                pendingCreateNewClass.put(
                                        curClassPackagePath,
                                        curClassPackagePath.replace(oldClassPath, newClassPath)
                                )
                            }
                            pendingDeleteMap.put(
                                    curClassPackagePath,
                                    curClassFile.absolutePath
                            )
                        } else {
                            pendingCreateNewClass.put(
                                    curClassPackagePath,
                                    curClassPackagePath
                            )
                            pendingDeleteMap.put(
                                    curClassPackagePath,
                                    curClassFile.absolutePath
                            )
                        }
                    }
                } catch(Exception e) {
                    e.printStackTrace()
                }
            }

            Object extension = project.plugins.findPlugin("com.android.library")
            if(extension != null && extension instanceof BaseExtension) {
                extension.bootClasspath.forEach { file ->
                    classPool.appendClassPath(file.absolutePath)
                }
            }
            boolean  hasError = false
            List<CtClass> pendingWriteFiles = new ArrayList<>()
            ClassMap changeClassMap = new ClassMap()

            // 预处理删除掉不需要的类
            pendingReplaceClass.entrySet().forEach { entry ->
                try {

                    String oldClassPath = entry.key
                    String newClassPath = entry.value
                    CtClass oldClass = classPool.getOrNull(oldClassPath)
                    if(oldClass != null) {
                        oldClass.detach()
                        File deleteFile = new File(pendingDeleteMap[newClassPath])
                        if(deleteFile.exists()) {
                            deleteFile.delete()
                            println("[DELETE] $deleteFile.absolutePath")

                        }
                    }

                    pendingCreateNewClass.put(newClassPath, oldClassPath)
                } catch(Exception e) {
                    println "[ERROR] ${e.message}"
                    hasError = true;
                }

            }

            pendingCreateNewClass.entrySet().forEach { entry ->
                try {

                    String oldClassPath = entry.key
                    String newClassPath = entry.value
                    CtClass ctClass = classPool.getOrNull(oldClassPath)
                    if(oldClassPath != newClassPath) {
                        CtClass oldClass = ctClass
                        CtClass newClass = classPool.getAndRename(oldClassPath, newClassPath)
                        changeClassMap.put(oldClass, newClass)
                        ctClass = newClass
                        println "[MODIFY PACKAGEPATH] ${oldClassPath} -> ${newClassPath}"
                    }

//                    File pendingDeleteFile = new File(pendingDeleteMap[oldClassPath])
//                    if(pendingDeleteFile.exists()) {
//                        pendingDeleteFile.delete()
//                        println("[DELETE] $pendingDeleteFile.absolutePath")
//
//                    }
                    pendingWriteFiles.add(ctClass)

                } catch(Exception e) {
                    println "[ERROR] ${e.message}"
                    hasError = true;
                }

            }

            pendingDeleteMap.values().forEach { deleteFilePath ->
                File pendingDeleteFile = new File(deleteFilePath)
                if(pendingDeleteFile.exists()) {
                    pendingDeleteFile.delete()
//                    println("[DELETE] $pendingDeleteFile.absolutePath")
                }
            }

            pendingWriteFiles.forEach { ctClass ->
//                println("[GENERATE CLASS] ${ctClass.name}")
                ctClass.replaceClassName(changeClassMap)
                ctClass.writeFile(classInputDirPath)
                ctClass.defrost()
            }

            requestDeleteClassFiles.forEach { file ->
                if(file.exists()) {
                    file.delete()
//                    println("[DELETE CLASS] ${file.absolutePath}")

                }
            }

        }




        null
    }
}
