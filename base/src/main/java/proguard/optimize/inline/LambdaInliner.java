package proguard.optimize.inline;

//import com.guardsquare.gui.Gui;
import proguard.optimize.inline.lambda_locator.LambdaLocator;
import proguard.AppView;
import proguard.classfile.AccessConstants;
import proguard.classfile.Method;
import proguard.classfile.ProgramClass;
import proguard.classfile.editor.CodeAttributeEditor;
import proguard.classfile.editor.ConstantPoolEditor;
import proguard.classfile.instruction.ConstantInstruction;
import proguard.classfile.instruction.Instruction;
import proguard.classfile.util.InitializationUtil;
import proguard.optimize.inline.lambda_locator.Lambda;
import proguard.pass.Pass;

import java.util.HashSet;
import java.util.Set;

public class LambdaInliner implements Pass {
    private final String classNameFilter;
    private boolean inlinedAllUsages;
    public LambdaInliner(String classNameFilter) {
        this.classNameFilter = classNameFilter;
        this.inlinedAllUsages = true;
    }

    public LambdaInliner() {
        this("");
    }

    @Override
    public void execute(AppView appView) {
        LambdaLocator lambdaLocator = new LambdaLocator(appView.programClassPool, classNameFilter);

        for (Lambda lambda : lambdaLocator.getStaticLambdas()) {
            Set<InstructionAtOffset> remainder = new HashSet<>();
            inlinedAllUsages = true;
            InitializationUtil.initialize(appView.programClassPool, appView.libraryClassPool);
            lambda.codeAttribute().accept(lambda.clazz(), lambda.method(), new LambdaUsageFinder(lambda, lambdaLocator.getStaticLambdaMap(), appView, (foundLambda, consumingClass, consumingMethod, consumingCallOffset, consumingCallClazz, consumingCallmethod, consumingCallCodeAttribute, sourceTrace, possibleLambdas) -> {
                // Try inlining the lambda in consumingMethod
                BaseLambdaInliner baseLambdaInliner = new BaseLambdaInliner(appView, consumingClass, consumingMethod, lambda);
                Method inlinedLambamethod = baseLambdaInliner.inline();

                // We didn't inline anything so no need to change any call instructions.
                if (inlinedLambamethod == null) {
                    inlinedAllUsages = false;
                    return false;
                }

                if (possibleLambdas.size() > 1) {
                    // This lambda is part of a collection of lambdas that might potentially be used, but we do not know which one is actually used. Because of that we cannot inline it.
                    inlinedAllUsages = false;
                    return false;
                }
                InitializationUtil.initialize(appView.programClassPool, appView.libraryClassPool);

                CodeAttributeEditor codeAttributeEditor = new CodeAttributeEditor();
                codeAttributeEditor.reset(consumingCallCodeAttribute.u4codeLength);

                /*
                 * Remove usages that bring the variable on the stack so all the way up until a load
                 * The store operations before the load might also be used by other functions calls that consume the
                 * lambda, that's why we need to keep them.
                 */
                for (int i = 0; i < sourceTrace.size(); i++) {
                    InstructionAtOffset instrAtOffset = sourceTrace.get(i);
                    codeAttributeEditor.deleteInstruction(instrAtOffset.offset());
                    if (instrAtOffset.instruction().canonicalOpcode() == Instruction.OP_ALOAD || instrAtOffset.instruction().canonicalOpcode() == Instruction.OP_INVOKESTATIC) {
                        remainder.addAll(sourceTrace.subList(i + 1, sourceTrace.size()));
                        break;
                    }
                }

                // Replace invokestatic call to a call with the new function
                ConstantPoolEditor constantPoolEditor = new ConstantPoolEditor((ProgramClass) consumingCallClazz);
                int methodWithoutLambdaParameterIndex = constantPoolEditor.addMethodrefConstant(consumingClass, inlinedLambamethod);

                // Replacing at consumingCallOffset
                if ((consumingMethod.getAccessFlags() & AccessConstants.STATIC) == 0) {
                    codeAttributeEditor.replaceInstruction(consumingCallOffset, new ConstantInstruction(Instruction.OP_INVOKEVIRTUAL, methodWithoutLambdaParameterIndex));
                } else {
                    codeAttributeEditor.replaceInstruction(consumingCallOffset, new ConstantInstruction(Instruction.OP_INVOKESTATIC, methodWithoutLambdaParameterIndex));
                }

                codeAttributeEditor.visitCodeAttribute(consumingCallClazz, consumingCallmethod, consumingCallCodeAttribute);
                return true;
            }));

            /*
             * Only remove the code needed to obtain a reference to the lambda if we were able to inline everything, if we
             * could not do that then we still need the lambda and cannot remove it.
             */
            if (inlinedAllUsages) {
                // Removing lambda obtaining code because all usages could be inlined!
                CodeAttributeEditor codeAttributeEditor = new CodeAttributeEditor();
                codeAttributeEditor.reset(lambda.codeAttribute().u4codeLength);
                for (InstructionAtOffset instrAtOffset : remainder) {
                    codeAttributeEditor.deleteInstruction(instrAtOffset.offset());
                }
                codeAttributeEditor.visitCodeAttribute(lambda.clazz(), lambda.method(), lambda.codeAttribute());
            }
        }
    }
}
