package de.innot.sim6502;

public class CPUState {

	/* status indicator flags */
	public final static int M6502_CF = (1 << 0); /* carry */
	public final static int M6502_ZF = (1 << 1); /* zero */
	public final static int M6502_IF = (1 << 2); /* IRQ disable */
	public final static int M6502_DF = (1 << 3); /* decimal mode */
	public final static int M6502_BF = (1 << 4); /* BRK command */
	public final static int M6502_XF = (1 << 5); /* unused */
	public final static int M6502_VF = (1 << 6); /* overflow */
	public final static int M6502_NF = (1 << 7); /* negative */

	int IR; /* internal instruction register */
	int PC; /* internal program counter register */
	int AD; /* ADL/ADH internal register */

	/* regular registers */
	int A, X, Y, S, P; 

	int irq_pip;
	int nmi_pip;
	int brk_flags; /* M6502_BRK_* */
	boolean last_nmi_state;	// The state of the NMI pin on the last cycle (for edge detection)

	public int sp_dec() {
		int retval = this.S;
		this.S = (this.S - 1) & 0xff;
		return retval;
	}
	
	public int sp_inc() {
		int retval = this.S;
		this.S = (this.S + 1) & 0xff;
		return retval;
	}
	
	public int getSP() {
		return this.S;
	}
	
	public int getPC() {
		return this.PC;
	}
	
	void setStatusBits(int bits) {
		this.P |= bits;
	}

	void clearStatusBits(int bits) {
		this.P &= ~(bits);
	}

	boolean statusBitsSet(int bits) {
		return (this.P & bits) == bits;
	}

	public String toString() {
		StringBuffer str = new StringBuffer();
		str.append("A:0x").append(Integer.toHexString(this.A)).append("\t");
		str.append("X:0x").append(Integer.toHexString(this.X)).append("\t");
		str.append("Y:0x").append(Integer.toHexString(this.Y)).append("\t");
		str.append("P:").append(this.statusToString()).append("\t");
		str.append("SP: 0x01").append(Integer.toHexString(this.S & 0xff)).append("\t");
		str.append("PC:0x").append(Integer.toHexString(this.PC)).append("\t");

		String instr = "0x" + Integer.toHexString(IR >> 3);
		String cycle = Integer.toString(this.IR & 0x07);

		str.append("IR:").append(instr).append("/").append(cycle).append("\t");
		str.append("AD:0x").append(Integer.toHexString(this.AD)).append("\t");
		return (str.toString());
	}

	public String statusToString() {
        String str = ((this.P & M6502_NF) > 0 ? "N" : "-") +
                ((this.P & M6502_VF) > 0 ? "V" : "-") +
                ((this.P & M6502_XF) > 0 ? "X" : "-") +
                ((this.P & M6502_BF) > 0 ? "B" : "-") +
                ((this.P & M6502_DF) > 0 ? "D" : "-") +
                ((this.P & M6502_IF) > 0 ? "I" : "-") +
                ((this.P & M6502_ZF) > 0 ? "Z" : "-") +
                ((this.P & M6502_CF) > 0 ? "C" : "-");
		return (str);
	}
	
	public int getCurrentInstruction() {
		return IR >> 3;
	}
}
