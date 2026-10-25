/* ###
 * IP: Apache License 2.0
 */
package ghidra.app.plugin.core.analysis;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.*;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.lang.Processor;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * RL78 fixed tables and memory layout, for ELF and raw images alike:
 * <ul>
 * <li>The vector table (00000-0007F) and CALLT table (00080-000BF) hold 16-bit addresses.
 * Each entry becomes a pointer; targets that are not erased flash are disassembled and made
 * functions (reset_handler, isr_NN, callt_fn_NN). A function or entry point at 00000 (GCC's
 * crt0 defines the absolute symbol __rl78_abs__ = 0 there) is removed: 00000 is the reset
 * vector, not code.</li>
 * <li>With PMC.MAA = 0 (the reset value) data reads from F0000-FFFFF that no RAM, SFR or data
 * flash occupies return the code flash at the same address in 00000-0FFFF. The free part of
 * F2000-FFEDF whose 00000-0FFFF counterpart holds loaded bytes becomes a byte-mapped block
 * ("mirror"), so near pointers to constants in flash resolve.</li>
 * </ul>
 */
public class RL78VectorAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "RL78 Vectors and Flash Mirror";
	private static final String DESCRIPTION = "Marks up the RL78 vector and CALLT tables, " +
		"makes functions of their targets and maps the flash mirror (MAA = 0) at F0000-FFFFF.";

	public RL78VectorAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setPriority(AnalysisPriority.FORMAT_ANALYSIS.before());
		setDefaultEnablement(true);
		setSupportsOneTimeAnalysis();
	}

	@Override
	public boolean canAnalyze(Program program) {
		return program.getLanguage()
				.getProcessor()
				.equals(Processor.findOrPossiblyCreateProcessor("RL78"));
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
		Memory mem = program.getMemory();
		Listing listing = program.getListing();
		FunctionManager fm = program.getFunctionManager();
		SymbolTable st = program.getSymbolTable();
		Address zero = space.getAddress(0);
		if (!mem.contains(zero, zero.add(1))) {
			return true; // not an image of code flash
		}
		// no code at the reset vector
		Function f0 = fm.getFunctionAt(zero);
		if (f0 != null) {
			fm.removeFunction(zero);
		}
		st.removeExternalEntryPoint(zero);
		listing.clearCodeUnits(zero, zero.add(1), false);

		AddressSet targets = new AddressSet();
		java.util.Map<Address, String> names = new java.util.HashMap<>();
		PointerDataType p16 = new PointerDataType(null, 2, program.getDataTypeManager());
		for (int a = 0; a < 0xC0; a += 2) {
			monitor.checkCancelled();
			Address va = space.getAddress(a);
			if (!mem.contains(va, va.add(1))) {
				continue;
			}
			int w;
			try {
				w = mem.getShort(va) & 0xffff;
			}
			catch (MemoryAccessException e) {
				continue;
			}
			String name = a == 0 ? "RESET" : a < 0x80 ? String.format("vector_%02x", a)
					: String.format("callt_%02x", a);
			try {
				DataUtilities.createData(program, va, p16, 2, false,
					DataUtilities.ClearDataMode.CLEAR_ALL_CONFLICT_DATA);
				if (st.getPrimarySymbol(va) == null ||
					st.getPrimarySymbol(va).getSource() == SourceType.DEFAULT ||
					st.getPrimarySymbol(va).getName().equals("__rl78_abs__")) {
					st.createLabel(va, name, SourceType.ANALYSIS).setPrimary();
				}
			}
			catch (Exception e) {
				// keep existing markup
			}
			if (w == 0xffff || w < 0xC0) {
				continue; // erased or unused vector
			}
			Address t = space.getAddress(w);
			if (!mem.contains(t)) {
				continue;
			}
			targets.add(t);
			names.putIfAbsent(t, a == 0 ? "reset_handler"
					: (a < 0x80 ? "isr_" : "callt_fn_") + String.format("%02x", a));
		}
		if (!targets.isEmpty()) {
			new DisassembleCommand(targets, null, true).applyTo(program, monitor);
			for (java.util.Map.Entry<Address, String> e : names.entrySet()) {
				Address t = e.getKey();
				if (listing.getInstructionAt(t) == null) {
					continue;
				}
				if (fm.getFunctionAt(t) == null) {
					new CreateFunctionCmd(t).applyTo(program, monitor);
				}
				Function f = fm.getFunctionAt(t);
				if (f != null && f.getSymbol().getSource() == SourceType.DEFAULT) {
					try {
						f.setName(e.getValue(), SourceType.ANALYSIS);
					}
					catch (Exception ex) {
						// keep the default name
					}
				}
			}
		}
		createMirror(program, log);
		return true;
	}

	private static void createMirror(Program program, MessageLog log) {
		Memory mem = program.getMemory();
		if (mem.getBlock("mirror") != null) {
			return;
		}
		AddressSpace space = program.getAddressFactory().getDefaultAddressSpace();
		// source: loaded, initialized bytes in 02000-0FFFF (below 02000 the mirror window is
		// occupied by SFR2 and data flash)
		AddressSet src = new AddressSet();
		for (MemoryBlock b : mem.getBlocks()) {
			if (b.isInitialized() && b.isLoaded() && !b.isMapped()) {
				src.add(b.getStart(), b.getEnd());
			}
		}
		src = src.intersect(new AddressSet(space.getAddress(0x2000), space.getAddress(0xFFFF)));
		int n = 0;
		for (AddressRange r : src) {
			long lo = r.getMinAddress().getOffset();
			long hi = r.getMaxAddress().getOffset();
			// clip each piece of the window to addresses no block occupies, below the
			// general-purpose registers and SFRs at FFEE0
			long a = 0xF0000 + lo;
			long end = Math.min(0xF0000 + hi, 0xFFEDF);
			while (a <= end) {
				Address aa = space.getAddress(a);
				MemoryBlock occ = mem.getBlock(aa);
				if (occ != null) {
					a = occ.getEnd().getOffset() + 1;
					continue;
				}
				long b = a;
				while (b + 1 <= end && mem.getBlock(space.getAddress(b + 1)) == null) {
					b++;
				}
				try {
					MemoryBlock m = mem.createByteMappedBlock(n == 0 ? "mirror" : "mirror_" + n,
						aa, space.getAddress(a - 0xF0000), b - a + 1, false);
					m.setRead(true);
					m.setWrite(false);
					m.setExecute(false);
					m.setComment("Flash mirror (PMC.MAA = 0): reads of F0000-FFFFF return 00000-0FFFF");
					n++;
				}
				catch (Exception e) {
					log.appendMsg(NAME, "could not create the flash mirror at " + aa + ": " +
						e.getMessage());
				}
				a = b + 1;
			}
		}
	}
}
