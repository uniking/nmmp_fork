package com.nmmedit.apkprotect.dex2c;

import com.google.common.collect.Maps;
import com.nmmedit.apkprotect.dex2c.converter.JniCodeGenerator;
import com.nmmedit.apkprotect.dex2c.converter.MyMethodUtil;
import com.nmmedit.apkprotect.dex2c.converter.instructionrewriter.InstructionRewriter;
import com.nmmedit.apkprotect.dex2c.converter.structs.ClassMethodToNative;
import com.nmmedit.apkprotect.dex2c.converter.structs.ClassToSymDex;
import com.nmmedit.apkprotect.dex2c.converter.structs.LoadLibClassDef;
import com.nmmedit.apkprotect.dex2c.converter.structs.RegisterNativesCallerClassDef;
import com.nmmedit.apkprotect.dex2c.converter.testbuild.ClassMethodImplCollection;
import com.nmmedit.apkprotect.dex2c.filters.ClassAndMethodFilter;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.*;

public class Dex2c {

    public static final String LANDROID_APP_APPLICATION = "Landroid/app/Application;";

    private Dex2c() {
    }


    public static ClassAndMethodFilter testFilter = new ClassAndMethodFilter() {

        @Override
        public boolean acceptClass(ClassDef classDef) {
            return classDef.getType().startsWith("Ltests/");
        }

        @Override
        public boolean acceptMethod(Method method) {
            return !MyMethodUtil.isConstructorOrAbstract(method) && !AccessFlags.BRIDGE.isSet(method.getAccessFlags());
        }
    };

    public static void parseDex(InputStream dexStream) throws IOException {
        DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(Opcodes.forApi(21), dexStream);
        DexPool dexPool = new DexPool(Opcodes.forApi(21));
        DexPool dexPoolMethodIml = new DexPool(Opcodes.forApi(21));


        StringBuilder sb = new StringBuilder();

        for (final ClassDef classDef : dexFile.getClasses()) {
            if (testFilter.acceptClass(classDef)) {
                dexPool.internClass(new ClassMethodToNative(classDef, testFilter));
                dexPoolMethodIml.internClass(new ClassMethodImplCollection(classDef, sb));
            } else {
                dexPool.internClass(classDef);
            }
        }

        dexPool.writeTo(new FileDataStore(new File("/home/mao/nmmp/classes2.dex")));
        dexPoolMethodIml.writeTo(new FileDataStore(new File("/home/mao/nmmp/sym.dat")));

    }

    public static void dex2cTest(File dex, File outDir) throws IOException {
        DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(Opcodes.forApi(21),
                new BufferedInputStream(new FileInputStream(dex)));

        //???????????????????????????,??????class?????????
        DexPool nativeMethodDexPool = new DexPool(Opcodes.forApi(21));

        //
        DexPool dexPoolMethodIml = new DexPool(Opcodes.forApi(21));

        DexBuilder dexBuilder = new DexBuilder(Opcodes.forApi(21));

        StringBuilder sb = new StringBuilder();
        for (final ClassDef classDef : dexFile.getClasses()) {
            if (testFilter.acceptClass(classDef)) {
                nativeMethodDexPool.internClass(new ClassMethodToNative(classDef, testFilter));

                for (Field field : classDef.getFields()) {
                    //?????????field??????,????????????c??????
                    dexBuilder.internField(field.getDefiningClass(), field.getName(), field.getType(),
                            field.getAccessFlags(), null, Collections.emptySet(), Collections.emptySet());
                }


                dexPoolMethodIml.internClass(new ClassMethodImplCollection(classDef, sb));
            } else {
                nativeMethodDexPool.internClass(classDef);
            }
        }


        nativeMethodDexPool.writeTo(new FileDataStore(new File(outDir, dex.getName())));
        dexPoolMethodIml.writeTo(new FileDataStore(new File(outDir, "sym.dat")));

        FileWriter writer = new FileWriter(new File(outDir, "dex2c.cpp"));
        String s = sb.toString();
        writer.write(s, 0, s.length());
        writer.close();

    }

    /**
     * ????????????dex??????
     *
     * @param dexFiles dex????????????
     * @param outDir   ??????c?????????????????????
     * @return ??????????????????
     * @throws IOException
     */
    public static GlobalDexConfig handleDexes(List<File> dexFiles,
                                              ClassAndMethodFilter filter,
                                              InstructionRewriter instructionRewriter,
                                              File outDir) throws IOException {
        if (!outDir.exists()) outDir.mkdirs();
        final GlobalDexConfig globalConfig = new GlobalDexConfig(outDir);
        for (File file : dexFiles) {
            final DexConfig config = handleDex(file, filter, instructionRewriter, outDir);
            globalConfig.addDexConfig(config);
        }
        globalConfig.generateJniInitCode();
        return globalConfig;
    }

    /**
     * ????????????dex??????
     *
     * @param dexFile dex??????
     * @param outDir  ????????????
     * @return ????????????
     * @throws IOException
     */
    public static DexConfig handleDex(File dexFile,
                                      ClassAndMethodFilter filter,
                                      InstructionRewriter instructionRewriter,
                                      File outDir) throws IOException {
        return handleDex(new BufferedInputStream(new FileInputStream(dexFile)),
                dexFile.getName(),
                filter,
                instructionRewriter,
                outDir);
    }

    /**
     * ????????????dex???
     *
     * @param dex         dex???
     * @param dexFileName dex???,??????????????????
     * @param outDir      ????????????
     * @return ????????????
     * @throws IOException
     */
    public static DexConfig handleDex(InputStream dex,
                                      String dexFileName,
                                      ClassAndMethodFilter filter,
                                      InstructionRewriter instructionRewriter,
                                      File outDir) throws IOException {
        DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                dex);

        //???????????????????????????,????????????????????????dex
        DexPool nativeMethodDexPool = new DexPool(Opcodes.getDefault());

        DexPool symDexPool = new DexPool(Opcodes.getDefault());


        for (final ClassDef classDef : dexFile.getClasses()) {
            if (filter.acceptClass(classDef)) {
                //??????????????????????????????native
                nativeMethodDexPool.internClass(new ClassMethodToNative(classDef, filter));
                //??????????????????????????????????????????dex
                symDexPool.internClass(new ClassToSymDex(classDef, filter));
            } else {
                //??????????????????class,????????????
                nativeMethodDexPool.internClass(classDef);
            }
        }
        DexConfig config = new DexConfig(outDir, dexFileName);


        //?????????????????????dex
        nativeMethodDexPool.writeTo(new FileDataStore(config.getNativeDexFile()));
        //????????????dex
        symDexPool.writeTo(new FileDataStore(config.getSymbolDexFile()));


        final DexBackedDexFile symDexFile = DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(config.getSymbolDexFile())));

        //????????????dex??????c??????
        try (FileWriter nativeCodeWriter = new FileWriter(config.getNativeFunctionsFile());
             FileWriter resolverWriter = new FileWriter(config.getResolverFile());
        ) {
            JniCodeGenerator codeGenerator = new JniCodeGenerator(symDexFile, instructionRewriter);
            codeGenerator.generate(
                    config,
                    resolverWriter,
                    nativeCodeWriter
            );
            config.setResult(codeGenerator);
        }


        //??????????????????????????????????????????extern
        /*
        DexConfig.HeaderFileAndSetupFuncName func = config.getHeaderFileAndSetupFunc();
        // ?????????????????????????????????,???????????????
        try (FileWriter headerWriter = new FileWriter(func.headerFile)) {
            headerWriter.write(String.format(
                    "#include <jni.h>\n" +
                            "\n" +
                            "#ifdef __cplusplus\n" +
                            "extern \"C\" {\n" +
                            "#endif\n" +
                            "\n" +
                            "\n" +
                            "\n" +
                            "void %s(JNIEnv *env);\n" +
                            "\n" +
                            "\n" +
                            "#ifdef __cplusplus\n" +
                            "}\n" +
                            "#endif\n\n", func.setupFunctionName));
        }
        */
        return config;
    }

    //???????????????class???static{}??????????????????????????????????????????,???????????????static{}????????????<clinit>??????
    public static List<DexPool> injectCallRegisterNativeInsns(DexConfig config,
                                                              DexPool lastDexPool,
                                                              Set<String> mainClassSet,
                                                              int maxPoolSize) throws IOException {

        DexBackedDexFile dexNativeFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(config.getNativeDexFile())));

        List<DexPool> dexPools = new ArrayList<>();
        dexPools.add(lastDexPool);


        for (ClassDef classDef : dexNativeFile.getClasses()) {
            if (mainClassSet.contains(classDef.getType())) {//??????????????????class,???????????????
                continue;
            }
            internClass(config, lastDexPool, classDef);

            if (lastDexPool.hasOverflowed(maxPoolSize)) {
                lastDexPool = new DexPool(Opcodes.getDefault());
                dexPools.add(lastDexPool);
            }
        }
        return dexPools;
    }

    public static void internClass(DexConfig config, DexPool dexPool, ClassDef classDef) {
        final Set<String> classes = config.getNativeClasses();
        final String type = classDef.getType();
        final String className = type.substring(1, type.length() - 1);
        if (classes.contains(className)) {
            final RegisterNativesCallerClassDef nativeClassDef = new RegisterNativesCallerClassDef(
                    classDef,
                    config.getOffsetFromClassName(className),
                    "L" + config.getRegisterNativesClassName() + ";",
                    config.getRegisterNativesMethodName());
            dexPool.internClass(nativeClassDef);
        } else {
            dexPool.internClass(classDef);
        }
    }

    /**
     * ????????????application???????????????????????????????????????????????????so???
     *
     * @param dexFile
     * @param newType
     */
    public static void addApplicationClass(@Nonnull DexFile dexFile,
                                           @Nonnull DexPool newDex,
                                           @Nonnull final String newType) {

        ClassDef appDirectSubClassDef = null;
        List<ClassDef> parents = getClassDefParents(dexFile.getClasses(), newType, LANDROID_APP_APPLICATION);
        if (!parents.isEmpty()) {
            appDirectSubClassDef = parents.get(parents.size() - 1);
        }

        for (ClassDef classDef : dexFile.getClasses()) {
            if (classDef.equals(appDirectSubClassDef)) {
                continue;
            }
            newDex.internClass(classDef);
        }

        final LoadLibClassDef libClassDef = new LoadLibClassDef(appDirectSubClassDef,
                appDirectSubClassDef != null ? appDirectSubClassDef.getType() : newType, "nmmp");
        newDex.internClass(libClassDef);

    }

    //???????????????????????????
    @Nonnull
    private static List<ClassDef> getClassDefParents(@Nonnull final Set<? extends ClassDef> classes, @Nonnull String type, @Nonnull String rootType) {
        String tmpType = type;
        final ArrayList<ClassDef> parents = new ArrayList<>();


        //??????????????????classDef????????????
        final Map<String, ClassDef> classDefMap = Maps.newHashMap();
        for (ClassDef classDef : classes) {
            classDefMap.put(classDef.getType(), classDef);
        }

        while (true) {//?????????????????????????????????rootType???????????????classDef
            final ClassDef classDef = classDefMap.get(tmpType);
            if (classDef == null) {
                break;
            }
            //?????????rootType???????????????
            if (rootType.equals(classDef.getSuperclass())) {
                parents.add(classDef);
                break;
            }
            tmpType = classDef.getSuperclass();
            parents.add(classDef);
        }

        return parents;
    }


}
