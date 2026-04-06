package comp0012.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;


public class ConstantFolder
{
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	public ConstantFolder(String classFilePath)
	{
		try{
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch(IOException e){
			e.printStackTrace();
		}
	}
	
	public void optimize()
	{
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = cgen.getConstantPool();

		// Implement your optimization here
		Method[] methods = cgen.getMethods();
		for (Method method: methods) {
			if (method.isAbstract() || method.isNative()) {
				continue;
			}

			MethodGen mg = new MethodGen(method, cgen.getClassName(), cpgen);
			InstructionList il = mg.getInstructionList();

			if (il == null) {
				continue;
			}

			boolean changed = foldConstants(il, cpgen);

			if (changed) {
				il.setPositions(true);
				mg.setMaxStack();
				mg.setMaxLocals();
				cgen.replaceMethod(method, mg.getMethod());
			}
		}

		this.optimized = cgen.getJavaClass();
	}



	private boolean foldConstants(InstructionList il, ConstantPoolGen cpgen) {
		boolean changed = false;
		InstructionHandle current = il.getStart();

		while (current != null) {
			InstructionHandle h1 = current;
			InstructionHandle h2 = h1.getNext();
			if (h2 == null) break;

			InstructionHandle h3 = h2.getNext();
			if (h3 == null) break;

			Number v1 = getNumericValue(h1, cpgen);
			Number v2 = getNumericValue(h2, cpgen);

			if (v1 != null && v2 != null) {
				Number result = null;
				result = foldDispatcher(v1, v2, h3);

				if (result != null) {
					try {
						InstructionHandle newHandle = handleResult(result, il, h1, cpgen);

						il.delete(h1, h3);
						changed = true;
						current = newHandle;
						continue;
					} catch (TargetLostException e) {
						e.printStackTrace();
					}
				}
			}
			current = current.getNext();
		}

		return changed;
	}
	
	private InstructionHandle handleResult(Number result, InstructionList il, InstructionHandle h1, ConstantPoolGen cpgen) {
		InstructionHandle newHandle = null;	
		if (result instanceof Integer){
			newHandle = il.insert(h1, new LDC(cpgen.addInteger((Integer) result)));
		} else if (result instanceof Float) {
			newHandle = il.insert(h1, new LDC(cpgen.addFloat((Float) result)));
		} else if (result instanceof Long) {
			newHandle = il.insert(h1, new LDC2_W(cpgen.addLong((Long) result)));
		} else if (result instanceof Double) {
			newHandle = il.insert(h1, new LDC2_W(cpgen.addDouble((Double) result)));
		}
		return newHandle;
	}

	private Number getNumericValue(InstructionHandle handle, ConstantPoolGen cpgen) {
		Instruction instruction = handle.getInstruction();
		if (instruction instanceof ConstantPushInstruction) {
			Number value = ((ConstantPushInstruction) instruction).getValue();
			if (value instanceof Integer ||
				value instanceof  Float  ||
				value instanceof  Long   ||
				value instanceof  Double) {
				return value;
			}
		}

		if (instruction instanceof LDC) {
			Object value = ((LDC) instruction).getValue(cpgen);
			if (value instanceof Integer || value instanceof  Float) {
				return (Number) value;
			}
		}

		if (instruction instanceof LDC2_W) {
			Object value = ((LDC2_W) instruction).getValue(cpgen);
			if (value instanceof Long || value instanceof Double) {
				return (Number) value;
			}
		}

		return null;
	}

	private Number foldDispatcher(Number v1, Number v2, InstructionHandle h3) {
		if (v1 instanceof Integer && v2 instanceof Integer) {
			return foldIntOperation(v1.intValue(), v2.intValue(), h3);
		}

		if (v1 instanceof Float && v2 instanceof Float) {
			return foldFloatOperation(v1.floatValue(), v2.floatValue(), h3);
		}

		if (v1 instanceof Long && v2 instanceof Long) {
			return foldLongOperation(v1.longValue(), v2.longValue(), h3);
		}

		if (v1 instanceof Double && v2 instanceof Double) {
			return foldDoubleOperation(v1.doubleValue(), v2.doubleValue(), h3);
		}

		return null;
	}

	private Float foldFloatOperation(Float a, Float b, InstructionHandle handle) {
		Instruction op = handle.getInstruction();
		if (op instanceof FADD) return a + b;
		if (op instanceof FSUB) return a - b;
		if (op instanceof FMUL) return a * b;
		if (op instanceof FDIV) return a / b;
		return null;
	}

	private Long foldLongOperation(Long a, Long b, InstructionHandle handle) {
		Instruction op = handle.getInstruction();
		if (op instanceof LADD) return a + b;
		if (op instanceof LSUB) return a - b;
		if (op instanceof LMUL) return a * b;
		if (op instanceof LDIV) {
			if (b != 0) return a / b;
		}
		return null;
	}

	private Double foldDoubleOperation(Double a, Double b, InstructionHandle handle) {
		Instruction op = handle.getInstruction();
		if (op instanceof DADD) return a + b;
		if (op instanceof DSUB) return a - b;
		if (op instanceof DMUL) return a * b;
		if (op instanceof DDIV) return a / b;
		return null;
	}


	private Integer foldIntOperation(Integer a, Integer b, InstructionHandle handle){
		Instruction op = handle.getInstruction();
		if (op instanceof IADD) return a + b;
		if (op instanceof ISUB) return a - b;
		if (op instanceof IMUL) return a * b;
		if (op instanceof IDIV) {
			if (b != 0) return a / b;
		}
		return null;
	}

	
	public void write(String optimisedFilePath) {
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}
}