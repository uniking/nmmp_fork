package com.nmmedit.apkprotect;

import com.nmmedit.apkprotect.andres.AxmlEdit;
import com.nmmedit.apkprotect.dex2c.Dex2c;
import com.nmmedit.apkprotect.dex2c.DexConfig;
import com.nmmedit.apkprotect.dex2c.GlobalDexConfig;
import com.nmmedit.apkprotect.dex2c.converter.instructionrewriter.InstructionRewriter;
import com.nmmedit.apkprotect.dex2c.converter.structs.RegisterNativesUtilClassDef;
import com.nmmedit.apkprotect.dex2c.filters.ClassAndMethodFilter;
import com.nmmedit.apkprotect.sign.ApkVerifyCodeGenerator;
import com.nmmedit.apkprotect.util.ApkUtils;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.*;

public class ApkProtect {

    public static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
    public static final String ANDROID_APP_APPLICATION = "android.app.Application";
    @Nonnull
    private final ApkFolders apkFolders;
    @Nonnull
    private final InstructionRewriter instructionRewriter;
    private final ApkVerifyCodeGenerator apkVerifyCodeGenerator;
    private final ClassAndMethodFilter filter;

    private ApkProtect(@Nonnull ApkFolders apkFolders,
                       @Nonnull InstructionRewriter instructionRewriter,
                       ApkVerifyCodeGenerator apkVerifyCodeGenerator,
                       ClassAndMethodFilter filter
    ) {
        this.apkFolders = apkFolders;

        this.instructionRewriter = instructionRewriter;

        this.apkVerifyCodeGenerator = apkVerifyCodeGenerator;
        this.filter = filter;

    }

    public void run() throws IOException {
        final File apkFile = apkFolders.getInApk();
        final File zipExtractDir = apkFolders.getZipExtractTempDir();

        try {
            byte[] manifestBytes = ApkUtils.getFile(apkFile, ANDROID_MANIFEST_XML);
            if (manifestBytes == null) {
                //??????apk??????
                throw new RuntimeException("Not is apk");
            }
            final String applicationClass = AxmlEdit.getApplicationName(manifestBytes);
            final String packageName = AxmlEdit.getPackageName(manifestBytes);


            //???????????????????????????c??????(??????opcode??????????????????apk???????????????)
            generateCSources(packageName);

            //??????????????????classesN.dex
            List<File> files = getClassesFiles(apkFile, zipExtractDir);
            if (files.isEmpty()) {
                throw new RuntimeException("No classes.dex");
            }
            //globalConfig??????configs?????????classesN.dex??????????????????
            final GlobalDexConfig globalConfig = Dex2c.handleDexes(files,
                    filter,
                    instructionRewriter,
                    apkFolders.getCodeGeneratedDir());


            //???????????????dex?????????
            final Set<String> mainDexClassTypeSet = new HashSet<>();
            //todo ??????????????????????????????????????????dex?????????class

            //application class ????????????
            final List<String> appClassTypes = getMainDexClasses(globalConfig, applicationClass);

            mainDexClassTypeSet.addAll(appClassTypes);


            //???????????????class??????????????????????????????????????????????????????????????????
            //static {
            //    NativeUtils.initClass(0);
            //}
            final List<File> outDexFiles = injectInstructionAndWriteToFile(
                    globalConfig,
                    mainDexClassTypeSet,
                    60000,
                    apkFolders.getTempDexDir());


            //???dex?????????so????????????
            File mainDex = outDexFiles.get(0);

            //Application?????????
            final String appName;
            if (appClassTypes.isEmpty()) {
                appName = ANDROID_APP_APPLICATION;
            } else {
                final String s = appClassTypes.get(appClassTypes.size() - 1);
                appName = s.substring(1, s.length() - 1).replace('/', '.');
            }


            //??????AndroidManifest.xml??????
            final File newManifestFile = handleApplicationClass(
                    manifestBytes,
                    appName,
                    mainDex,
                    globalConfig,
                    apkFolders.getOutRootDir());

            final Map<String, List<File>> nativeLibs = generateNativeLibs(apkFolders);


            try (
                    final ZipInputStream zipInput = new ZipInputStream(new FileInputStream(apkFile));
                    final ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(apkFolders.getOutputApk()));
            ) {
                zipCopy(zipInput, apkFolders.getZipExtractTempDir(), zipOutput);

                //add AndroidManifest.xml
                addFileToZip(zipOutput, newManifestFile, new ZipEntry(ANDROID_MANIFEST_XML));

                //add classesX.dex
                for (File file : outDexFiles) {
                    final ZipEntry zipEntry = new ZipEntry(file.getName());

                    addFileToZip(zipOutput, file, zipEntry);
                }

                //add native libs
                for (Map.Entry<String, List<File>> entry : nativeLibs.entrySet()) {
                    final String abi = entry.getKey();
                    for (File file : entry.getValue()) {
                        final ZipEntry zipEntry = new ZipEntry("lib/" + abi + "/" + file.getName());

                        addFileToZip(zipOutput, file, zipEntry);
                    }

                }


            }

        } finally {
            //????????????????????????
            deleteFile(zipExtractDir);
        }
        //


    }

    private void addFileToZip(ZipOutputStream zipOutput, File file, ZipEntry zipEntry) throws IOException {
        zipOutput.putNextEntry(zipEntry);
        try (
                FileInputStream input = new FileInputStream(file);
        ) {
            copyStream(input, zipOutput);
        }
        zipOutput.closeEntry();
    }

    private static Map<String, List<File>> generateNativeLibs(ApkFolders apkFolders) throws IOException {
        String cmakePath = System.getenv("CMAKE_PATH");
        if (isEmpty(cmakePath)) {
            System.err.println("No CMAKE_PATH");
            cmakePath = "";
        }
        String sdkHome = System.getenv("ANDROID_SDK_HOME");
        if (isEmpty(sdkHome)) {
            sdkHome = "/opt/android-sdk";
            System.err.println("No ANDROID_SDK_HOME. Default is " + sdkHome);
        }
        String ndkHome = System.getenv("ANDROID_NDK_HOME");
        if (isEmpty(ndkHome)) {
            ndkHome = "/opt/android-sdk/ndk/22.1.7171670";
            System.err.println("No ANDROID_NDK_HOME. Default is " + ndkHome);
        }

        final File outRootDir = apkFolders.getOutRootDir();
        final File apkFile = apkFolders.getInApk();
        final File script = File.createTempFile("build", ".sh", outRootDir);
        try (
                final InputStream in = ApkProtect.class.getResourceAsStream("/build.sh");
                final FileOutputStream out = new FileOutputStream(script);
        ) {
            copyStream(in, out);
        }
        final Map<String, List<File>> allLibs = new HashMap<>();
        try {
            final List<String> abis = getAbis(apkFile);
            for (String abi : abis) {
                final BuildNativeLib.CMakeOptions cmakeOptions = new BuildNativeLib.CMakeOptions(cmakePath,
                        sdkHome,
                        ndkHome, 21,
                        outRootDir.getAbsolutePath(), BuildNativeLib.CMakeOptions.BuildType.RELEASE, abi);
                final List<File> files = BuildNativeLib.build(script.getPath(), cmakeOptions);
                allLibs.put(abi, files);
            }

        } finally {
            script.delete();
        }
        return allLibs;

    }

    private static boolean isEmpty(String cmakePath) {
        return cmakePath == null || "".equals(cmakePath);
    }

    //??????apk???????????????abi???????????????????????????????????????
    private static List<String> getAbis(File apk) throws IOException {
        final Pattern pattern = Pattern.compile("lib/(.*)/.*\\.so");
        final ZipFile zipFile = new ZipFile(apk);
        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
        Set<String> abis = new HashSet<>();
        while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            final Matcher matcher = pattern.matcher(entry.getName());
            if (matcher.matches()) {
                abis.add(matcher.group(1));
            }
        }
        if (abis.isEmpty()) {
            return Arrays.asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64");
        }
        return new ArrayList<>(abis);
    }

    private void generateCSources(String packageName) throws IOException {
        final List<File> cSources = ApkUtils.extractFiles(
                ApkProtect.class.getResourceAsStream("/vmsrc.zip"), ".*", apkFolders.getDex2cSrcDir()
        );
        //???????????????apk??????,????????????c??????
        for (File source : cSources) {
            if (source.getName().endsWith("DexOpcodes.h")) {
                //????????????????????????????????????DexOpcodes.h??????
                writeOpcodeHeaderFile(source, instructionRewriter);
            } else if (source.getName().endsWith("apk_verifier.c")) {
                //??????????????????????????????????????????
                writeApkVerifierFile(packageName, source, apkVerifyCodeGenerator);
            }
        }
    }

    @Nonnull
    private static List<File> getClassesFiles(File apkFile, File zipExtractDir) throws IOException {
        List<File> files = ApkUtils.extractFiles(apkFile, "classes(\\d+)*\\.dex", zipExtractDir);
        //??????classes??????????????????
        files.sort((file, t1) -> {
            final String numb = file.getName().replace("classes", "").replace(".dex", "");
            final String numb2 = t1.getName().replace("classes", "").replace(".dex", "");
            int n, n2;
            if ("".equals(numb)) {
                n = 0;
            } else {
                n = Integer.parseInt(numb);
            }
            if ("".equals(numb2)) {
                n2 = 0;
            } else {
                n2 = Integer.parseInt(numb2);
            }
            return n - n2;
        });
        return files;
    }

    //????????????????????????,??????????????????opcode
    private static void writeOpcodeHeaderFile(File source, InstructionRewriter instructionRewriter) throws IOException {
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8));

        final String collect = bufferedReader.lines().collect(Collectors.joining("\n"));
        final Pattern opcodePattern = Pattern.compile(
                "enum Opcode \\{.*?};",
                Pattern.MULTILINE | Pattern.DOTALL);
        final StringWriter opcodeContent = new StringWriter();
        final StringWriter gotoTableContent = new StringWriter();
        instructionRewriter.generateConfig(opcodeContent, gotoTableContent);
        String headerContent = opcodePattern
                .matcher(collect)
                .replaceAll(String.format("enum Opcode {\n%s};\n", opcodeContent.toString()));

        //??????opcode??????goto???
        final Pattern patternGotoTable = Pattern.compile(
                "_name\\[kNumPackedOpcodes\\] = \\{.*?};",
                Pattern.MULTILINE | Pattern.DOTALL);
        headerContent = patternGotoTable
                .matcher(headerContent)
                .replaceAll(String.format("_name[kNumPackedOpcodes] = {        \\\\\n%s};\n", gotoTableContent));


        try (FileWriter fileWriter = new FileWriter(source)) {
            fileWriter.write(headerContent);
        }

    }

    //??????????????????,???????????????????????????????????????,????????????apk??????????????????
    private static void writeApkVerifierFile(String packageName, File source, ApkVerifyCodeGenerator apkVerifyCodeGenerator) throws IOException {
        if (apkVerifyCodeGenerator == null) {
            return;
        }
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                new FileInputStream(source), StandardCharsets.UTF_8));

        final String lines = bufferedReader.lines().collect(Collectors.joining("\n"));
        String dataPlaceHolder = "#define publicKeyPlaceHolder";

        String content = lines.replaceAll(dataPlaceHolder, dataPlaceHolder + apkVerifyCodeGenerator.generate());
        content = content.replaceAll("(#define PACKAGE_NAME) .*\n", "$1 \"" + packageName + "\"\n");

        try (FileWriter fileWriter = new FileWriter(source)) {
            fileWriter.write(content);
        }
    }


    @Nonnull
    private static File dexWriteToFile(DexPool dexPool, int index, File dexOutDir) throws IOException {
        if (!dexOutDir.exists()) dexOutDir.mkdirs();

        File outDexFile;
        if (index == 0) {
            outDexFile = new File(dexOutDir, "classes.dex");
        } else {
            outDexFile = new File(dexOutDir, String.format("classes%d.dex", index + 1));
        }
        dexPool.writeTo(new FileDataStore(outDexFile));

        return outDexFile;
    }

    @Nonnull
    private static List<String> getMainDexClasses(GlobalDexConfig globalConfig, String applicationClass) throws IOException {
        final List<String> mainDexClassList = new ArrayList<>();
        String tmpType = classDotNameToType(applicationClass);
        mainDexClassList.add(tmpType);
        for (DexConfig config : globalConfig.getConfigs()) {
            DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getNativeDexFile())));
            final Set<? extends DexBackedClassDef> classes = dexFile.getClasses();
            ClassDef classDef;
            while (true) {
                classDef = getClassDefFromType(classes, tmpType);
                if (classDef == null) {
                    break;
                }
                if (classDotNameToType(ANDROID_APP_APPLICATION).equals(classDef.getSuperclass())) {
                    return mainDexClassList;
                }
                tmpType = classDef.getSuperclass();
                mainDexClassList.add(tmpType);
            }


        }
        return mainDexClassList;
    }

    private static ClassDef getClassDefFromType(Set<? extends ClassDef> classDefSet, String type) {
        for (ClassDef classDef : classDefSet) {
            if (classDef.getType().equals(type)) {
                return classDef;
            }
        }
        return null;
    }

    /**
     * ???????????????class???????????????????????????,??????dex??????????????????dex????????????
     * ??????????????????dexpool,????????????dexpool?????????????????????dexpool????????????,??????????????????
     *
     * @param globalConfig
     * @param mainClassSet
     * @param maxPoolSize
     * @param dexOutDir
     * @return
     * @throws IOException
     */
    @Nonnull
    private static List<File> injectInstructionAndWriteToFile(GlobalDexConfig globalConfig,
                                                              Set<String> mainClassSet,
                                                              int maxPoolSize,
                                                              File dexOutDir
    ) throws IOException {

        final List<File> dexFiles = new ArrayList<>();

        DexPool lastDexPool = new DexPool(Opcodes.getDefault());

        final List<DexConfig> configs = globalConfig.getConfigs();
        //?????????dex???main dex
        //???????????????dex?????????
        for (DexConfig config : configs) {

            DexBackedDexFile dexNativeFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getNativeDexFile())));

            for (ClassDef classDef : dexNativeFile.getClasses()) {
                if (mainClassSet.contains(classDef.getType())) {
                    Dex2c.internClass(config, lastDexPool, classDef);
                }
            }

        }

        for (int i = 0; i < configs.size(); i++) {
            DexConfig config = configs.get(i);
            final List<DexPool> retPools = Dex2c.injectCallRegisterNativeInsns(config, lastDexPool, mainClassSet, maxPoolSize);
            if (retPools.isEmpty()) {
                throw new RuntimeException("Dex inject instruction error");
            }
            if (retPools.size() > 1) {
                for (int k = 0; k < retPools.size() - 1; k++) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(retPools.get(k), size, dexOutDir);
                    dexFiles.add(file);
                }

                lastDexPool = retPools.get(retPools.size() - 1);
                if (i == configs.size() - 1) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(lastDexPool, size, dexOutDir);
                    dexFiles.add(file);
                }
            } else {
                final int size = dexFiles.size();
                final File file = dexWriteToFile(retPools.get(0), size, dexOutDir);
                dexFiles.add(file);


                lastDexPool = new DexPool(Opcodes.getDefault());
            }
        }

        return dexFiles;
    }

    /**
     * @param manifestBytes        ?????????androidManifest.xml????????????
     * @param applicationClassName application?????????class????????????
     * @param mainDex              ???dex??????
     * @param outDir               ???????????????androidManifest.xml?????????
     * @return ??????????????????xml??????
     * @throws IOException
     */
    @Nonnull
    private static File handleApplicationClass(byte[] manifestBytes,
                                               String applicationClassName,
                                               File mainDex,
                                               GlobalDexConfig globalConfig,
                                               File outDir) throws IOException {
        String newAppClassName;
        byte[] newManifest = manifestBytes;
        if (applicationClassName.equals(ANDROID_APP_APPLICATION)) {
            newAppClassName = "com.nmmedit.protect.LoadLibApp";

            //??????AndroidManifest.xml???application?????????class??????
            byte[] bytes = AxmlEdit.renameApplicationName(manifestBytes, newAppClassName);
            if (bytes != null) {
                newManifest = bytes;
            }
        } else {
            newAppClassName = applicationClassName;
        }

        //??????android.app.Application?????????

        DexFile mainDexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(mainDex)));
        DexPool newDex = new DexPool(Opcodes.getDefault());

        Dex2c.addApplicationClass(
                mainDexFile,
                newDex,
                classDotNameToType(newAppClassName));

        final ArrayList<String> nativeMethodNames = new ArrayList<>();
        for (DexConfig config : globalConfig.getConfigs()) {
            nativeMethodNames.add(config.getRegisterNativesMethodName());
        }


        newDex.internClass(
                new RegisterNativesUtilClassDef("L" + globalConfig.getConfigs().get(0).getRegisterNativesClassName() + ";",
                        nativeMethodNames));

        final File newFile = new File(mainDex.getParent(), ".temp.dex");
        newDex.writeTo(new FileDataStore(newFile));
        if (mainDex.delete()) {
            if (!newFile.renameTo(mainDex)) {
                throw new RemoteException("Can't handle main dex");
            }
        } else {
            throw new RemoteException("Can't handle main dex");
        }


        //?????????manifest??????
        File newManifestFile = new File(outDir, ANDROID_MANIFEST_XML);
        try (
                FileOutputStream output = new FileOutputStream(newManifestFile);
        ) {
            output.write(newManifest);
        }
        return newManifestFile;
    }

    //?????????????????????????????????,????????????crc32
    private static HashMap<ZipEntry, FileObj> zipExtractNeedCopy(ZipInputStream zipInputStream, File outDir) throws IOException {
        //?????????????????????????????????
        final Pattern regex = Pattern.compile(
                "classes(\\d)*\\.dex" +
                        "|META-INF/.*\\.(RSA|DSA|EC|SF|MF)" +
                        "|AndroidManifest\\.xml");
        final HashMap<ZipEntry, FileObj> entryNameFileMap = new HashMap<>();
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            if (entry.isDirectory()
                    || "".equals(entry.getName())) {
                continue;
            }
            if (regex.matcher(entry.getName()).matches()) {
                continue;
            }

            final File file = new File(outDir, entry.getName());
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (final FileOutputStream out = new FileOutputStream(file)) {
                final long crc = copyStreamCalcCrc32(zipInputStream, out);
                entryNameFileMap.put(entry, new FileObj(file, crc));
            }
        }

        return entryNameFileMap;
    }

    private static void zipCopy(ZipInputStream zipInputStream, File tempDir, ZipOutputStream zipOutputStream) throws IOException {

        //??????????????????zip????????????zip?????????
        final HashMap<ZipEntry, FileObj> entries = zipExtractNeedCopy(zipInputStream, tempDir);
        for (Map.Entry<ZipEntry, FileObj> entryFile : entries.entrySet()) {
            final ZipEntry entry = entryFile.getKey();
            final FileObj fileObj = entryFile.getValue();


            final ZipEntry zipEntry = new ZipEntry(entry.getName());
            if (entry.getMethod() == ZipEntry.STORED) {//????????????????????????
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setCrc(fileObj.crc32);
                zipEntry.setSize(fileObj.file.length());
                zipEntry.setCompressedSize(fileObj.file.length());
            }

            zipOutputStream.putNextEntry(zipEntry);

            try (final FileInputStream fileIn = new FileInputStream(fileObj.file)) {
                copyStream(fileIn, zipOutputStream);
            }
            zipOutputStream.closeEntry();
        }
    }

    //??????????????????
    private static void deleteFile(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            final File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteFile(child);
                }
            }
        }
        file.delete();
    }

    private static String classDotNameToType(String classDotName) {
        return "L" + classDotName.replace('.', '/') + ";";
    }

    private static long copyStreamCalcCrc32(InputStream in, OutputStream out) throws IOException {
        final CRC32 crc32 = new CRC32();
        byte[] buf = new byte[4 * 1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
            crc32.update(buf, 0, len);
        }
        return crc32.getValue();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[4 * 1024];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
    }

    private static class FileObj {
        private final File file;
        private final long crc32;

        FileObj(File file, long crc32) {
            this.file = file;
            this.crc32 = crc32;
        }
    }

    public static class Builder {
        private final ApkFolders apkFolders;
        private InstructionRewriter instructionRewriter;
        private ApkVerifyCodeGenerator apkVerifyCodeGenerator;
        private ClassAndMethodFilter filter;


        public Builder(ApkFolders apkFolders) {
            this.apkFolders = apkFolders;
        }

        public Builder setInstructionRewriter(InstructionRewriter instructionRewriter) {
            this.instructionRewriter = instructionRewriter;
            return this;
        }

        public Builder setApkVerifyCodeGenerator(ApkVerifyCodeGenerator apkVerifyCodeGenerator) {
            this.apkVerifyCodeGenerator = apkVerifyCodeGenerator;
            return this;
        }

        public Builder setFilter(ClassAndMethodFilter filter) {
            this.filter = filter;
            return this;
        }

        public ApkProtect build() {
            if (instructionRewriter == null) {
                throw new RuntimeException("instructionRewriter == null");
            }
            return new ApkProtect(apkFolders, instructionRewriter, apkVerifyCodeGenerator, filter);
        }
    }
}
