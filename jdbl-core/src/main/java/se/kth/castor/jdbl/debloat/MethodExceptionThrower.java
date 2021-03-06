package se.kth.castor.jdbl.debloat;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class MethodExceptionThrower extends MethodVisitor
{
    private final MethodVisitor target;

    public MethodExceptionThrower(MethodVisitor methodVisitor)
    {
        super(Opcodes.ASM8, null);
        this.target = methodVisitor;
    }

    @Override
    public void visitCode()
    {
        target.visitCode();
        target.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
        target.visitInsn(Opcodes.DUP);
        target.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false);
        target.visitInsn(Opcodes.ATHROW);
        target.visitMaxs(2, 0);
        target.visitEnd();
    }
}
