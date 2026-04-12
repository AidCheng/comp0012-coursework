package comp0012.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import java.util.ArrayList;
import java.util.List;
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

			boolean methodChanged = false;
			boolean loopChanged = true;

			while (loopChanged) {
				boolean foldedVars = foldConstantVariables(il, cpgen);
				boolean foldedMath = foldConstants(il, cpgen);
				boolean foldedDynamic = foldDynamicVariables(il, cpgen);
				boolean eliminatedStores = eliminateDeadCode(il, cpgen);
				loopChanged = foldedVars || foldedDynamic || foldedMath || eliminatedStores;
				if (loopChanged) {
					methodChanged = true;
				}
			}

			if (methodChanged) {
				il.setPositions(true);
				mg.setMaxStack();
				mg.setMaxLocals();
				mg.removeCodeAttributes();
				cgen.setMajor(50);
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
					InstructionHandle newHandle = handleResult(result, il, h1, cpgen);
					try {
						il.delete(h1, h3);
					} catch (TargetLostException e) {
						for (InstructionHandle target : e.getTargets()) {
							for (InstructionTargeter targeter : target.getTargeters()) {
								targeter.updateTarget(target, newHandle);
							}
						}
					}
					changed = true;
					current = newHandle;
					continue;
				}
			}
			current = current.getNext();
		}

		return changed;
	}

	// Stage 2: Constant variables
	private boolean foldConstantVariables(InstructionList il, ConstantPoolGen cpgen) {
		boolean changed = false;
		Map<Integer, Integer> storeCounts = new HashMap<>();
		
		// Count assignments for each variable
		for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext()) {
			Instruction inst = ih.getInstruction();
			if (inst instanceof StoreInstruction) {
				int index = ((StoreInstruction) inst).getIndex();
				storeCounts.put(index, storeCounts.getOrDefault(index, 0) + 1);
			}
			// Bug fix, need to count IINC as well since it modifies variables
			else if (inst instanceof IINC) {
				int index = ((IINC) inst).getIndex();
				storeCounts.put(index, storeCounts.getOrDefault(index, 0) + 1);
			}
		}
		// Find variables assigned only once to a constant.
		Map<Integer, Number> constantValues = new HashMap<>();
		for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext()) {
			Instruction inst = ih.getInstruction();
			if (inst instanceof StoreInstruction) {
				int index = ((StoreInstruction) inst).getIndex();

				if (storeCounts.get(index) == 1) {
					InstructionHandle prev = ih.getPrev();
					if (prev != null ) {
						Number val = getNumericValue(prev, cpgen);
						if (val != null) {
							constantValues.put(index, val);
						}
					}
				}
			}
		}
		// Replace loads of constant variables with actual values.
		InstructionHandle ih = il.getStart();
		while (ih != null) {
			Instruction inst = ih.getInstruction();
			InstructionHandle nextIh = ih.getNext();

			if (inst instanceof LoadInstruction) {
				int index = ((LoadInstruction) inst).getIndex();
				if (constantValues.containsKey(index)) {
					Number val = constantValues.get(index);
					InstructionHandle newIh = handleResult(val, il, ih, cpgen);

					try {
						il.delete(ih);
					} catch (TargetLostException e) {
						for (InstructionHandle target : e.getTargets()) {
							for (InstructionTargeter targeter : target.getTargeters()) {
								targeter.updateTarget(target, newIh);
							}
						}
					}
					changed = true;
				}
			}
			ih = nextIh;
		}

		return changed;
	}

	// Stage 3: Dynamic variables - propagate constant values between reassignments
	private boolean foldDynamicVariables(InstructionList il, ConstantPoolGen cpgen) {
		boolean changed = false;

		Map<Integer, List<InstructionHandle>> storesByVar = getConstantStoresByVar(il, cpgen);

		for (Map.Entry<Integer, List<InstructionHandle>> entry : storesByVar.entrySet()) {
			int varIndex = entry.getKey();
			List<InstructionHandle> stores = entry.getValue();

			InstructionHandle ih = il.getStart();
			while (ih != null) {
				InstructionHandle next = ih.getNext();
				Instruction inst = ih.getInstruction();

				if (inst instanceof LoadInstruction && ((LoadInstruction) inst).getIndex() == varIndex) {
					InstructionHandle activeStore = getMostRecentStoreBefore(ih, stores, il);
					if (activeStore != null) {
						Number val = getNumericValue(activeStore.getPrev(), cpgen);
						if (val != null) {
							InstructionHandle newIh = handleResult(val, il, ih, cpgen);
							try {
								il.delete(ih);
							} catch (TargetLostException e) {
								for (InstructionHandle target : e.getTargets()) {
									for (InstructionTargeter targeter : target.getTargeters()) {
										targeter.updateTarget(target, newIh);
									}
								}
							}
							changed = true;
						}
					}
				}
				ih = next;
			}
		}

		return changed;
	}

	// map of varindex store
	private Map<Integer, List<InstructionHandle>> getConstantStoresByVar(InstructionList il, ConstantPoolGen cpgen) {
		Map<Integer, Integer> totalStoreCounts = new HashMap<>();
		for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext()) {
			Instruction inst = ih.getInstruction();
			if (inst instanceof StoreInstruction) {
				int index = ((StoreInstruction) inst).getIndex();
				totalStoreCounts.put(index, totalStoreCounts.getOrDefault(index, 0) + 1);
			} else if (inst instanceof IINC) {
				int index = ((IINC) inst).getIndex();
				totalStoreCounts.put(index, totalStoreCounts.getOrDefault(index, 0) + 1);
			}
		}

		Map<Integer, List<InstructionHandle>> constantStores = new HashMap<>();
		for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext()) {
			Instruction inst = ih.getInstruction();
			if (inst instanceof StoreInstruction) {
				int index = ((StoreInstruction) inst).getIndex();
				if (totalStoreCounts.getOrDefault(index, 0) <= 1) continue;
				InstructionHandle prev = ih.getPrev();
				if (prev != null && getNumericValue(prev, cpgen) != null) {
					constantStores.computeIfAbsent(index, k -> new ArrayList<>()).add(ih);
				}
			}
		}

		Map<Integer, List<InstructionHandle>> result = new HashMap<>();
		for (Map.Entry<Integer, List<InstructionHandle>> entry : constantStores.entrySet()) {
			int index = entry.getKey();
			if (entry.getValue().size() == totalStoreCounts.get(index)) {
				result.put(index, entry.getValue());
			}
		}

		return result;
	}

	private InstructionHandle getMostRecentStoreBefore(InstructionHandle load, List<InstructionHandle> stores, InstructionList il) {
		InstructionHandle mostRecent = null;
		for (InstructionHandle ih = il.getStart(); ih != null && ih != load; ih = ih.getNext()) {
			if (stores.contains(ih)) {
				mostRecent = ih;
			}
		}
		return mostRecent;
	}
	
	private boolean eliminateDeadCode(InstructionList il, ConstantPoolGen cpgen) {
		boolean changed = false;

		InstructionHandle ih = il.getStart();
		while (ih != null) {
			InstructionHandle next = ih.getNext();
			Instruction inst = ih.getInstruction();

			if (!(inst instanceof StoreInstruction)) {
				ih = next;
				continue;
			}

			int index = ((StoreInstruction) inst).getIndex();

			boolean loadFound = false;
			for (InstructionHandle scan = ih.getNext(); scan != null; scan = scan.getNext()) {
				Instruction scanInst = scan.getInstruction();

				// dead variable as stored to same variable before any load
				if (scanInst instanceof StoreInstruction && ((StoreInstruction) scanInst).getIndex() == index) {
					break;
				}

				// live variable as load found
				if (scanInst instanceof LoadInstruction && ((LoadInstruction) scanInst).getIndex() == index) {
					loadFound = true;
					break;
				}
			}

			if (!loadFound) {
				InstructionHandle prev = ih.getPrev();
				boolean storeIsTarget = ih.hasTargeters();
				boolean prevIsConstant = prev != null && getNumericValue(prev, cpgen) != null;

				// skip if the store itself is a branch target as nothing safe to redirect to
				if (storeIsTarget) {
					ih = next;
					continue;
				}

				// remove both store and constant push if the store is preceded by a constant push
				if (prevIsConstant) {
					InstructionHandle afterStore = ih.getNext();
					if (afterStore == null) {
						ih = next;
						continue;
					}
					if (prev.hasTargeters()) {
						il.redirectBranches(prev, afterStore);
					}
					try {
						il.delete(prev, ih);
						changed = true;
					} catch (TargetLostException e) {
					}
				} else {
					// just delete the store instruction if preceding instruction is not a constant
					try {
						il.delete(ih);
						changed = true;
					} catch (TargetLostException e) {
					}
				}
			}

			ih = next;
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