import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds a "mockable" android.jar the same way the Android Gradle Plugin does for unit
 * tests: the body of every method is replaced by a default return value, so that the tests
 * can run on a plain JVM without a device. Native methods become normal methods and the
 * constructors call the no argument constructor of their super class.
 */
public class MockableJar {

    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);

        Map<String, ClassInfo> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(input.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                ClassReader reader = new ClassReader(zip.getInputStream(entry));
                ClassInfo info = new ClassInfo();
                info.superName = reader.getSuperName();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        if (name.equals("<init>") && descriptor.equals("()V")) info.hasNoArgConstructor = true;
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
                classes.put(reader.getClassName(), info);
            }
        }

        int rewritten = 0;
        try (ZipFile zip = new ZipFile(input.toFile());
             OutputStream out = Files.newOutputStream(output)) {
            ZipOutputStream zos = new ZipOutputStream(out);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] data;
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                    reader.accept(new RewritingClassVisitor(writer, reader.getClassName(), classes), 0);
                    data = writer.toByteArray();
                    rewritten++;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                zos.putNextEntry(copy);
                zos.write(data);
                zos.closeEntry();
            }
            zos.finish();
        }
        System.out.println("mockable jar written, " + rewritten + " classes rewritten");
    }

    /** What is known about a class of the android.jar. */
    private static final class ClassInfo {
        String superName;
        boolean hasNoArgConstructor;
    }

    /** Rewrites the bodies of all methods of a class. */
    private static class RewritingClassVisitor extends ClassVisitor {

        private final String className;
        private final Map<String, ClassInfo> classes;

        RewritingClassVisitor(ClassVisitor cv, String className, Map<String, ClassInfo> classes) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.classes = classes;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            // A native method may not have a body, so the flag is removed.
            int newAccess = access & ~Opcodes.ACC_NATIVE;
            MethodVisitor mv = super.visitMethod(newAccess, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            if ((access & Opcodes.ACC_ABSTRACT) != 0) return mv;
            boolean isConstructor = name.equals("<init>");
            mv.visitCode();
            if (isConstructor) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName(), "<init>", "()V", false);
            }
            Type returnType = Type.getReturnType(descriptor);
            switch (returnType.getSort()) {
                case Type.VOID:
                    mv.visitInsn(Opcodes.RETURN);
                    break;
                case Type.LONG:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.DOUBLE:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    break;
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    break;
                default:
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return new BodySwallower(mv);
        }

        /** The super class whose no argument constructor is called by a constructor. */
        private String superName() {
            ClassInfo info = classes.get(className);
            if (info == null) return "java/lang/Object";
            String name = info.superName;
            if (name == null) return "java/lang/Object";
            ClassInfo parent = classes.get(name);
            if (parent == null || !parent.hasNoArgConstructor) return "java/lang/Object";
            return name;
        }
    }

    /** Swallows the original body of a method, only the exception table is dropped. */
    private static class BodySwallower extends MethodVisitor {
        BodySwallower(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override public void visitCode() { }
        @Override public void visitInsn(int opcode) { }
        @Override public void visitIntInsn(int opcode, int operand) { }
        @Override public void visitVarInsn(int opcode, int varIndex) { }
        @Override public void visitTypeInsn(int opcode, String type) { }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) { }
        @Override public void visitJumpInsn(int opcode, Label label) { }
        @Override public void visitLdcInsn(Object value) { }
        @Override public void visitIincInsn(int varIndex, int increment) { }
        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { }
        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { }
        @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { }
        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { }
        @Override public void visitLineNumber(int line, Label start) { }
        @Override public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) { }
        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) { }
        @Override public void visitEnd() { }
    }
}
