package de.innot.sim6502;

/*
## zlib/libpng license

Copyright (2) 2025 Thomas Holland
Copyright (c) 2018 Andre Weissflog

This software is provided 'as-is', without any express or implied warranty.
In no event will the authors be held liable for any damages arising from the
use of this software.
Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:
    1. The origin of this software must not be misrepresented; you must not
    claim that you wrote the original software. If you use this software in a
    product, an acknowledgment in the product documentation would be
    appreciated but is not required.
    2. Altered source versions must be plainly marked as such, and must not
    be misrepresented as being the original software.
    3. This notice may not be removed or altered from any source
    distribution.
*/

/**
 * MOS 6502 CPU emulator.
 * 
 * Derived from the 6502 Simulator by Andre Weissflog
 * (<a href="https://github.com/floooh/chips/">Project repo</a>)
 * https://github.com/floooh/chips/
 * 
 * Converted to Java and refactored to be more object oriented.
 * 
 * This emuator handles all documented and undocumented Opcodes of the standard
 * 6502. Other variants like the 6510, 65c02 etc. are currently not supported.
 * 
 * It passes the the
 * (<a href="https://github.com/Klaus2m5/6502_65C02_functional_tests">6502
 * Functional Test</a>) by Klaus Dormann
 * 
 * Usage: Instantiate the {@link Sim6502} class, call the {@link Sim6502#tick}
 * method for every clock cycle.
 * 
 * 
 * @author Thomas Holland
 * @version 1.0 First release
 * 
 */

public class Sim6502 {

	/* status indicator flags */
	final static int CARRY_F = (1 << 0); /* carry */
	final static int ZERO_F = (1 << 1); /* zero */
	final static int IRQ_F = (1 << 2); /* IRQ disable */
	final static int DECIMAL_F = (1 << 3); /* decimal mode */
	final static int BREAK_F = (1 << 4); /* BRK command */
	final static int X_F = (1 << 5); /* unused */
	final static int OVERFLOW_F = (1 << 6); /* overflow */
	final static int NEG_F = (1 << 7); /* negative */

	/* internal BRK state flags */
	final static int BRK_IRQ = (1 << 0); /* IRQ was triggered */
	final static int BRK_NMI = (1 << 1); /* NMI was triggered */
	final static int BRK_RESET = (1 << 2); /* RES was triggered */

	final private CPUState cpu;
	final private Sim6502Output out;
	private Sim6502Input in;

	public Sim6502() {
		this.cpu = new CPUState();
		this.out = new Sim6502Output();
		this.out.rw = true;
		this.out.sync = true;
		this.out.addr = 0x0000;
		this.out.data = 0x00;

		this.cpu.P = ZERO_F;
		this.cpu.last_nmi_state = false;
	}

	public CPUState getState() {
		return this.cpu;
	}

	/* helper macros and functions for code-generated instruction decoder */
	static int _M6502_NZ(int p, int v) {
		p &= ~(NEG_F | ZERO_F);
		if (v == 0)
			p |= ZERO_F;
		if ((v & 0x80) == 0x80)
			p |= NEG_F;
		return p;
	}

	static void adc(CPUState cpu, int val) {
		if ((cpu.P & DECIMAL_F) != 0) {
			/* decimal mode (credit goes to MAME) */
			int c = cpu.statusBitsSet(CARRY_F) ? 1 : 0;
			cpu.clearStatusBits(NEG_F | OVERFLOW_F | ZERO_F | CARRY_F);

			// low BCD
			int al = (cpu.A & 0x0F) + (val & 0x0F) + c;
			if (al > 9) {
				al += 6;
			}
			int ovf = (al > 0x0f) ? 1 : 0;

			// high BCD
			int ah = (cpu.A >> 4) + (val >> 4) + ovf;

			if (0 == (cpu.A + val + c)) {
				cpu.setStatusBits(ZERO_F);
			} else if (1 == (ah & 0x08)) {
				cpu.setStatusBits(NEG_F);
			}
			if (0 != (~(cpu.A ^ val) & (cpu.A ^ (ah << 4)) & 0x80)) {
				cpu.setStatusBits(OVERFLOW_F);
			}
			if (ah > 9) {
				ah += 6;
			}
			if (ah > 15) {
				cpu.setStatusBits(CARRY_F);
			}
			cpu.A = ((ah << 4) | (al & 0x0F)) & 0xff;

		} else {

			/* default mode */
			int sum = cpu.A + val + (0 != (cpu.P & CARRY_F) ? 1 : 0);
			cpu.P &= ~(OVERFLOW_F | CARRY_F);
			cpu.P = _M6502_NZ(cpu.P, (sum & 0xff));
			if ((~(cpu.A ^ val) & (cpu.A ^ sum) & 0x80) == 0x80) {
				cpu.P |= OVERFLOW_F;
			}
			if ((sum & 0xFF00) > 0) {
				cpu.P |= CARRY_F;
			}
			cpu.A = sum & 0xFF;
		}
	}

	static void sbc(CPUState cpu, int val) {
		if (cpu.statusBitsSet(DECIMAL_F)) {
			/* decimal mode (credit goes to MAME) */
			int c = cpu.statusBitsSet(CARRY_F) ? 0 : 1;
			cpu.clearStatusBits(NEG_F | OVERFLOW_F | ZERO_F | CARRY_F);
			int diff = cpu.A - val - c;
			int al = (cpu.A & 0x0F) - (val & 0x0F) - c;
			if (al < 0) {
				al -= 6;
			}
			int ah = (cpu.A >> 4) - (val >> 4) - ((al < 0) ? 1 : 0);
			if (0 == diff) {
				cpu.P |= ZERO_F;
			} else if ((diff & 0x80) == 0x80) {
				cpu.P |= NEG_F;
			}
			if (((cpu.A ^ val) & (cpu.A ^ diff) & 0x80) == 0x80) {
				cpu.P |= OVERFLOW_F;
			}
			if (!((diff & 0xFF00) != 0)) {
				cpu.P |= CARRY_F;
			}
			if ((ah & 0x80) == 0x80) {
				ah -= 6;
			}
			cpu.A = ((ah << 4) | (al & 0x0F)) & 0xff;
		} else {
			/* default mode */
			int diff = (cpu.A - val - (cpu.statusBitsSet(CARRY_F) ? 0 : 1)) & 0xffff;
			cpu.clearStatusBits(OVERFLOW_F | CARRY_F);
			cpu.P = _M6502_NZ(cpu.P, diff & 0xff);
			if (((cpu.A ^ val) & (cpu.A ^ diff) & 0x80) == 0x80) {
				cpu.setStatusBits(OVERFLOW_F);
			}
			if (!((diff & 0xFF00) != 0)) {
				cpu.P |= CARRY_F;
			}
			cpu.A = diff & 0xFF;
		}
	}

	static void cmp(CPUState cpu, int r, int v) {
		int t = r - v;
		cpu.P = (_M6502_NZ(cpu.P, (int) t) & ~CARRY_F) | (((t & 0xFF00) != 0) ? 0 : CARRY_F);
	}

	static int asl(CPUState cpu, int v) {
		int shifted = (v << 1) & 0xff;
		cpu.P = (_M6502_NZ(cpu.P, shifted) & ~CARRY_F) | (((v & 0x80) == 0x80) ? CARRY_F : 0);
		return shifted;
	}

	static int lsr(CPUState cpu, int v) {
		cpu.P = (_M6502_NZ(cpu.P, v >> 1) & ~CARRY_F) | (((v & 0x01) == 0x01) ? CARRY_F : 0);
		return (v >> 1) & 0xff;
	}

	static int rol(CPUState cpu, int v) {
		boolean carry = cpu.statusBitsSet(CARRY_F);
		cpu.clearStatusBits(NEG_F | ZERO_F | CARRY_F);
		if ((v & 0x80) == 0x80) {
			cpu.P |= CARRY_F;
		}
		v = (v << 1) & 0xff;
		if (carry) {
			v |= 1;
		}
		cpu.P = _M6502_NZ(cpu.P, v);
		return v;
	}

	static int ror(CPUState cpu, int v) {
		boolean carry = cpu.statusBitsSet(CARRY_F);
		cpu.clearStatusBits(NEG_F | ZERO_F | CARRY_F);
		if ((v & 1) == 1) {
			cpu.P |= CARRY_F;
		}
		v = (v >> 1) & 0xff;
		if (carry) {
			v |= 0x80;
		}
		cpu.P = _M6502_NZ(cpu.P, v);
		return v;
	}

	static void bit(CPUState cpu, int v) {
		int t = cpu.A & v;
		cpu.clearStatusBits(NEG_F | OVERFLOW_F | ZERO_F);
		if (t == 0) {
			cpu.P |= ZERO_F;
		}
		cpu.P |= v & (NEG_F | OVERFLOW_F);
	}

	/**
	 * undocumented, unreliable ARR instruction, but this is tested by the Wolfgang
	 * Lorenz C64 test suite implementation taken from MAME
	 */
	static void arr(CPUState cpu) {
		if (cpu.statusBitsSet(DECIMAL_F)) {
			boolean c = cpu.statusBitsSet(CARRY_F);
			cpu.clearStatusBits(NEG_F | OVERFLOW_F | ZERO_F | CARRY_F);
			int a = cpu.A >> 1;
			if (c) {
				a |= 0x80;
			}
			cpu.P = _M6502_NZ(cpu.P, a);
			if (((a ^ cpu.A) & 0x40) == 0x40) {
				cpu.P |= OVERFLOW_F;
			}
			if ((cpu.A & 0xF) >= 5) {
				a = ((a + 6) & 0xF) | (a & 0xF0);
			}
			if ((cpu.A & 0xF0) >= 0x50) {
				a += 0x60;
				cpu.P |= CARRY_F;
			}
			cpu.A = a;
		} else {
			boolean c = cpu.statusBitsSet(CARRY_F);
			cpu.clearStatusBits(NEG_F | OVERFLOW_F | ZERO_F | CARRY_F);
			cpu.A >>= 1;
			if (c) {
				cpu.A |= 0x80;
			}
			cpu.P = _M6502_NZ(cpu.P, cpu.A);
			if ((cpu.A & 0x40) == 0x40) {
				cpu.P |= OVERFLOW_F | CARRY_F;
			}
			if ((cpu.A & 0x20) == 0x20) {
				cpu.P ^= OVERFLOW_F;
			}
		}
	}

	/**
	 * undocumented SBX instruction: AND X register with accumulator and store
	 * result in X register, then subtract byte from X register (without borrow)
	 * where the subtract works like a CMP instruction
	 */
	static void sbx(CPUState cpu, int v) {
		int t = (cpu.A & cpu.X) - v;
		cpu.P = _M6502_NZ(cpu.P, t) & ~CARRY_F;
		if (!((t & 0xFF00) > 0)) {
			cpu.P |= CARRY_F;
		}
		cpu.X = (int) t;
	}

	/**
	 * Set the address bus to the given value.
	 * 
	 * @param addr 16-bit address bus value.
	 */
	private void setA(int addr) {
		this.out.addr = addr & 0xffff;
	}

	/**
	 * Get the current value of the address bus.
	 * 
	 * @return 16-bit address bus value.
	 */
	private int getA() {
		return this.out.addr & 0xffff;
	}

	/**
	 * Set the output address and data bus to the given values.
	 * 
	 * @param addr 16-bit address bus value.
	 * @param data 8-bit data bus value
	 */
	private void setAD(int addr, int data) {
		this.out.addr = addr & 0xffff;
		this.out.data = data & 0xff;
	}

	/**
	 * Set the address bus to fetch the next opcode byte. SYNC flag is set to
	 * indicate a command cycle.
	 */
	private void fetch() {
		this.out.addr = this.cpu.PC & 0xffff;
		this.out.sync = true;
	}

	/**
	 * Set the Data bus to the given value.
	 * 
	 * @param data 8-bit data bus value
	 */
	private void setD(int data) {
		this.out.data = data & 0xff;
	}

	/**
	 * Get the current value of the Data bus.
	 * 
	 * @return 8-bit data bus value
	 */
	private int getD() {
		return this.in.data & 0xff;
	}

	/**
	 * Set W/R pin to high (memory read cycle)
	 */
	private void _RD() {
		this.out.rw = true;
	}

	/**
	 * Set W/R Line to low (memory write cycle)
	 */
	private void _WR() {
		this.out.rw = false;
	}

	/**
	 * Set N and Z flags depending on value.
	 * 
	 * N is set if the bit 7 of the value is set. Z is set if the value is zero.
	 * 
	 * @param v 8-bit data value.
	 */
	private void _NZ(int v) {
		this.cpu.P = _M6502_NZ(this.cpu.P, v);
	}

	/**
	 * Simulate a single clock cycle.
	 * 
	 * This should be called on the falling edge of Φ2.
	 * 
	 * This ensures correct behaviour in more complex designs that expect all address
	 * & data lines as well as the signals to be valid at the following rising edge
	 * of Φ2.
	 * 
	 * 
	 * @param input The current state of all input pins
	 * @return The new state of all output pins.
	 */
	public Sim6502Output tick(Sim6502Input input) {

		this.in = input;

		CPUState c = this.cpu; // shorten

		if (this.out.sync || !this.in.irq || !this.in.nmi || this.in.ready || !this.in.reset) {
			// interrupt detection also works in RDY phases, but only NMI is "sticky"

			// NMI is edge-triggered
			if (c.last_nmi_state && !input.nmi) {
				c.nmi_pip |= 0x100;
			}
			// IRQ test is level triggered
			if (!input.irq && (0 == (c.P & IRQ_F))) {
				c.irq_pip |= 0x100;
			}

			// RDY pin is only checked during read cycles
			if (this.out.rw && !input.ready) {
				c.last_nmi_state = input.nmi;
				c.irq_pip <<= 1;
				return this.out;
			}
			if (this.out.sync) {
				// load new instruction into 'instruction register' and restart tick counter
				c.IR = (getD() & 0xff) << 3;
				this.out.sync = false;

				// check IRQ, NMI and RES state
				// - IRQ is level-triggered and must be active in the full cycle
				// before SYNC
				// - NMI is edge-triggered, and the change must have happened in
				// any cycle before SYNC
				// - RES behaves slightly different than on a real 6502, we go
				// into RES state as soon as the pin goes active, from there
				// on, behaviour is 'standard'
				if (0 != (c.irq_pip & 0x400)) {
					c.brk_flags |= BRK_IRQ;
				}
				
				// was: if (0 != (c.nmi_pip & 0xFC00)) { , but this can lose NMIs in extreme Situations.
				if (c.nmi_pip >= 0x400) {
					c.brk_flags |= BRK_NMI;
				}
				if (!input.reset) {
					c.brk_flags |= BRK_RESET;
				}

				c.irq_pip &= 0x3FF;
				c.nmi_pip &= 0x3FF;

				// if interrupt or reset was requested, force a BRK instruction
				if (c.brk_flags != 0) {
					c.IR = 0;
					c.P &= ~BREAK_F;
				} else {
					c.PC++;
				}
			}
		}

		// reads are default, writes are special
		_RD();

		switch (c.IR++) {
		// <% decoder
		/* BRK */
		case (0x00 << 3) | 0:
			setA(c.PC);
			break;

		case (0x00 << 3) | 1:
			if (0 == (c.brk_flags & (BRK_IRQ | BRK_NMI))) {
				c.PC++;
			}
			setAD(0x0100 | c.sp_dec(), c.PC >> 8);
			if (0 == (c.brk_flags & BRK_RESET)) {
				_WR();
			}
			break;

		case (0x00 << 3) | 2:
			setAD(0x0100 | c.sp_dec(), c.PC);
			if (0 == (c.brk_flags & BRK_RESET)) {
				_WR();
			}
			break;

		case (0x00 << 3) | 3:
			setAD(0x0100 | c.sp_dec(), c.P | X_F);
			if ((c.brk_flags & BRK_RESET) == BRK_RESET) {
				c.AD = 0xFFFC;
			} else {
				_WR();
				if ((c.brk_flags & BRK_NMI) == BRK_NMI) {
					c.AD = 0xFFFA;
				} else {
					c.AD = 0xFFFE;
				}
			}
			break;

		case (0x00 << 3) | 4:
			setA(c.AD++);
			c.P |= (IRQ_F | BREAK_F);
			c.brk_flags = 0; 	// RES/NMI hijacking
			break;

		case (0x00 << 3) | 5:
			setA(c.AD);
			c.AD = getD();		// NMI "half-hijacking" not possible
			break;

		case (0x00 << 3) | 6:
			c.PC = (getD() << 8) | c.AD;
			fetch();
			break;

		case (0x00 << 3) | 7:
			assert (false);
			break;

		/* ORA (zp,X) */
		case (0x01 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x01 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x01 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x01 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x01 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x01 << 3) | 5:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x01 << 3) | 6:
			assert (false);
			break;
		case (0x01 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x02 << 3) | 0:
			setA(c.PC);
			break;
		case (0x02 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x02 << 3) | 2:
			assert (false);
			break;
		case (0x02 << 3) | 3:
			assert (false);
			break;
		case (0x02 << 3) | 4:
			assert (false);
			break;
		case (0x02 << 3) | 5:
			assert (false);
			break;
		case (0x02 << 3) | 6:
			assert (false);
			break;
		case (0x02 << 3) | 7:
			assert (false);
			break;

		/* SLO (zp,X) (undoc) */
		case (0x03 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x03 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x03 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x03 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x03 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x03 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x03 << 3) | 6:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x03 << 3) | 7:
			fetch();
			break;

		/* NOP zp (undoc) */
		case (0x04 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x04 << 3) | 1:
			setA(getD());
			break;
		case (0x04 << 3) | 2:
			fetch();
			break;
		case (0x04 << 3) | 3:
			assert (false);
			break;
		case (0x04 << 3) | 4:
			assert (false);
			break;
		case (0x04 << 3) | 5:
			assert (false);
			break;
		case (0x04 << 3) | 6:
			assert (false);
			break;
		case (0x04 << 3) | 7:
			assert (false);
			break;

		/* ORA zp */
		case (0x05 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x05 << 3) | 1:
			setA(getD());
			break;
		case (0x05 << 3) | 2:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x05 << 3) | 3:
			assert (false);
			break;
		case (0x05 << 3) | 4:
			assert (false);
			break;
		case (0x05 << 3) | 5:
			assert (false);
			break;
		case (0x05 << 3) | 6:
			assert (false);
			break;
		case (0x05 << 3) | 7:
			assert (false);
			break;

		/* ASL zp */
		case (0x06 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x06 << 3) | 1:
			setA(getD());
			break;
		case (0x06 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x06 << 3) | 3:
			setD(asl(c, c.AD));
			_WR();
			break;
		case (0x06 << 3) | 4:
			fetch();
			break;
		case (0x06 << 3) | 5:
			assert (false);
			break;
		case (0x06 << 3) | 6:
			assert (false);
			break;
		case (0x06 << 3) | 7:
			assert (false);
			break;

		/* SLO zp (undoc) */
		case (0x07 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x07 << 3) | 1:
			setA(getD());
			break;
		case (0x07 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x07 << 3) | 3:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x07 << 3) | 4:
			fetch();
			break;
		case (0x07 << 3) | 5:
			assert (false);
			break;
		case (0x07 << 3) | 6:
			assert (false);
			break;
		case (0x07 << 3) | 7:
			assert (false);
			break;

		/* PHP */
		case (0x08 << 3) | 0:
			setA(c.PC);
			break;
		case (0x08 << 3) | 1:
			setAD(0x0100 | c.sp_dec(), c.P | X_F);
			_WR();
			break;
		case (0x08 << 3) | 2:
			fetch();
			break;
		case (0x08 << 3) | 3:
			assert (false);
			break;
		case (0x08 << 3) | 4:
			assert (false);
			break;
		case (0x08 << 3) | 5:
			assert (false);
			break;
		case (0x08 << 3) | 6:
			assert (false);
			break;
		case (0x08 << 3) | 7:
			assert (false);
			break;

		/* ORA # */
		case (0x09 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x09 << 3) | 1:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x09 << 3) | 2:
			assert (false);
			break;
		case (0x09 << 3) | 3:
			assert (false);
			break;
		case (0x09 << 3) | 4:
			assert (false);
			break;
		case (0x09 << 3) | 5:
			assert (false);
			break;
		case (0x09 << 3) | 6:
			assert (false);
			break;
		case (0x09 << 3) | 7:
			assert (false);
			break;

		/* ASLA */
		case (0x0A << 3) | 0:
			setA(c.PC);
			break;
		case (0x0A << 3) | 1:
			c.A = asl(c, c.A);
			fetch();
			break;
		case (0x0A << 3) | 2:
			assert (false);
			break;
		case (0x0A << 3) | 3:
			assert (false);
			break;
		case (0x0A << 3) | 4:
			assert (false);
			break;
		case (0x0A << 3) | 5:
			assert (false);
			break;
		case (0x0A << 3) | 6:
			assert (false);
			break;
		case (0x0A << 3) | 7:
			assert (false);
			break;

		/* ANC # (undoc) */
		case (0x0B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x0B << 3) | 1:
			c.A &= getD();
			_NZ(c.A);
			if ((c.A & 0x80) == 0x80) {
				c.P |= CARRY_F;
			} else {
				c.P &= ~CARRY_F;
			}
			fetch();
			break;
		case (0x0B << 3) | 2:
			assert (false);
			break;
		case (0x0B << 3) | 3:
			assert (false);
			break;
		case (0x0B << 3) | 4:
			assert (false);
			break;
		case (0x0B << 3) | 5:
			assert (false);
			break;
		case (0x0B << 3) | 6:
			assert (false);
			break;
		case (0x0B << 3) | 7:
			assert (false);
			break;

		/* NOP abs (undoc) */
		case (0x0C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x0C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x0C << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x0C << 3) | 3:
			fetch();
			break;
		case (0x0C << 3) | 4:
			assert (false);
			break;
		case (0x0C << 3) | 5:
			assert (false);
			break;
		case (0x0C << 3) | 6:
			assert (false);
			break;
		case (0x0C << 3) | 7:
			assert (false);
			break;

		/* ORA abs */
		case (0x0D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x0D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x0D << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x0D << 3) | 3:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x0D << 3) | 4:
			assert (false);
			break;
		case (0x0D << 3) | 5:
			assert (false);
			break;
		case (0x0D << 3) | 6:
			assert (false);
			break;
		case (0x0D << 3) | 7:
			assert (false);
			break;

		/* ASL abs */
		case (0x0E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x0E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x0E << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x0E << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x0E << 3) | 4:
			setD(asl(c, c.AD));
			_WR();
			break;
		case (0x0E << 3) | 5:
			fetch();
			break;
		case (0x0E << 3) | 6:
			assert (false);
			break;
		case (0x0E << 3) | 7:
			assert (false);
			break;

		/* SLO abs (undoc) */
		case (0x0F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x0F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x0F << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x0F << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x0F << 3) | 4:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x0F << 3) | 5:
			fetch();
			break;
		case (0x0F << 3) | 6:
			assert (false);
			break;
		case (0x0F << 3) | 7:
			assert (false);
			break;

		/* BPL # */
		case (0x10 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x10 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x80) != 0x0) {
				fetch();
			}
			;
			break;
		case (0x10 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0x10 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0x10 << 3) | 4:
			assert (false);
			break;
		case (0x10 << 3) | 5:
			assert (false);
			break;
		case (0x10 << 3) | 6:
			assert (false);
			break;
		case (0x10 << 3) | 7:
			assert (false);
			break;

		/* ORA (zp),Y */
		case (0x11 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x11 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x11 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x11 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x11 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x11 << 3) | 5:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x11 << 3) | 6:
			assert (false);
			break;
		case (0x11 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x12 << 3) | 0:
			setA(c.PC);
			break;
		case (0x12 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x12 << 3) | 2:
			assert (false);
			break;
		case (0x12 << 3) | 3:
			assert (false);
			break;
		case (0x12 << 3) | 4:
			assert (false);
			break;
		case (0x12 << 3) | 5:
			assert (false);
			break;
		case (0x12 << 3) | 6:
			assert (false);
			break;
		case (0x12 << 3) | 7:
			assert (false);
			break;

		/* SLO (zp),Y (undoc) */
		case (0x13 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x13 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x13 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x13 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x13 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x13 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x13 << 3) | 6:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x13 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0x14 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x14 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x14 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x14 << 3) | 3:
			fetch();
			break;
		case (0x14 << 3) | 4:
			assert (false);
			break;
		case (0x14 << 3) | 5:
			assert (false);
			break;
		case (0x14 << 3) | 6:
			assert (false);
			break;
		case (0x14 << 3) | 7:
			assert (false);
			break;

		/* ORA zp,X */
		case (0x15 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x15 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x15 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x15 << 3) | 3:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x15 << 3) | 4:
			assert (false);
			break;
		case (0x15 << 3) | 5:
			assert (false);
			break;
		case (0x15 << 3) | 6:
			assert (false);
			break;
		case (0x15 << 3) | 7:
			assert (false);
			break;

		/* ASL zp,X */
		case (0x16 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x16 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x16 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x16 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x16 << 3) | 4:
			setD(asl(c, c.AD));
			_WR();
			break;
		case (0x16 << 3) | 5:
			fetch();
			break;
		case (0x16 << 3) | 6:
			assert (false);
			break;
		case (0x16 << 3) | 7:
			assert (false);
			break;

		/* SLO zp,X (undoc) */
		case (0x17 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x17 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x17 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x17 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x17 << 3) | 4:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x17 << 3) | 5:
			fetch();
			break;
		case (0x17 << 3) | 6:
			assert (false);
			break;
		case (0x17 << 3) | 7:
			assert (false);
			break;

		/* CLC */
		case (0x18 << 3) | 0:
			setA(c.PC);
			break;
		case (0x18 << 3) | 1:
			c.P &= ~0x1;
			fetch();
			break;
		case (0x18 << 3) | 2:
			assert (false);
			break;
		case (0x18 << 3) | 3:
			assert (false);
			break;
		case (0x18 << 3) | 4:
			assert (false);
			break;
		case (0x18 << 3) | 5:
			assert (false);
			break;
		case (0x18 << 3) | 6:
			assert (false);
			break;
		case (0x18 << 3) | 7:
			assert (false);
			break;

		/* ORA abs,Y */
		case (0x19 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x19 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x19 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x19 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x19 << 3) | 4:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x19 << 3) | 5:
			assert (false);
			break;
		case (0x19 << 3) | 6:
			assert (false);
			break;
		case (0x19 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0x1A << 3) | 0:
			setA(c.PC);
			break;
		case (0x1A << 3) | 1:
			fetch();
			break;
		case (0x1A << 3) | 2:
			assert (false);
			break;
		case (0x1A << 3) | 3:
			assert (false);
			break;
		case (0x1A << 3) | 4:
			assert (false);
			break;
		case (0x1A << 3) | 5:
			assert (false);
			break;
		case (0x1A << 3) | 6:
			assert (false);
			break;
		case (0x1A << 3) | 7:
			assert (false);
			break;

		/* SLO abs,Y (undoc) */
		case (0x1B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x1B << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x1B << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x1B << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x1B << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x1B << 3) | 5:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x1B << 3) | 6:
			fetch();
			break;
		case (0x1B << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0x1C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x1C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x1C << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x1C << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x1C << 3) | 4:
			fetch();
			break;
		case (0x1C << 3) | 5:
			assert (false);
			break;
		case (0x1C << 3) | 6:
			assert (false);
			break;
		case (0x1C << 3) | 7:
			assert (false);
			break;

		/* ORA abs,X */
		case (0x1D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x1D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x1D << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x1D << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x1D << 3) | 4:
			c.A |= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x1D << 3) | 5:
			assert (false);
			break;
		case (0x1D << 3) | 6:
			assert (false);
			break;
		case (0x1D << 3) | 7:
			assert (false);
			break;

		/* ASL abs,X */
		case (0x1E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x1E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x1E << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x1E << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x1E << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x1E << 3) | 5:
			setD(asl(c, c.AD));
			_WR();
			break;
		case (0x1E << 3) | 6:
			fetch();
			break;
		case (0x1E << 3) | 7:
			assert (false);
			break;

		/* SLO abs,X (undoc) */
		case (0x1F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x1F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x1F << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x1F << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x1F << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x1F << 3) | 5:
			c.AD = asl(c, c.AD);
			setD(c.AD);
			c.A |= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x1F << 3) | 6:
			fetch();
			break;
		case (0x1F << 3) | 7:
			assert (false);
			break;

		/* JSR */
		case (0x20 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x20 << 3) | 1:
			setA(0x0100 | c.S);
			c.AD = getD();
			break;
		case (0x20 << 3) | 2:
			setAD(0x0100 | c.sp_dec(), c.PC >> 8);
			_WR();
			break;
		case (0x20 << 3) | 3:
			setAD(0x0100 | c.sp_dec(), c.PC);
			_WR();
			break;
		case (0x20 << 3) | 4:
			setA(c.PC);
			break;
		case (0x20 << 3) | 5:
			c.PC = (getD() << 8) | c.AD;
			fetch();
			break;
		case (0x20 << 3) | 6:
			assert (false);
			break;
		case (0x20 << 3) | 7:
			assert (false);
			break;

		/* AND (zp,X) */
		case (0x21 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x21 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x21 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x21 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x21 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x21 << 3) | 5:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x21 << 3) | 6:
			assert (false);
			break;
		case (0x21 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x22 << 3) | 0:
			setA(c.PC);
			break;
		case (0x22 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x22 << 3) | 2:
			assert (false);
			break;
		case (0x22 << 3) | 3:
			assert (false);
			break;
		case (0x22 << 3) | 4:
			assert (false);
			break;
		case (0x22 << 3) | 5:
			assert (false);
			break;
		case (0x22 << 3) | 6:
			assert (false);
			break;
		case (0x22 << 3) | 7:
			assert (false);
			break;

		/* RLA (zp,X) (undoc) */
		case (0x23 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x23 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x23 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x23 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x23 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x23 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x23 << 3) | 6:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x23 << 3) | 7:
			fetch();
			break;

		/* BIT zp */
		case (0x24 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x24 << 3) | 1:
			setA(getD());
			break;
		case (0x24 << 3) | 2:
			bit(c, getD());
			fetch();
			break;
		case (0x24 << 3) | 3:
			assert (false);
			break;
		case (0x24 << 3) | 4:
			assert (false);
			break;
		case (0x24 << 3) | 5:
			assert (false);
			break;
		case (0x24 << 3) | 6:
			assert (false);
			break;
		case (0x24 << 3) | 7:
			assert (false);
			break;

		/* AND zp */
		case (0x25 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x25 << 3) | 1:
			setA(getD());
			break;
		case (0x25 << 3) | 2:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x25 << 3) | 3:
			assert (false);
			break;
		case (0x25 << 3) | 4:
			assert (false);
			break;
		case (0x25 << 3) | 5:
			assert (false);
			break;
		case (0x25 << 3) | 6:
			assert (false);
			break;
		case (0x25 << 3) | 7:
			assert (false);
			break;

		/* ROL zp */
		case (0x26 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x26 << 3) | 1:
			setA(getD());
			break;
		case (0x26 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x26 << 3) | 3:
			setD(rol(c, c.AD));
			_WR();
			break;
		case (0x26 << 3) | 4:
			fetch();
			break;
		case (0x26 << 3) | 5:
			assert (false);
			break;
		case (0x26 << 3) | 6:
			assert (false);
			break;
		case (0x26 << 3) | 7:
			assert (false);
			break;

		/* RLA zp (undoc) */
		case (0x27 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x27 << 3) | 1:
			setA(getD());
			break;
		case (0x27 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x27 << 3) | 3:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x27 << 3) | 4:
			fetch();
			break;
		case (0x27 << 3) | 5:
			assert (false);
			break;
		case (0x27 << 3) | 6:
			assert (false);
			break;
		case (0x27 << 3) | 7:
			assert (false);
			break;

		/* PLP */
		case (0x28 << 3) | 0:
			setA(c.PC);
			break;
		case (0x28 << 3) | 1:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x28 << 3) | 2:
			setA(0x0100 | c.S);
			break;
		case (0x28 << 3) | 3:
			c.P = (getD() | BREAK_F) & ~X_F;
			fetch();
			break;
		case (0x28 << 3) | 4:
			assert (false);
			break;
		case (0x28 << 3) | 5:
			assert (false);
			break;
		case (0x28 << 3) | 6:
			assert (false);
			break;
		case (0x28 << 3) | 7:
			assert (false);
			break;

		/* AND # */
		case (0x29 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x29 << 3) | 1:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x29 << 3) | 2:
			assert (false);
			break;
		case (0x29 << 3) | 3:
			assert (false);
			break;
		case (0x29 << 3) | 4:
			assert (false);
			break;
		case (0x29 << 3) | 5:
			assert (false);
			break;
		case (0x29 << 3) | 6:
			assert (false);
			break;
		case (0x29 << 3) | 7:
			assert (false);
			break;

		/* ROLA */
		case (0x2A << 3) | 0:
			setA(c.PC);
			break;
		case (0x2A << 3) | 1:
			c.A = rol(c, c.A);
			fetch();
			break;
		case (0x2A << 3) | 2:
			assert (false);
			break;
		case (0x2A << 3) | 3:
			assert (false);
			break;
		case (0x2A << 3) | 4:
			assert (false);
			break;
		case (0x2A << 3) | 5:
			assert (false);
			break;
		case (0x2A << 3) | 6:
			assert (false);
			break;
		case (0x2A << 3) | 7:
			assert (false);
			break;

		/* ANC # (undoc) */
		case (0x2B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x2B << 3) | 1:
			c.A &= getD();
			_NZ(c.A);
			if ((c.A & 0x80) > 0) {
				c.P |= CARRY_F;
			} else {
				c.P &= ~CARRY_F;
			}
			fetch();
			break;
		case (0x2B << 3) | 2:
			assert (false);
			break;
		case (0x2B << 3) | 3:
			assert (false);
			break;
		case (0x2B << 3) | 4:
			assert (false);
			break;
		case (0x2B << 3) | 5:
			assert (false);
			break;
		case (0x2B << 3) | 6:
			assert (false);
			break;
		case (0x2B << 3) | 7:
			assert (false);
			break;

		/* BIT abs */
		case (0x2C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x2C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x2C << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x2C << 3) | 3:
			bit(c, getD());
			fetch();
			break;
		case (0x2C << 3) | 4:
			assert (false);
			break;
		case (0x2C << 3) | 5:
			assert (false);
			break;
		case (0x2C << 3) | 6:
			assert (false);
			break;
		case (0x2C << 3) | 7:
			assert (false);
			break;

		/* AND abs */
		case (0x2D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x2D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x2D << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x2D << 3) | 3:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x2D << 3) | 4:
			assert (false);
			break;
		case (0x2D << 3) | 5:
			assert (false);
			break;
		case (0x2D << 3) | 6:
			assert (false);
			break;
		case (0x2D << 3) | 7:
			assert (false);
			break;

		/* ROL abs */
		case (0x2E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x2E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x2E << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x2E << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x2E << 3) | 4:
			setD(rol(c, c.AD));
			_WR();
			break;
		case (0x2E << 3) | 5:
			fetch();
			break;
		case (0x2E << 3) | 6:
			assert (false);
			break;
		case (0x2E << 3) | 7:
			assert (false);
			break;

		/* RLA abs (undoc) */
		case (0x2F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x2F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x2F << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x2F << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x2F << 3) | 4:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x2F << 3) | 5:
			fetch();
			break;
		case (0x2F << 3) | 6:
			assert (false);
			break;
		case (0x2F << 3) | 7:
			assert (false);
			break;

		/* BMI # */
		case (0x30 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x30 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x80) != 0x80) {
				fetch();
			}
			;
			break;
		case (0x30 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0x30 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0x30 << 3) | 4:
			assert (false);
			break;
		case (0x30 << 3) | 5:
			assert (false);
			break;
		case (0x30 << 3) | 6:
			assert (false);
			break;
		case (0x30 << 3) | 7:
			assert (false);
			break;

		/* AND (zp),Y */
		case (0x31 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x31 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x31 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x31 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x31 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x31 << 3) | 5:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x31 << 3) | 6:
			assert (false);
			break;
		case (0x31 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x32 << 3) | 0:
			setA(c.PC);
			break;
		case (0x32 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x32 << 3) | 2:
			assert (false);
			break;
		case (0x32 << 3) | 3:
			assert (false);
			break;
		case (0x32 << 3) | 4:
			assert (false);
			break;
		case (0x32 << 3) | 5:
			assert (false);
			break;
		case (0x32 << 3) | 6:
			assert (false);
			break;
		case (0x32 << 3) | 7:
			assert (false);
			break;

		/* RLA (zp),Y (undoc) */
		case (0x33 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x33 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x33 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x33 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x33 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x33 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x33 << 3) | 6:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x33 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0x34 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x34 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x34 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x34 << 3) | 3:
			fetch();
			break;
		case (0x34 << 3) | 4:
			assert (false);
			break;
		case (0x34 << 3) | 5:
			assert (false);
			break;
		case (0x34 << 3) | 6:
			assert (false);
			break;
		case (0x34 << 3) | 7:
			assert (false);
			break;

		/* AND zp,X */
		case (0x35 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x35 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x35 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x35 << 3) | 3:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x35 << 3) | 4:
			assert (false);
			break;
		case (0x35 << 3) | 5:
			assert (false);
			break;
		case (0x35 << 3) | 6:
			assert (false);
			break;
		case (0x35 << 3) | 7:
			assert (false);
			break;

		/* ROL zp,X */
		case (0x36 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x36 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x36 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x36 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x36 << 3) | 4:
			setD(rol(c, c.AD));
			_WR();
			break;
		case (0x36 << 3) | 5:
			fetch();
			break;
		case (0x36 << 3) | 6:
			assert (false);
			break;
		case (0x36 << 3) | 7:
			assert (false);
			break;

		/* RLA zp,X (undoc) */
		case (0x37 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x37 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x37 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x37 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x37 << 3) | 4:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x37 << 3) | 5:
			fetch();
			break;
		case (0x37 << 3) | 6:
			assert (false);
			break;
		case (0x37 << 3) | 7:
			assert (false);
			break;

		/* SEC */
		case (0x38 << 3) | 0:
			setA(c.PC);
			break;
		case (0x38 << 3) | 1:
			c.P |= 0x1;
			fetch();
			break;
		case (0x38 << 3) | 2:
			assert (false);
			break;
		case (0x38 << 3) | 3:
			assert (false);
			break;
		case (0x38 << 3) | 4:
			assert (false);
			break;
		case (0x38 << 3) | 5:
			assert (false);
			break;
		case (0x38 << 3) | 6:
			assert (false);
			break;
		case (0x38 << 3) | 7:
			assert (false);
			break;

		/* AND abs,Y */
		case (0x39 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x39 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x39 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x39 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x39 << 3) | 4:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x39 << 3) | 5:
			assert (false);
			break;
		case (0x39 << 3) | 6:
			assert (false);
			break;
		case (0x39 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0x3A << 3) | 0:
			setA(c.PC);
			break;
		case (0x3A << 3) | 1:
			fetch();
			break;
		case (0x3A << 3) | 2:
			assert (false);
			break;
		case (0x3A << 3) | 3:
			assert (false);
			break;
		case (0x3A << 3) | 4:
			assert (false);
			break;
		case (0x3A << 3) | 5:
			assert (false);
			break;
		case (0x3A << 3) | 6:
			assert (false);
			break;
		case (0x3A << 3) | 7:
			assert (false);
			break;

		/* RLA abs,Y (undoc) */
		case (0x3B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x3B << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x3B << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x3B << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x3B << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x3B << 3) | 5:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x3B << 3) | 6:
			fetch();
			break;
		case (0x3B << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0x3C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x3C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x3C << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x3C << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x3C << 3) | 4:
			fetch();
			break;
		case (0x3C << 3) | 5:
			assert (false);
			break;
		case (0x3C << 3) | 6:
			assert (false);
			break;
		case (0x3C << 3) | 7:
			assert (false);
			break;

		/* AND abs,X */
		case (0x3D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x3D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x3D << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x3D << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x3D << 3) | 4:
			c.A &= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x3D << 3) | 5:
			assert (false);
			break;
		case (0x3D << 3) | 6:
			assert (false);
			break;
		case (0x3D << 3) | 7:
			assert (false);
			break;

		/* ROL abs,X */
		case (0x3E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x3E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x3E << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x3E << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x3E << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x3E << 3) | 5:
			setD(rol(c, c.AD));
			_WR();
			break;
		case (0x3E << 3) | 6:
			fetch();
			break;
		case (0x3E << 3) | 7:
			assert (false);
			break;

		/* RLA abs,X (undoc) */
		case (0x3F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x3F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x3F << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x3F << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x3F << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x3F << 3) | 5:
			c.AD = rol(c, c.AD);
			setD(c.AD);
			c.A &= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x3F << 3) | 6:
			fetch();
			break;
		case (0x3F << 3) | 7:
			assert (false);
			break;

		/* RTI */
		case (0x40 << 3) | 0:
			setA(c.PC);
			break;
		case (0x40 << 3) | 1:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x40 << 3) | 2:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x40 << 3) | 3:
			setA(0x0100 | c.sp_inc());
			c.P = (getD() | BREAK_F) & ~X_F;
			break;
		case (0x40 << 3) | 4:
			setA(0x0100 | c.S);
			c.AD = getD();
			break;
		case (0x40 << 3) | 5:
			c.PC = (getD() << 8) | c.AD;
			fetch();
			break;
		case (0x40 << 3) | 6:
			assert (false);
			break;
		case (0x40 << 3) | 7:
			assert (false);
			break;

		/* EOR (zp,X) */
		case (0x41 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x41 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x41 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x41 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x41 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x41 << 3) | 5:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x41 << 3) | 6:
			assert (false);
			break;
		case (0x41 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x42 << 3) | 0:
			setA(c.PC);
			break;
		case (0x42 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x42 << 3) | 2:
			assert (false);
			break;
		case (0x42 << 3) | 3:
			assert (false);
			break;
		case (0x42 << 3) | 4:
			assert (false);
			break;
		case (0x42 << 3) | 5:
			assert (false);
			break;
		case (0x42 << 3) | 6:
			assert (false);
			break;
		case (0x42 << 3) | 7:
			assert (false);
			break;

		/* SRE (zp,X) (undoc) */
		case (0x43 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x43 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x43 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x43 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x43 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x43 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x43 << 3) | 6:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x43 << 3) | 7:
			fetch();
			break;

		/* NOP zp (undoc) */
		case (0x44 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x44 << 3) | 1:
			setA(getD());
			break;
		case (0x44 << 3) | 2:
			fetch();
			break;
		case (0x44 << 3) | 3:
			assert (false);
			break;
		case (0x44 << 3) | 4:
			assert (false);
			break;
		case (0x44 << 3) | 5:
			assert (false);
			break;
		case (0x44 << 3) | 6:
			assert (false);
			break;
		case (0x44 << 3) | 7:
			assert (false);
			break;

		/* EOR zp */
		case (0x45 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x45 << 3) | 1:
			setA(getD());
			break;
		case (0x45 << 3) | 2:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x45 << 3) | 3:
			assert (false);
			break;
		case (0x45 << 3) | 4:
			assert (false);
			break;
		case (0x45 << 3) | 5:
			assert (false);
			break;
		case (0x45 << 3) | 6:
			assert (false);
			break;
		case (0x45 << 3) | 7:
			assert (false);
			break;

		/* LSR zp */
		case (0x46 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x46 << 3) | 1:
			setA(getD());
			break;
		case (0x46 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x46 << 3) | 3:
			setD(lsr(c, c.AD));
			_WR();
			break;
		case (0x46 << 3) | 4:
			fetch();
			break;
		case (0x46 << 3) | 5:
			assert (false);
			break;
		case (0x46 << 3) | 6:
			assert (false);
			break;
		case (0x46 << 3) | 7:
			assert (false);
			break;

		/* SRE zp (undoc) */
		case (0x47 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x47 << 3) | 1:
			setA(getD());
			break;
		case (0x47 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x47 << 3) | 3:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x47 << 3) | 4:
			fetch();
			break;
		case (0x47 << 3) | 5:
			assert (false);
			break;
		case (0x47 << 3) | 6:
			assert (false);
			break;
		case (0x47 << 3) | 7:
			assert (false);
			break;

		/* PHA */
		case (0x48 << 3) | 0:
			setA(c.PC);
			break;
		case (0x48 << 3) | 1:
			setAD(0x0100 | c.sp_dec(), c.A);
			_WR();
			break;
		case (0x48 << 3) | 2:
			fetch();
			break;
		case (0x48 << 3) | 3:
			assert (false);
			break;
		case (0x48 << 3) | 4:
			assert (false);
			break;
		case (0x48 << 3) | 5:
			assert (false);
			break;
		case (0x48 << 3) | 6:
			assert (false);
			break;
		case (0x48 << 3) | 7:
			assert (false);
			break;

		/* EOR # */
		case (0x49 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x49 << 3) | 1:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x49 << 3) | 2:
			assert (false);
			break;
		case (0x49 << 3) | 3:
			assert (false);
			break;
		case (0x49 << 3) | 4:
			assert (false);
			break;
		case (0x49 << 3) | 5:
			assert (false);
			break;
		case (0x49 << 3) | 6:
			assert (false);
			break;
		case (0x49 << 3) | 7:
			assert (false);
			break;

		/* LSRA */
		case (0x4A << 3) | 0:
			setA(c.PC);
			break;
		case (0x4A << 3) | 1:
			c.A = lsr(c, c.A);
			fetch();
			break;
		case (0x4A << 3) | 2:
			assert (false);
			break;
		case (0x4A << 3) | 3:
			assert (false);
			break;
		case (0x4A << 3) | 4:
			assert (false);
			break;
		case (0x4A << 3) | 5:
			assert (false);
			break;
		case (0x4A << 3) | 6:
			assert (false);
			break;
		case (0x4A << 3) | 7:
			assert (false);
			break;

		/* ASR # (undoc) */
		case (0x4B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x4B << 3) | 1:
			c.A &= getD();
			c.A = lsr(c, c.A);
			fetch();
			break;
		case (0x4B << 3) | 2:
			assert (false);
			break;
		case (0x4B << 3) | 3:
			assert (false);
			break;
		case (0x4B << 3) | 4:
			assert (false);
			break;
		case (0x4B << 3) | 5:
			assert (false);
			break;
		case (0x4B << 3) | 6:
			assert (false);
			break;
		case (0x4B << 3) | 7:
			assert (false);
			break;

		/* JMP */
		case (0x4C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x4C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x4C << 3) | 2:
			c.PC = (getD() << 8) | c.AD;
			fetch();
			break;
		case (0x4C << 3) | 3:
			assert (false);
			break;
		case (0x4C << 3) | 4:
			assert (false);
			break;
		case (0x4C << 3) | 5:
			assert (false);
			break;
		case (0x4C << 3) | 6:
			assert (false);
			break;
		case (0x4C << 3) | 7:
			assert (false);
			break;

		/* EOR abs */
		case (0x4D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x4D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x4D << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x4D << 3) | 3:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x4D << 3) | 4:
			assert (false);
			break;
		case (0x4D << 3) | 5:
			assert (false);
			break;
		case (0x4D << 3) | 6:
			assert (false);
			break;
		case (0x4D << 3) | 7:
			assert (false);
			break;

		/* LSR abs */
		case (0x4E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x4E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x4E << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x4E << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x4E << 3) | 4:
			setD(lsr(c, c.AD));
			_WR();
			break;
		case (0x4E << 3) | 5:
			fetch();
			break;
		case (0x4E << 3) | 6:
			assert (false);
			break;
		case (0x4E << 3) | 7:
			assert (false);
			break;

		/* SRE abs (undoc) */
		case (0x4F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x4F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x4F << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x4F << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x4F << 3) | 4:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x4F << 3) | 5:
			fetch();
			break;
		case (0x4F << 3) | 6:
			assert (false);
			break;
		case (0x4F << 3) | 7:
			assert (false);
			break;

		/* BVC # */
		case (0x50 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x50 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x40) != 0x0) {
				fetch();
			}
			;
			break;
		case (0x50 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0x50 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0x50 << 3) | 4:
			assert (false);
			break;
		case (0x50 << 3) | 5:
			assert (false);
			break;
		case (0x50 << 3) | 6:
			assert (false);
			break;
		case (0x50 << 3) | 7:
			assert (false);
			break;

		/* EOR (zp),Y */
		case (0x51 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x51 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x51 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x51 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x51 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x51 << 3) | 5:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x51 << 3) | 6:
			assert (false);
			break;
		case (0x51 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x52 << 3) | 0:
			setA(c.PC);
			break;
		case (0x52 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x52 << 3) | 2:
			assert (false);
			break;
		case (0x52 << 3) | 3:
			assert (false);
			break;
		case (0x52 << 3) | 4:
			assert (false);
			break;
		case (0x52 << 3) | 5:
			assert (false);
			break;
		case (0x52 << 3) | 6:
			assert (false);
			break;
		case (0x52 << 3) | 7:
			assert (false);
			break;

		/* SRE (zp),Y (undoc) */
		case (0x53 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x53 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x53 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x53 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x53 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x53 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x53 << 3) | 6:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x53 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0x54 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x54 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x54 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x54 << 3) | 3:
			fetch();
			break;
		case (0x54 << 3) | 4:
			assert (false);
			break;
		case (0x54 << 3) | 5:
			assert (false);
			break;
		case (0x54 << 3) | 6:
			assert (false);
			break;
		case (0x54 << 3) | 7:
			assert (false);
			break;

		/* EOR zp,X */
		case (0x55 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x55 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x55 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x55 << 3) | 3:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x55 << 3) | 4:
			assert (false);
			break;
		case (0x55 << 3) | 5:
			assert (false);
			break;
		case (0x55 << 3) | 6:
			assert (false);
			break;
		case (0x55 << 3) | 7:
			assert (false);
			break;

		/* LSR zp,X */
		case (0x56 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x56 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x56 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x56 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x56 << 3) | 4:
			setD(lsr(c, c.AD));
			_WR();
			break;
		case (0x56 << 3) | 5:
			fetch();
			break;
		case (0x56 << 3) | 6:
			assert (false);
			break;
		case (0x56 << 3) | 7:
			assert (false);
			break;

		/* SRE zp,X (undoc) */
		case (0x57 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x57 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x57 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x57 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x57 << 3) | 4:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x57 << 3) | 5:
			fetch();
			break;
		case (0x57 << 3) | 6:
			assert (false);
			break;
		case (0x57 << 3) | 7:
			assert (false);
			break;

		/* CLI */
		case (0x58 << 3) | 0:
			setA(c.PC);
			break;
		case (0x58 << 3) | 1:
			c.P &= ~0x4;
			fetch();
			break;
		case (0x58 << 3) | 2:
			assert (false);
			break;
		case (0x58 << 3) | 3:
			assert (false);
			break;
		case (0x58 << 3) | 4:
			assert (false);
			break;
		case (0x58 << 3) | 5:
			assert (false);
			break;
		case (0x58 << 3) | 6:
			assert (false);
			break;
		case (0x58 << 3) | 7:
			assert (false);
			break;

		/* EOR abs,Y */
		case (0x59 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x59 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x59 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x59 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x59 << 3) | 4:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x59 << 3) | 5:
			assert (false);
			break;
		case (0x59 << 3) | 6:
			assert (false);
			break;
		case (0x59 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0x5A << 3) | 0:
			setA(c.PC);
			break;
		case (0x5A << 3) | 1:
			fetch();
			break;
		case (0x5A << 3) | 2:
			assert (false);
			break;
		case (0x5A << 3) | 3:
			assert (false);
			break;
		case (0x5A << 3) | 4:
			assert (false);
			break;
		case (0x5A << 3) | 5:
			assert (false);
			break;
		case (0x5A << 3) | 6:
			assert (false);
			break;
		case (0x5A << 3) | 7:
			assert (false);
			break;

		/* SRE abs,Y (undoc) */
		case (0x5B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x5B << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x5B << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x5B << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x5B << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x5B << 3) | 5:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x5B << 3) | 6:
			fetch();
			break;
		case (0x5B << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0x5C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x5C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x5C << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x5C << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x5C << 3) | 4:
			fetch();
			break;
		case (0x5C << 3) | 5:
			assert (false);
			break;
		case (0x5C << 3) | 6:
			assert (false);
			break;
		case (0x5C << 3) | 7:
			assert (false);
			break;

		/* EOR abs,X */
		case (0x5D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x5D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x5D << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x5D << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x5D << 3) | 4:
			c.A ^= getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x5D << 3) | 5:
			assert (false);
			break;
		case (0x5D << 3) | 6:
			assert (false);
			break;
		case (0x5D << 3) | 7:
			assert (false);
			break;

		/* LSR abs,X */
		case (0x5E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x5E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x5E << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x5E << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x5E << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x5E << 3) | 5:
			setD(lsr(c, c.AD));
			_WR();
			break;
		case (0x5E << 3) | 6:
			fetch();
			break;
		case (0x5E << 3) | 7:
			assert (false);
			break;

		/* SRE abs,X (undoc) */
		case (0x5F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x5F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x5F << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x5F << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x5F << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x5F << 3) | 5:
			c.AD = lsr(c, c.AD);
			setD(c.AD);
			c.A ^= c.AD;
			_NZ(c.A);
			_WR();
			break;
		case (0x5F << 3) | 6:
			fetch();
			break;
		case (0x5F << 3) | 7:
			assert (false);
			break;

		/* RTS */
		case (0x60 << 3) | 0:
			setA(c.PC);
			break;
		case (0x60 << 3) | 1:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x60 << 3) | 2:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x60 << 3) | 3:
			setA(0x0100 | c.S);
			c.AD = getD();
			break;
		case (0x60 << 3) | 4:
			c.PC = (getD() << 8) | c.AD;
			setA(c.PC++);
			break;
		case (0x60 << 3) | 5:
			fetch();
			break;
		case (0x60 << 3) | 6:
			assert (false);
			break;
		case (0x60 << 3) | 7:
			assert (false);
			break;

		/* ADC (zp,X) */
		case (0x61 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x61 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x61 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x61 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x61 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x61 << 3) | 5:
			adc(c, getD());
			fetch();
			break;
		case (0x61 << 3) | 6:
			assert (false);
			break;
		case (0x61 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x62 << 3) | 0:
			setA(c.PC);
			break;
		case (0x62 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x62 << 3) | 2:
			assert (false);
			break;
		case (0x62 << 3) | 3:
			assert (false);
			break;
		case (0x62 << 3) | 4:
			assert (false);
			break;
		case (0x62 << 3) | 5:
			assert (false);
			break;
		case (0x62 << 3) | 6:
			assert (false);
			break;
		case (0x62 << 3) | 7:
			assert (false);
			break;

		/* RRA (zp,X) (undoc) */
		case (0x63 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x63 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x63 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x63 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x63 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0x63 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x63 << 3) | 6:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x63 << 3) | 7:
			fetch();
			break;

		/* NOP zp (undoc) */
		case (0x64 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x64 << 3) | 1:
			setA(getD());
			break;
		case (0x64 << 3) | 2:
			fetch();
			break;
		case (0x64 << 3) | 3:
			assert (false);
			break;
		case (0x64 << 3) | 4:
			assert (false);
			break;
		case (0x64 << 3) | 5:
			assert (false);
			break;
		case (0x64 << 3) | 6:
			assert (false);
			break;
		case (0x64 << 3) | 7:
			assert (false);
			break;

		/* ADC zp */
		case (0x65 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x65 << 3) | 1:
			setA(getD());
			break;
		case (0x65 << 3) | 2:
			adc(c, getD());
			fetch();
			break;
		case (0x65 << 3) | 3:
			assert (false);
			break;
		case (0x65 << 3) | 4:
			assert (false);
			break;
		case (0x65 << 3) | 5:
			assert (false);
			break;
		case (0x65 << 3) | 6:
			assert (false);
			break;
		case (0x65 << 3) | 7:
			assert (false);
			break;

		/* ROR zp */
		case (0x66 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x66 << 3) | 1:
			setA(getD());
			break;
		case (0x66 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x66 << 3) | 3:
			setD(ror(c, c.AD));
			_WR();
			break;
		case (0x66 << 3) | 4:
			fetch();
			break;
		case (0x66 << 3) | 5:
			assert (false);
			break;
		case (0x66 << 3) | 6:
			assert (false);
			break;
		case (0x66 << 3) | 7:
			assert (false);
			break;

		/* RRA zp (undoc) */
		case (0x67 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x67 << 3) | 1:
			setA(getD());
			break;
		case (0x67 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0x67 << 3) | 3:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x67 << 3) | 4:
			fetch();
			break;
		case (0x67 << 3) | 5:
			assert (false);
			break;
		case (0x67 << 3) | 6:
			assert (false);
			break;
		case (0x67 << 3) | 7:
			assert (false);
			break;

		/* PLA */
		case (0x68 << 3) | 0:
			setA(c.PC);
			break;
		case (0x68 << 3) | 1:
			setA(0x0100 | c.sp_inc());
			break;
		case (0x68 << 3) | 2:
			setA(0x0100 | c.S);
			break;
		case (0x68 << 3) | 3:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x68 << 3) | 4:
			assert (false);
			break;
		case (0x68 << 3) | 5:
			assert (false);
			break;
		case (0x68 << 3) | 6:
			assert (false);
			break;
		case (0x68 << 3) | 7:
			assert (false);
			break;

		/* ADC # */
		case (0x69 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x69 << 3) | 1:
			adc(c, getD());
			fetch();
			break;
		case (0x69 << 3) | 2:
			assert (false);
			break;
		case (0x69 << 3) | 3:
			assert (false);
			break;
		case (0x69 << 3) | 4:
			assert (false);
			break;
		case (0x69 << 3) | 5:
			assert (false);
			break;
		case (0x69 << 3) | 6:
			assert (false);
			break;
		case (0x69 << 3) | 7:
			assert (false);
			break;

		/* RORA */
		case (0x6A << 3) | 0:
			setA(c.PC);
			break;
		case (0x6A << 3) | 1:
			c.A = ror(c, c.A);
			fetch();
			break;
		case (0x6A << 3) | 2:
			assert (false);
			break;
		case (0x6A << 3) | 3:
			assert (false);
			break;
		case (0x6A << 3) | 4:
			assert (false);
			break;
		case (0x6A << 3) | 5:
			assert (false);
			break;
		case (0x6A << 3) | 6:
			assert (false);
			break;
		case (0x6A << 3) | 7:
			assert (false);
			break;

		/* ARR # (undoc) */
		case (0x6B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x6B << 3) | 1:
			c.A &= getD();
			arr(c);
			fetch();
			break;
		case (0x6B << 3) | 2:
			assert (false);
			break;
		case (0x6B << 3) | 3:
			assert (false);
			break;
		case (0x6B << 3) | 4:
			assert (false);
			break;
		case (0x6B << 3) | 5:
			assert (false);
			break;
		case (0x6B << 3) | 6:
			assert (false);
			break;
		case (0x6B << 3) | 7:
			assert (false);
			break;

		/* JMPI */
		case (0x6C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x6C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x6C << 3) | 2:
			c.AD |= getD() << 8;
			setA(c.AD);
			break;
		case (0x6C << 3) | 3:
			setA((c.AD & 0xFF00) | ((c.AD + 1) & 0x00FF));
			c.AD = getD();
			break;
		case (0x6C << 3) | 4:
			c.PC = (getD() << 8) | c.AD;
			fetch();
			break;
		case (0x6C << 3) | 5:
			assert (false);
			break;
		case (0x6C << 3) | 6:
			assert (false);
			break;
		case (0x6C << 3) | 7:
			assert (false);
			break;

		/* ADC abs */
		case (0x6D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x6D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x6D << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x6D << 3) | 3:
			adc(c, getD());
			fetch();
			break;
		case (0x6D << 3) | 4:
			assert (false);
			break;
		case (0x6D << 3) | 5:
			assert (false);
			break;
		case (0x6D << 3) | 6:
			assert (false);
			break;
		case (0x6D << 3) | 7:
			assert (false);
			break;

		/* ROR abs */
		case (0x6E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x6E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x6E << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x6E << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x6E << 3) | 4:
			setD(ror(c, c.AD));
			_WR();
			break;
		case (0x6E << 3) | 5:
			fetch();
			break;
		case (0x6E << 3) | 6:
			assert (false);
			break;
		case (0x6E << 3) | 7:
			assert (false);
			break;

		/* RRA abs (undoc) */
		case (0x6F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x6F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x6F << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0x6F << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x6F << 3) | 4:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x6F << 3) | 5:
			fetch();
			break;
		case (0x6F << 3) | 6:
			assert (false);
			break;
		case (0x6F << 3) | 7:
			assert (false);
			break;

		/* BVS # */
		case (0x70 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x70 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x40) != 0x40) {
				fetch();
			}
			;
			break;
		case (0x70 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0x70 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0x70 << 3) | 4:
			assert (false);
			break;
		case (0x70 << 3) | 5:
			assert (false);
			break;
		case (0x70 << 3) | 6:
			assert (false);
			break;
		case (0x70 << 3) | 7:
			assert (false);
			break;

		/* ADC (zp),Y */
		case (0x71 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x71 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x71 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x71 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x71 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x71 << 3) | 5:
			adc(c, getD());
			fetch();
			break;
		case (0x71 << 3) | 6:
			assert (false);
			break;
		case (0x71 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x72 << 3) | 0:
			setA(c.PC);
			break;
		case (0x72 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x72 << 3) | 2:
			assert (false);
			break;
		case (0x72 << 3) | 3:
			assert (false);
			break;
		case (0x72 << 3) | 4:
			assert (false);
			break;
		case (0x72 << 3) | 5:
			assert (false);
			break;
		case (0x72 << 3) | 6:
			assert (false);
			break;
		case (0x72 << 3) | 7:
			assert (false);
			break;

		/* RRA (zp),Y (undoc) */
		case (0x73 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x73 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x73 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x73 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x73 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0x73 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0x73 << 3) | 6:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x73 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0x74 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x74 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x74 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x74 << 3) | 3:
			fetch();
			break;
		case (0x74 << 3) | 4:
			assert (false);
			break;
		case (0x74 << 3) | 5:
			assert (false);
			break;
		case (0x74 << 3) | 6:
			assert (false);
			break;
		case (0x74 << 3) | 7:
			assert (false);
			break;

		/* ADC zp,X */
		case (0x75 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x75 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x75 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x75 << 3) | 3:
			adc(c, getD());
			fetch();
			break;
		case (0x75 << 3) | 4:
			assert (false);
			break;
		case (0x75 << 3) | 5:
			assert (false);
			break;
		case (0x75 << 3) | 6:
			assert (false);
			break;
		case (0x75 << 3) | 7:
			assert (false);
			break;

		/* ROR zp,X */
		case (0x76 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x76 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x76 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x76 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x76 << 3) | 4:
			setD(ror(c, c.AD));
			_WR();
			break;
		case (0x76 << 3) | 5:
			fetch();
			break;
		case (0x76 << 3) | 6:
			assert (false);
			break;
		case (0x76 << 3) | 7:
			assert (false);
			break;

		/* RRA zp,X (undoc) */
		case (0x77 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x77 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x77 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0x77 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0x77 << 3) | 4:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x77 << 3) | 5:
			fetch();
			break;
		case (0x77 << 3) | 6:
			assert (false);
			break;
		case (0x77 << 3) | 7:
			assert (false);
			break;

		/* SEI */
		case (0x78 << 3) | 0:
			setA(c.PC);
			break;
		case (0x78 << 3) | 1:
			c.P |= 0x4;
			fetch();
			break;
		case (0x78 << 3) | 2:
			assert (false);
			break;
		case (0x78 << 3) | 3:
			assert (false);
			break;
		case (0x78 << 3) | 4:
			assert (false);
			break;
		case (0x78 << 3) | 5:
			assert (false);
			break;
		case (0x78 << 3) | 6:
			assert (false);
			break;
		case (0x78 << 3) | 7:
			assert (false);
			break;

		/* ADC abs,Y */
		case (0x79 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x79 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x79 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0x79 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x79 << 3) | 4:
			adc(c, getD());
			fetch();
			break;
		case (0x79 << 3) | 5:
			assert (false);
			break;
		case (0x79 << 3) | 6:
			assert (false);
			break;
		case (0x79 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0x7A << 3) | 0:
			setA(c.PC);
			break;
		case (0x7A << 3) | 1:
			fetch();
			break;
		case (0x7A << 3) | 2:
			assert (false);
			break;
		case (0x7A << 3) | 3:
			assert (false);
			break;
		case (0x7A << 3) | 4:
			assert (false);
			break;
		case (0x7A << 3) | 5:
			assert (false);
			break;
		case (0x7A << 3) | 6:
			assert (false);
			break;
		case (0x7A << 3) | 7:
			assert (false);
			break;

		/* RRA abs,Y (undoc) */
		case (0x7B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x7B << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x7B << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x7B << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0x7B << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x7B << 3) | 5:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x7B << 3) | 6:
			fetch();
			break;
		case (0x7B << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0x7C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x7C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x7C << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x7C << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x7C << 3) | 4:
			fetch();
			break;
		case (0x7C << 3) | 5:
			assert (false);
			break;
		case (0x7C << 3) | 6:
			assert (false);
			break;
		case (0x7C << 3) | 7:
			assert (false);
			break;

		/* ADC abs,X */
		case (0x7D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x7D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x7D << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0x7D << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x7D << 3) | 4:
			adc(c, getD());
			fetch();
			break;
		case (0x7D << 3) | 5:
			assert (false);
			break;
		case (0x7D << 3) | 6:
			assert (false);
			break;
		case (0x7D << 3) | 7:
			assert (false);
			break;

		/* ROR abs,X */
		case (0x7E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x7E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x7E << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x7E << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x7E << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x7E << 3) | 5:
			setD(ror(c, c.AD));
			_WR();
			break;
		case (0x7E << 3) | 6:
			fetch();
			break;
		case (0x7E << 3) | 7:
			assert (false);
			break;

		/* RRA abs,X (undoc) */
		case (0x7F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x7F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x7F << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x7F << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0x7F << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0x7F << 3) | 5:
			c.AD = ror(c, c.AD);
			setD(c.AD);
			adc(c, c.AD);
			_WR();
			break;
		case (0x7F << 3) | 6:
			fetch();
			break;
		case (0x7F << 3) | 7:
			assert (false);
			break;

		/* NOP # (undoc) */
		case (0x80 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x80 << 3) | 1:
			fetch();
			break;
		case (0x80 << 3) | 2:
			assert (false);
			break;
		case (0x80 << 3) | 3:
			assert (false);
			break;
		case (0x80 << 3) | 4:
			assert (false);
			break;
		case (0x80 << 3) | 5:
			assert (false);
			break;
		case (0x80 << 3) | 6:
			assert (false);
			break;
		case (0x80 << 3) | 7:
			assert (false);
			break;

		/* STA (zp,X) */
		case (0x81 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x81 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x81 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x81 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x81 << 3) | 4:
			setA((getD() << 8) | c.AD);
			setD(c.A);
			_WR();
			break;
		case (0x81 << 3) | 5:
			fetch();
			break;
		case (0x81 << 3) | 6:
			assert (false);
			break;
		case (0x81 << 3) | 7:
			assert (false);
			break;

		/* NOP # (undoc) */
		case (0x82 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x82 << 3) | 1:
			fetch();
			break;
		case (0x82 << 3) | 2:
			assert (false);
			break;
		case (0x82 << 3) | 3:
			assert (false);
			break;
		case (0x82 << 3) | 4:
			assert (false);
			break;
		case (0x82 << 3) | 5:
			assert (false);
			break;
		case (0x82 << 3) | 6:
			assert (false);
			break;
		case (0x82 << 3) | 7:
			assert (false);
			break;

		/* SAX (zp,X) (undoc) */
		case (0x83 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x83 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x83 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0x83 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x83 << 3) | 4:
			setA((getD() << 8) | c.AD);
			setD(c.A & c.X);
			_WR();
			break;
		case (0x83 << 3) | 5:
			fetch();
			break;
		case (0x83 << 3) | 6:
			assert (false);
			break;
		case (0x83 << 3) | 7:
			assert (false);
			break;

		/* STY zp */
		case (0x84 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x84 << 3) | 1:
			setA(getD());
			setD(c.Y);
			_WR();
			break;
		case (0x84 << 3) | 2:
			fetch();
			break;
		case (0x84 << 3) | 3:
			assert (false);
			break;
		case (0x84 << 3) | 4:
			assert (false);
			break;
		case (0x84 << 3) | 5:
			assert (false);
			break;
		case (0x84 << 3) | 6:
			assert (false);
			break;
		case (0x84 << 3) | 7:
			assert (false);
			break;

		/* STA zp */
		case (0x85 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x85 << 3) | 1:
			setA(getD());
			setD(c.A);
			_WR();
			break;
		case (0x85 << 3) | 2:
			fetch();
			break;
		case (0x85 << 3) | 3:
			assert (false);
			break;
		case (0x85 << 3) | 4:
			assert (false);
			break;
		case (0x85 << 3) | 5:
			assert (false);
			break;
		case (0x85 << 3) | 6:
			assert (false);
			break;
		case (0x85 << 3) | 7:
			assert (false);
			break;

		/* STX zp */
		case (0x86 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x86 << 3) | 1:
			setA(getD());
			setD(c.X);
			_WR();
			break;
		case (0x86 << 3) | 2:
			fetch();
			break;
		case (0x86 << 3) | 3:
			assert (false);
			break;
		case (0x86 << 3) | 4:
			assert (false);
			break;
		case (0x86 << 3) | 5:
			assert (false);
			break;
		case (0x86 << 3) | 6:
			assert (false);
			break;
		case (0x86 << 3) | 7:
			assert (false);
			break;

		/* SAX zp (undoc) */
		case (0x87 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x87 << 3) | 1:
			setA(getD());
			setD(c.A & c.X);
			_WR();
			break;
		case (0x87 << 3) | 2:
			fetch();
			break;
		case (0x87 << 3) | 3:
			assert (false);
			break;
		case (0x87 << 3) | 4:
			assert (false);
			break;
		case (0x87 << 3) | 5:
			assert (false);
			break;
		case (0x87 << 3) | 6:
			assert (false);
			break;
		case (0x87 << 3) | 7:
			assert (false);
			break;

		/* DEY */
		case (0x88 << 3) | 0:
			setA(c.PC);
			break;
		case (0x88 << 3) | 1:
			c.Y = (c.Y - 1) & 0xff;
			_NZ(c.Y);
			fetch();
			break;
		case (0x88 << 3) | 2:
			assert (false);
			break;
		case (0x88 << 3) | 3:
			assert (false);
			break;
		case (0x88 << 3) | 4:
			assert (false);
			break;
		case (0x88 << 3) | 5:
			assert (false);
			break;
		case (0x88 << 3) | 6:
			assert (false);
			break;
		case (0x88 << 3) | 7:
			assert (false);
			break;

		/* NOP # (undoc) */
		case (0x89 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x89 << 3) | 1:
			fetch();
			break;
		case (0x89 << 3) | 2:
			assert (false);
			break;
		case (0x89 << 3) | 3:
			assert (false);
			break;
		case (0x89 << 3) | 4:
			assert (false);
			break;
		case (0x89 << 3) | 5:
			assert (false);
			break;
		case (0x89 << 3) | 6:
			assert (false);
			break;
		case (0x89 << 3) | 7:
			assert (false);
			break;

		/* TXA */
		case (0x8A << 3) | 0:
			setA(c.PC);
			break;
		case (0x8A << 3) | 1:
			c.A = c.X;
			_NZ(c.A);
			fetch();
			break;
		case (0x8A << 3) | 2:
			assert (false);
			break;
		case (0x8A << 3) | 3:
			assert (false);
			break;
		case (0x8A << 3) | 4:
			assert (false);
			break;
		case (0x8A << 3) | 5:
			assert (false);
			break;
		case (0x8A << 3) | 6:
			assert (false);
			break;
		case (0x8A << 3) | 7:
			assert (false);
			break;

		/* ANE # (undoc) */
		case (0x8B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x8B << 3) | 1:
			c.A = (c.A | 0xEE) & c.X & getD();
			_NZ(c.A);
			fetch();
			break;
		case (0x8B << 3) | 2:
			assert (false);
			break;
		case (0x8B << 3) | 3:
			assert (false);
			break;
		case (0x8B << 3) | 4:
			assert (false);
			break;
		case (0x8B << 3) | 5:
			assert (false);
			break;
		case (0x8B << 3) | 6:
			assert (false);
			break;
		case (0x8B << 3) | 7:
			assert (false);
			break;

		/* STY abs */
		case (0x8C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x8C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x8C << 3) | 2:
			setA((getD() << 8) | c.AD);
			setD(c.Y);
			_WR();
			break;
		case (0x8C << 3) | 3:
			fetch();
			break;
		case (0x8C << 3) | 4:
			assert (false);
			break;
		case (0x8C << 3) | 5:
			assert (false);
			break;
		case (0x8C << 3) | 6:
			assert (false);
			break;
		case (0x8C << 3) | 7:
			assert (false);
			break;

		/* STA abs */
		case (0x8D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x8D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x8D << 3) | 2:
			setA((getD() << 8) | c.AD);
			setD(c.A);
			_WR();
			break;
		case (0x8D << 3) | 3:
			fetch();
			break;
		case (0x8D << 3) | 4:
			assert (false);
			break;
		case (0x8D << 3) | 5:
			assert (false);
			break;
		case (0x8D << 3) | 6:
			assert (false);
			break;
		case (0x8D << 3) | 7:
			assert (false);
			break;

		/* STX abs */
		case (0x8E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x8E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x8E << 3) | 2:
			setA((getD() << 8) | c.AD);
			setD(c.X);
			_WR();
			break;
		case (0x8E << 3) | 3:
			fetch();
			break;
		case (0x8E << 3) | 4:
			assert (false);
			break;
		case (0x8E << 3) | 5:
			assert (false);
			break;
		case (0x8E << 3) | 6:
			assert (false);
			break;
		case (0x8E << 3) | 7:
			assert (false);
			break;

		/* SAX abs (undoc) */
		case (0x8F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x8F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x8F << 3) | 2:
			setA((getD() << 8) | c.AD);
			setD(c.A & c.X);
			_WR();
			break;
		case (0x8F << 3) | 3:
			fetch();
			break;
		case (0x8F << 3) | 4:
			assert (false);
			break;
		case (0x8F << 3) | 5:
			assert (false);
			break;
		case (0x8F << 3) | 6:
			assert (false);
			break;
		case (0x8F << 3) | 7:
			assert (false);
			break;

		/* BCC # */
		case (0x90 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x90 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x1) != 0x0) {
				fetch();
			}
			;
			break;
		case (0x90 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0x90 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0x90 << 3) | 4:
			assert (false);
			break;
		case (0x90 << 3) | 5:
			assert (false);
			break;
		case (0x90 << 3) | 6:
			assert (false);
			break;
		case (0x90 << 3) | 7:
			assert (false);
			break;

		/* STA (zp),Y */
		case (0x91 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x91 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x91 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x91 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x91 << 3) | 4:
			setA(c.AD + c.Y);
			setD(c.A);
			_WR();
			break;
		case (0x91 << 3) | 5:
			fetch();
			break;
		case (0x91 << 3) | 6:
			assert (false);
			break;
		case (0x91 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0x92 << 3) | 0:
			setA(c.PC);
			break;
		case (0x92 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0x92 << 3) | 2:
			assert (false);
			break;
		case (0x92 << 3) | 3:
			assert (false);
			break;
		case (0x92 << 3) | 4:
			assert (false);
			break;
		case (0x92 << 3) | 5:
			assert (false);
			break;
		case (0x92 << 3) | 6:
			assert (false);
			break;
		case (0x92 << 3) | 7:
			assert (false);
			break;

		/* SHA (zp),Y (undoc) */
		case (0x93 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x93 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x93 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0x93 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x93 << 3) | 4:
			setA(c.AD + c.Y);
			setD(c.A & c.X & (int) ((getA() >> 8) + 1));
			_WR();
			break;
		case (0x93 << 3) | 5:
			fetch();
			break;
		case (0x93 << 3) | 6:
			assert (false);
			break;
		case (0x93 << 3) | 7:
			assert (false);
			break;

		/* STY zp,X */
		case (0x94 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x94 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x94 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			setD(c.Y);
			_WR();
			break;
		case (0x94 << 3) | 3:
			fetch();
			break;
		case (0x94 << 3) | 4:
			assert (false);
			break;
		case (0x94 << 3) | 5:
			assert (false);
			break;
		case (0x94 << 3) | 6:
			assert (false);
			break;
		case (0x94 << 3) | 7:
			assert (false);
			break;

		/* STA zp,X */
		case (0x95 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x95 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x95 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			setD(c.A);
			_WR();
			break;
		case (0x95 << 3) | 3:
			fetch();
			break;
		case (0x95 << 3) | 4:
			assert (false);
			break;
		case (0x95 << 3) | 5:
			assert (false);
			break;
		case (0x95 << 3) | 6:
			assert (false);
			break;
		case (0x95 << 3) | 7:
			assert (false);
			break;

		/* STX zp,Y */
		case (0x96 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x96 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x96 << 3) | 2:
			setA((c.AD + c.Y) & 0x00FF);
			setD(c.X);
			_WR();
			break;
		case (0x96 << 3) | 3:
			fetch();
			break;
		case (0x96 << 3) | 4:
			assert (false);
			break;
		case (0x96 << 3) | 5:
			assert (false);
			break;
		case (0x96 << 3) | 6:
			assert (false);
			break;
		case (0x96 << 3) | 7:
			assert (false);
			break;

		/* SAX zp,Y (undoc) */
		case (0x97 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x97 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0x97 << 3) | 2:
			setA((c.AD + c.Y) & 0x00FF);
			setD(c.A & c.X);
			_WR();
			break;
		case (0x97 << 3) | 3:
			fetch();
			break;
		case (0x97 << 3) | 4:
			assert (false);
			break;
		case (0x97 << 3) | 5:
			assert (false);
			break;
		case (0x97 << 3) | 6:
			assert (false);
			break;
		case (0x97 << 3) | 7:
			assert (false);
			break;

		/* TYA */
		case (0x98 << 3) | 0:
			setA(c.PC);
			break;
		case (0x98 << 3) | 1:
			c.A = c.Y;
			_NZ(c.A);
			fetch();
			break;
		case (0x98 << 3) | 2:
			assert (false);
			break;
		case (0x98 << 3) | 3:
			assert (false);
			break;
		case (0x98 << 3) | 4:
			assert (false);
			break;
		case (0x98 << 3) | 5:
			assert (false);
			break;
		case (0x98 << 3) | 6:
			assert (false);
			break;
		case (0x98 << 3) | 7:
			assert (false);
			break;

		/* STA abs,Y */
		case (0x99 << 3) | 0:
			setA(c.PC++);
			break;
		case (0x99 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x99 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x99 << 3) | 3:
			setA(c.AD + c.Y);
			setD(c.A);
			_WR();
			break;
		case (0x99 << 3) | 4:
			fetch();
			break;
		case (0x99 << 3) | 5:
			assert (false);
			break;
		case (0x99 << 3) | 6:
			assert (false);
			break;
		case (0x99 << 3) | 7:
			assert (false);
			break;

		/* TXS */
		case (0x9A << 3) | 0:
			setA(c.PC);
			break;
		case (0x9A << 3) | 1:
			c.S = c.X;
			fetch();
			break;
		case (0x9A << 3) | 2:
			assert (false);
			break;
		case (0x9A << 3) | 3:
			assert (false);
			break;
		case (0x9A << 3) | 4:
			assert (false);
			break;
		case (0x9A << 3) | 5:
			assert (false);
			break;
		case (0x9A << 3) | 6:
			assert (false);
			break;
		case (0x9A << 3) | 7:
			assert (false);
			break;

		/* SHS abs,Y (undoc) */
		case (0x9B << 3) | 0:
			setA(c.PC++);
			break;
		case (0x9B << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x9B << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x9B << 3) | 3:
			setA(c.AD + c.Y);
			c.S = c.A & c.X;
			setD(c.S & (int) ((getA() >> 8) + 1));
			_WR();
			break;
		case (0x9B << 3) | 4:
			fetch();
			break;
		case (0x9B << 3) | 5:
			assert (false);
			break;
		case (0x9B << 3) | 6:
			assert (false);
			break;
		case (0x9B << 3) | 7:
			assert (false);
			break;

		/* SHY abs,X (undoc) */
		case (0x9C << 3) | 0:
			setA(c.PC++);
			break;
		case (0x9C << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x9C << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x9C << 3) | 3:
			setA(c.AD + c.X);
			setD(c.Y & (int) ((getA() >> 8) + 1));
			_WR();
			break;
		case (0x9C << 3) | 4:
			fetch();
			break;
		case (0x9C << 3) | 5:
			assert (false);
			break;
		case (0x9C << 3) | 6:
			assert (false);
			break;
		case (0x9C << 3) | 7:
			assert (false);
			break;

		/* STA abs,X */
		case (0x9D << 3) | 0:
			setA(c.PC++);
			break;
		case (0x9D << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x9D << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0x9D << 3) | 3:
			setA(c.AD + c.X);
			setD(c.A);
			_WR();
			break;
		case (0x9D << 3) | 4:
			fetch();
			break;
		case (0x9D << 3) | 5:
			assert (false);
			break;
		case (0x9D << 3) | 6:
			assert (false);
			break;
		case (0x9D << 3) | 7:
			assert (false);
			break;

		/* SHX abs,Y (undoc) */
		case (0x9E << 3) | 0:
			setA(c.PC++);
			break;
		case (0x9E << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x9E << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x9E << 3) | 3:
			setA(c.AD + c.Y);
			setD(c.X & (int) ((getA() >> 8) + 1));
			_WR();
			break;
		case (0x9E << 3) | 4:
			fetch();
			break;
		case (0x9E << 3) | 5:
			assert (false);
			break;
		case (0x9E << 3) | 6:
			assert (false);
			break;
		case (0x9E << 3) | 7:
			assert (false);
			break;

		/* SHA abs,Y (undoc) */
		case (0x9F << 3) | 0:
			setA(c.PC++);
			break;
		case (0x9F << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0x9F << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0x9F << 3) | 3:
			setA(c.AD + c.Y);
			setD(c.A & c.X & (int) ((getA() >> 8) + 1));
			_WR();
			break;
		case (0x9F << 3) | 4:
			fetch();
			break;
		case (0x9F << 3) | 5:
			assert (false);
			break;
		case (0x9F << 3) | 6:
			assert (false);
			break;
		case (0x9F << 3) | 7:
			assert (false);
			break;

		/* LDY # */
		case (0xA0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA0 << 3) | 1:
			c.Y = getD();
			_NZ(c.Y);
			fetch();
			break;
		case (0xA0 << 3) | 2:
			assert (false);
			break;
		case (0xA0 << 3) | 3:
			assert (false);
			break;
		case (0xA0 << 3) | 4:
			assert (false);
			break;
		case (0xA0 << 3) | 5:
			assert (false);
			break;
		case (0xA0 << 3) | 6:
			assert (false);
			break;
		case (0xA0 << 3) | 7:
			assert (false);
			break;

		/* LDA (zp,X) */
		case (0xA1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xA1 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xA1 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xA1 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xA1 << 3) | 5:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xA1 << 3) | 6:
			assert (false);
			break;
		case (0xA1 << 3) | 7:
			assert (false);
			break;

		/* LDX # */
		case (0xA2 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA2 << 3) | 1:
			c.X = getD();
			_NZ(c.X);
			fetch();
			break;
		case (0xA2 << 3) | 2:
			assert (false);
			break;
		case (0xA2 << 3) | 3:
			assert (false);
			break;
		case (0xA2 << 3) | 4:
			assert (false);
			break;
		case (0xA2 << 3) | 5:
			assert (false);
			break;
		case (0xA2 << 3) | 6:
			assert (false);
			break;
		case (0xA2 << 3) | 7:
			assert (false);
			break;

		/* LAX (zp,X) (undoc) */
		case (0xA3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xA3 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xA3 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xA3 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xA3 << 3) | 5:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xA3 << 3) | 6:
			assert (false);
			break;
		case (0xA3 << 3) | 7:
			assert (false);
			break;

		/* LDY zp */
		case (0xA4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA4 << 3) | 1:
			setA(getD());
			break;
		case (0xA4 << 3) | 2:
			c.Y = getD();
			_NZ(c.Y);
			fetch();
			break;
		case (0xA4 << 3) | 3:
			assert (false);
			break;
		case (0xA4 << 3) | 4:
			assert (false);
			break;
		case (0xA4 << 3) | 5:
			assert (false);
			break;
		case (0xA4 << 3) | 6:
			assert (false);
			break;
		case (0xA4 << 3) | 7:
			assert (false);
			break;

		/* LDA zp */
		case (0xA5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA5 << 3) | 1:
			setA(getD());
			break;
		case (0xA5 << 3) | 2:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xA5 << 3) | 3:
			assert (false);
			break;
		case (0xA5 << 3) | 4:
			assert (false);
			break;
		case (0xA5 << 3) | 5:
			assert (false);
			break;
		case (0xA5 << 3) | 6:
			assert (false);
			break;
		case (0xA5 << 3) | 7:
			assert (false);
			break;

		/* LDX zp */
		case (0xA6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA6 << 3) | 1:
			setA(getD());
			break;
		case (0xA6 << 3) | 2:
			c.X = getD();
			_NZ(c.X);
			fetch();
			break;
		case (0xA6 << 3) | 3:
			assert (false);
			break;
		case (0xA6 << 3) | 4:
			assert (false);
			break;
		case (0xA6 << 3) | 5:
			assert (false);
			break;
		case (0xA6 << 3) | 6:
			assert (false);
			break;
		case (0xA6 << 3) | 7:
			assert (false);
			break;

		/* LAX zp (undoc) */
		case (0xA7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA7 << 3) | 1:
			setA(getD());
			break;
		case (0xA7 << 3) | 2:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xA7 << 3) | 3:
			assert (false);
			break;
		case (0xA7 << 3) | 4:
			assert (false);
			break;
		case (0xA7 << 3) | 5:
			assert (false);
			break;
		case (0xA7 << 3) | 6:
			assert (false);
			break;
		case (0xA7 << 3) | 7:
			assert (false);
			break;

		/* TAY */
		case (0xA8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xA8 << 3) | 1:
			c.Y = c.A;
			_NZ(c.Y);
			fetch();
			break;
		case (0xA8 << 3) | 2:
			assert (false);
			break;
		case (0xA8 << 3) | 3:
			assert (false);
			break;
		case (0xA8 << 3) | 4:
			assert (false);
			break;
		case (0xA8 << 3) | 5:
			assert (false);
			break;
		case (0xA8 << 3) | 6:
			assert (false);
			break;
		case (0xA8 << 3) | 7:
			assert (false);
			break;

		/* LDA # */
		case (0xA9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xA9 << 3) | 1:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xA9 << 3) | 2:
			assert (false);
			break;
		case (0xA9 << 3) | 3:
			assert (false);
			break;
		case (0xA9 << 3) | 4:
			assert (false);
			break;
		case (0xA9 << 3) | 5:
			assert (false);
			break;
		case (0xA9 << 3) | 6:
			assert (false);
			break;
		case (0xA9 << 3) | 7:
			assert (false);
			break;

		/* TAX */
		case (0xAA << 3) | 0:
			setA(c.PC);
			break;
		case (0xAA << 3) | 1:
			c.X = c.A;
			_NZ(c.X);
			fetch();
			break;
		case (0xAA << 3) | 2:
			assert (false);
			break;
		case (0xAA << 3) | 3:
			assert (false);
			break;
		case (0xAA << 3) | 4:
			assert (false);
			break;
		case (0xAA << 3) | 5:
			assert (false);
			break;
		case (0xAA << 3) | 6:
			assert (false);
			break;
		case (0xAA << 3) | 7:
			assert (false);
			break;

		/* LXA # (undoc) */
		case (0xAB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xAB << 3) | 1:
			c.A = c.X = (c.A | 0xEE) & getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xAB << 3) | 2:
			assert (false);
			break;
		case (0xAB << 3) | 3:
			assert (false);
			break;
		case (0xAB << 3) | 4:
			assert (false);
			break;
		case (0xAB << 3) | 5:
			assert (false);
			break;
		case (0xAB << 3) | 6:
			assert (false);
			break;
		case (0xAB << 3) | 7:
			assert (false);
			break;

		/* LDY abs */
		case (0xAC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xAC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xAC << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xAC << 3) | 3:
			c.Y = getD();
			_NZ(c.Y);
			fetch();
			break;
		case (0xAC << 3) | 4:
			assert (false);
			break;
		case (0xAC << 3) | 5:
			assert (false);
			break;
		case (0xAC << 3) | 6:
			assert (false);
			break;
		case (0xAC << 3) | 7:
			assert (false);
			break;

		/* LDA abs */
		case (0xAD << 3) | 0:
			setA(c.PC++);
			break;
		case (0xAD << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xAD << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xAD << 3) | 3:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xAD << 3) | 4:
			assert (false);
			break;
		case (0xAD << 3) | 5:
			assert (false);
			break;
		case (0xAD << 3) | 6:
			assert (false);
			break;
		case (0xAD << 3) | 7:
			assert (false);
			break;

		/* LDX abs */
		case (0xAE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xAE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xAE << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xAE << 3) | 3:
			c.X = getD();
			_NZ(c.X);
			fetch();
			break;
		case (0xAE << 3) | 4:
			assert (false);
			break;
		case (0xAE << 3) | 5:
			assert (false);
			break;
		case (0xAE << 3) | 6:
			assert (false);
			break;
		case (0xAE << 3) | 7:
			assert (false);
			break;

		/* LAX abs (undoc) */
		case (0xAF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xAF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xAF << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xAF << 3) | 3:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xAF << 3) | 4:
			assert (false);
			break;
		case (0xAF << 3) | 5:
			assert (false);
			break;
		case (0xAF << 3) | 6:
			assert (false);
			break;
		case (0xAF << 3) | 7:
			assert (false);
			break;

		/* BCS # */
		case (0xB0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB0 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x1) != 0x1) {
				fetch();
			}
			;
			break;
		case (0xB0 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0xB0 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0xB0 << 3) | 4:
			assert (false);
			break;
		case (0xB0 << 3) | 5:
			assert (false);
			break;
		case (0xB0 << 3) | 6:
			assert (false);
			break;
		case (0xB0 << 3) | 7:
			assert (false);
			break;

		/* LDA (zp),Y */
		case (0xB1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB1 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xB1 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xB1 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xB1 << 3) | 5:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xB1 << 3) | 6:
			assert (false);
			break;
		case (0xB1 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0xB2 << 3) | 0:
			setA(c.PC);
			break;
		case (0xB2 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0xB2 << 3) | 2:
			assert (false);
			break;
		case (0xB2 << 3) | 3:
			assert (false);
			break;
		case (0xB2 << 3) | 4:
			assert (false);
			break;
		case (0xB2 << 3) | 5:
			assert (false);
			break;
		case (0xB2 << 3) | 6:
			assert (false);
			break;
		case (0xB2 << 3) | 7:
			assert (false);
			break;

		/* LAX (zp),Y (undoc) */
		case (0xB3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB3 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xB3 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xB3 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xB3 << 3) | 5:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xB3 << 3) | 6:
			assert (false);
			break;
		case (0xB3 << 3) | 7:
			assert (false);
			break;

		/* LDY zp,X */
		case (0xB4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB4 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB4 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xB4 << 3) | 3:
			c.Y = getD();
			_NZ(c.Y);
			fetch();
			break;
		case (0xB4 << 3) | 4:
			assert (false);
			break;
		case (0xB4 << 3) | 5:
			assert (false);
			break;
		case (0xB4 << 3) | 6:
			assert (false);
			break;
		case (0xB4 << 3) | 7:
			assert (false);
			break;

		/* LDA zp,X */
		case (0xB5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB5 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB5 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xB5 << 3) | 3:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xB5 << 3) | 4:
			assert (false);
			break;
		case (0xB5 << 3) | 5:
			assert (false);
			break;
		case (0xB5 << 3) | 6:
			assert (false);
			break;
		case (0xB5 << 3) | 7:
			assert (false);
			break;

		/* LDX zp,Y */
		case (0xB6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB6 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB6 << 3) | 2:
			setA((c.AD + c.Y) & 0x00FF);
			break;
		case (0xB6 << 3) | 3:
			c.X = getD();
			_NZ(c.X);
			fetch();
			break;
		case (0xB6 << 3) | 4:
			assert (false);
			break;
		case (0xB6 << 3) | 5:
			assert (false);
			break;
		case (0xB6 << 3) | 6:
			assert (false);
			break;
		case (0xB6 << 3) | 7:
			assert (false);
			break;

		/* LAX zp,Y (undoc) */
		case (0xB7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB7 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xB7 << 3) | 2:
			setA((c.AD + c.Y) & 0x00FF);
			break;
		case (0xB7 << 3) | 3:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xB7 << 3) | 4:
			assert (false);
			break;
		case (0xB7 << 3) | 5:
			assert (false);
			break;
		case (0xB7 << 3) | 6:
			assert (false);
			break;
		case (0xB7 << 3) | 7:
			assert (false);
			break;

		/* CLV */
		case (0xB8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xB8 << 3) | 1:
			c.P &= ~0x40;
			fetch();
			break;
		case (0xB8 << 3) | 2:
			assert (false);
			break;
		case (0xB8 << 3) | 3:
			assert (false);
			break;
		case (0xB8 << 3) | 4:
			assert (false);
			break;
		case (0xB8 << 3) | 5:
			assert (false);
			break;
		case (0xB8 << 3) | 6:
			assert (false);
			break;
		case (0xB8 << 3) | 7:
			assert (false);
			break;

		/* LDA abs,Y */
		case (0xB9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xB9 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xB9 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xB9 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xB9 << 3) | 4:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xB9 << 3) | 5:
			assert (false);
			break;
		case (0xB9 << 3) | 6:
			assert (false);
			break;
		case (0xB9 << 3) | 7:
			assert (false);
			break;

		/* TSX */
		case (0xBA << 3) | 0:
			setA(c.PC);
			break;
		case (0xBA << 3) | 1:
			c.X = (c.S & 0xff);
			_NZ(c.X);
			fetch();
			break;
		case (0xBA << 3) | 2:
			assert (false);
			break;
		case (0xBA << 3) | 3:
			assert (false);
			break;
		case (0xBA << 3) | 4:
			assert (false);
			break;
		case (0xBA << 3) | 5:
			assert (false);
			break;
		case (0xBA << 3) | 6:
			assert (false);
			break;
		case (0xBA << 3) | 7:
			assert (false);
			break;

		/* LAS abs,Y (undoc) */
		case (0xBB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xBB << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xBB << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xBB << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xBB << 3) | 4:
			c.A = c.X = c.S = getD() & c.S;
			_NZ(c.A);
			fetch();
			break;
		case (0xBB << 3) | 5:
			assert (false);
			break;
		case (0xBB << 3) | 6:
			assert (false);
			break;
		case (0xBB << 3) | 7:
			assert (false);
			break;

		/* LDY abs,X */
		case (0xBC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xBC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xBC << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xBC << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xBC << 3) | 4:
			c.Y = getD();
			_NZ(c.Y);
			fetch();
			break;
		case (0xBC << 3) | 5:
			assert (false);
			break;
		case (0xBC << 3) | 6:
			assert (false);
			break;
		case (0xBC << 3) | 7:
			assert (false);
			break;

		/* LDA abs,X */
		case (0xBD << 3) | 0:
			setA(c.PC++);
			break;
		case (0xBD << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xBD << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xBD << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xBD << 3) | 4:
			c.A = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xBD << 3) | 5:
			assert (false);
			break;
		case (0xBD << 3) | 6:
			assert (false);
			break;
		case (0xBD << 3) | 7:
			assert (false);
			break;

		/* LDX abs,Y */
		case (0xBE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xBE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xBE << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xBE << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xBE << 3) | 4:
			c.X = getD();
			_NZ(c.X);
			fetch();
			break;
		case (0xBE << 3) | 5:
			assert (false);
			break;
		case (0xBE << 3) | 6:
			assert (false);
			break;
		case (0xBE << 3) | 7:
			assert (false);
			break;

		/* LAX abs,Y (undoc) */
		case (0xBF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xBF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xBF << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xBF << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xBF << 3) | 4:
			c.A = c.X = getD();
			_NZ(c.A);
			fetch();
			break;
		case (0xBF << 3) | 5:
			assert (false);
			break;
		case (0xBF << 3) | 6:
			assert (false);
			break;
		case (0xBF << 3) | 7:
			assert (false);
			break;

		/* CPY # */
		case (0xC0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC0 << 3) | 1:
			cmp(c, c.Y, getD());
			fetch();
			break;
		case (0xC0 << 3) | 2:
			assert (false);
			break;
		case (0xC0 << 3) | 3:
			assert (false);
			break;
		case (0xC0 << 3) | 4:
			assert (false);
			break;
		case (0xC0 << 3) | 5:
			assert (false);
			break;
		case (0xC0 << 3) | 6:
			assert (false);
			break;
		case (0xC0 << 3) | 7:
			assert (false);
			break;

		/* CMP (zp,X) */
		case (0xC1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xC1 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xC1 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xC1 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xC1 << 3) | 5:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xC1 << 3) | 6:
			assert (false);
			break;
		case (0xC1 << 3) | 7:
			assert (false);
			break;

		/* NOP # (undoc) */
		case (0xC2 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC2 << 3) | 1:
			fetch();
			break;
		case (0xC2 << 3) | 2:
			assert (false);
			break;
		case (0xC2 << 3) | 3:
			assert (false);
			break;
		case (0xC2 << 3) | 4:
			assert (false);
			break;
		case (0xC2 << 3) | 5:
			assert (false);
			break;
		case (0xC2 << 3) | 6:
			assert (false);
			break;
		case (0xC2 << 3) | 7:
			assert (false);
			break;

		/* DCP (zp,X) (undoc) */
		case (0xC3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xC3 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xC3 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xC3 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xC3 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0xC3 << 3) | 6:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xC3 << 3) | 7:
			fetch();
			break;

		/* CPY zp */
		case (0xC4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC4 << 3) | 1:
			setA(getD());
			break;
		case (0xC4 << 3) | 2:
			cmp(c, c.Y, getD());
			fetch();
			break;
		case (0xC4 << 3) | 3:
			assert (false);
			break;
		case (0xC4 << 3) | 4:
			assert (false);
			break;
		case (0xC4 << 3) | 5:
			assert (false);
			break;
		case (0xC4 << 3) | 6:
			assert (false);
			break;
		case (0xC4 << 3) | 7:
			assert (false);
			break;

		/* CMP zp */
		case (0xC5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC5 << 3) | 1:
			setA(getD());
			break;
		case (0xC5 << 3) | 2:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xC5 << 3) | 3:
			assert (false);
			break;
		case (0xC5 << 3) | 4:
			assert (false);
			break;
		case (0xC5 << 3) | 5:
			assert (false);
			break;
		case (0xC5 << 3) | 6:
			assert (false);
			break;
		case (0xC5 << 3) | 7:
			assert (false);
			break;

		/* DEC zp */
		case (0xC6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC6 << 3) | 1:
			setA(getD());
			break;
		case (0xC6 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0xC6 << 3) | 3:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xC6 << 3) | 4:
			fetch();
			break;
		case (0xC6 << 3) | 5:
			assert (false);
			break;
		case (0xC6 << 3) | 6:
			assert (false);
			break;
		case (0xC6 << 3) | 7:
			assert (false);
			break;

		/* DCP zp (undoc) */
		case (0xC7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC7 << 3) | 1:
			setA(getD());
			break;
		case (0xC7 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0xC7 << 3) | 3:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xC7 << 3) | 4:
			fetch();
			break;
		case (0xC7 << 3) | 5:
			assert (false);
			break;
		case (0xC7 << 3) | 6:
			assert (false);
			break;
		case (0xC7 << 3) | 7:
			assert (false);
			break;

		/* INY */
		case (0xC8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xC8 << 3) | 1:
			c.Y = (c.Y + 1) & 0xff;
			_NZ(c.Y);
			fetch();
			break;
		case (0xC8 << 3) | 2:
			assert (false);
			break;
		case (0xC8 << 3) | 3:
			assert (false);
			break;
		case (0xC8 << 3) | 4:
			assert (false);
			break;
		case (0xC8 << 3) | 5:
			assert (false);
			break;
		case (0xC8 << 3) | 6:
			assert (false);
			break;
		case (0xC8 << 3) | 7:
			assert (false);
			break;

		/* CMP # */
		case (0xC9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xC9 << 3) | 1:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xC9 << 3) | 2:
			assert (false);
			break;
		case (0xC9 << 3) | 3:
			assert (false);
			break;
		case (0xC9 << 3) | 4:
			assert (false);
			break;
		case (0xC9 << 3) | 5:
			assert (false);
			break;
		case (0xC9 << 3) | 6:
			assert (false);
			break;
		case (0xC9 << 3) | 7:
			assert (false);
			break;

		/* DEX */
		case (0xCA << 3) | 0:
			setA(c.PC);
			break;
		case (0xCA << 3) | 1:
			c.X = (c.X - 1) & 0xff;
			_NZ(c.X);
			fetch();
			break;
		case (0xCA << 3) | 2:
			assert (false);
			break;
		case (0xCA << 3) | 3:
			assert (false);
			break;
		case (0xCA << 3) | 4:
			assert (false);
			break;
		case (0xCA << 3) | 5:
			assert (false);
			break;
		case (0xCA << 3) | 6:
			assert (false);
			break;
		case (0xCA << 3) | 7:
			assert (false);
			break;

		/* SBX # (undoc) */
		case (0xCB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xCB << 3) | 1:
			sbx(c, getD());
			fetch();
			break;
		case (0xCB << 3) | 2:
			assert (false);
			break;
		case (0xCB << 3) | 3:
			assert (false);
			break;
		case (0xCB << 3) | 4:
			assert (false);
			break;
		case (0xCB << 3) | 5:
			assert (false);
			break;
		case (0xCB << 3) | 6:
			assert (false);
			break;
		case (0xCB << 3) | 7:
			assert (false);
			break;

		/* CPY abs */
		case (0xCC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xCC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xCC << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xCC << 3) | 3:
			cmp(c, c.Y, getD());
			fetch();
			break;
		case (0xCC << 3) | 4:
			assert (false);
			break;
		case (0xCC << 3) | 5:
			assert (false);
			break;
		case (0xCC << 3) | 6:
			assert (false);
			break;
		case (0xCC << 3) | 7:
			assert (false);
			break;

		/* CMP abs */
		case (0xCD << 3) | 0:
			setA(c.PC++);
			break;
		case (0xCD << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xCD << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xCD << 3) | 3:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xCD << 3) | 4:
			assert (false);
			break;
		case (0xCD << 3) | 5:
			assert (false);
			break;
		case (0xCD << 3) | 6:
			assert (false);
			break;
		case (0xCD << 3) | 7:
			assert (false);
			break;

		/* DEC abs */
		case (0xCE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xCE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xCE << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xCE << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xCE << 3) | 4:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xCE << 3) | 5:
			fetch();
			break;
		case (0xCE << 3) | 6:
			assert (false);
			break;
		case (0xCE << 3) | 7:
			assert (false);
			break;

		/* DCP abs (undoc) */
		case (0xCF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xCF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xCF << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xCF << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xCF << 3) | 4:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xCF << 3) | 5:
			fetch();
			break;
		case (0xCF << 3) | 6:
			assert (false);
			break;
		case (0xCF << 3) | 7:
			assert (false);
			break;

		/* BNE # */
		case (0xD0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD0 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x2) != 0x0) {
				fetch();
			}
			;
			break;
		case (0xD0 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0xD0 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0xD0 << 3) | 4:
			assert (false);
			break;
		case (0xD0 << 3) | 5:
			assert (false);
			break;
		case (0xD0 << 3) | 6:
			assert (false);
			break;
		case (0xD0 << 3) | 7:
			assert (false);
			break;

		/* CMP (zp),Y */
		case (0xD1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD1 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xD1 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xD1 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xD1 << 3) | 5:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xD1 << 3) | 6:
			assert (false);
			break;
		case (0xD1 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0xD2 << 3) | 0:
			setA(c.PC);
			break;
		case (0xD2 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0xD2 << 3) | 2:
			assert (false);
			break;
		case (0xD2 << 3) | 3:
			assert (false);
			break;
		case (0xD2 << 3) | 4:
			assert (false);
			break;
		case (0xD2 << 3) | 5:
			assert (false);
			break;
		case (0xD2 << 3) | 6:
			assert (false);
			break;
		case (0xD2 << 3) | 7:
			assert (false);
			break;

		/* DCP (zp),Y (undoc) */
		case (0xD3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD3 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xD3 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0xD3 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xD3 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0xD3 << 3) | 6:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xD3 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0xD4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD4 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD4 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xD4 << 3) | 3:
			fetch();
			break;
		case (0xD4 << 3) | 4:
			assert (false);
			break;
		case (0xD4 << 3) | 5:
			assert (false);
			break;
		case (0xD4 << 3) | 6:
			assert (false);
			break;
		case (0xD4 << 3) | 7:
			assert (false);
			break;

		/* CMP zp,X */
		case (0xD5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD5 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD5 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xD5 << 3) | 3:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xD5 << 3) | 4:
			assert (false);
			break;
		case (0xD5 << 3) | 5:
			assert (false);
			break;
		case (0xD5 << 3) | 6:
			assert (false);
			break;
		case (0xD5 << 3) | 7:
			assert (false);
			break;

		/* DEC zp,X */
		case (0xD6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD6 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD6 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xD6 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xD6 << 3) | 4:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xD6 << 3) | 5:
			fetch();
			break;
		case (0xD6 << 3) | 6:
			assert (false);
			break;
		case (0xD6 << 3) | 7:
			assert (false);
			break;

		/* DCP zp,X (undoc) */
		case (0xD7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD7 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xD7 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xD7 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xD7 << 3) | 4:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xD7 << 3) | 5:
			fetch();
			break;
		case (0xD7 << 3) | 6:
			assert (false);
			break;
		case (0xD7 << 3) | 7:
			assert (false);
			break;

		/* CLD */
		case (0xD8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xD8 << 3) | 1:
			c.P &= ~0x8;
			fetch();
			break;
		case (0xD8 << 3) | 2:
			assert (false);
			break;
		case (0xD8 << 3) | 3:
			assert (false);
			break;
		case (0xD8 << 3) | 4:
			assert (false);
			break;
		case (0xD8 << 3) | 5:
			assert (false);
			break;
		case (0xD8 << 3) | 6:
			assert (false);
			break;
		case (0xD8 << 3) | 7:
			assert (false);
			break;

		/* CMP abs,Y */
		case (0xD9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xD9 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xD9 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xD9 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xD9 << 3) | 4:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xD9 << 3) | 5:
			assert (false);
			break;
		case (0xD9 << 3) | 6:
			assert (false);
			break;
		case (0xD9 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0xDA << 3) | 0:
			setA(c.PC);
			break;
		case (0xDA << 3) | 1:
			fetch();
			break;
		case (0xDA << 3) | 2:
			assert (false);
			break;
		case (0xDA << 3) | 3:
			assert (false);
			break;
		case (0xDA << 3) | 4:
			assert (false);
			break;
		case (0xDA << 3) | 5:
			assert (false);
			break;
		case (0xDA << 3) | 6:
			assert (false);
			break;
		case (0xDA << 3) | 7:
			assert (false);
			break;

		/* DCP abs,Y (undoc) */
		case (0xDB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xDB << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xDB << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0xDB << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xDB << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xDB << 3) | 5:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xDB << 3) | 6:
			fetch();
			break;
		case (0xDB << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0xDC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xDC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xDC << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xDC << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xDC << 3) | 4:
			fetch();
			break;
		case (0xDC << 3) | 5:
			assert (false);
			break;
		case (0xDC << 3) | 6:
			assert (false);
			break;
		case (0xDC << 3) | 7:
			assert (false);
			break;

		/* CMP abs,X */
		case (0xDD << 3) | 0:
			setA(c.PC++);
			break;
		case (0xDD << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xDD << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xDD << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xDD << 3) | 4:
			cmp(c, c.A, getD());
			fetch();
			break;
		case (0xDD << 3) | 5:
			assert (false);
			break;
		case (0xDD << 3) | 6:
			assert (false);
			break;
		case (0xDD << 3) | 7:
			assert (false);
			break;

		/* DEC abs,X */
		case (0xDE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xDE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xDE << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0xDE << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xDE << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xDE << 3) | 5:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xDE << 3) | 6:
			fetch();
			break;
		case (0xDE << 3) | 7:
			assert (false);
			break;

		/* DCP abs,X (undoc) */
		case (0xDF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xDF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xDF << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0xDF << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xDF << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xDF << 3) | 5:
			c.AD--;
			_NZ(c.AD);
			setD(c.AD);
			cmp(c, c.A, c.AD);
			_WR();
			break;
		case (0xDF << 3) | 6:
			fetch();
			break;
		case (0xDF << 3) | 7:
			assert (false);
			break;

		/* CPX # */
		case (0xE0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE0 << 3) | 1:
			cmp(c, c.X, getD());
			fetch();
			break;
		case (0xE0 << 3) | 2:
			assert (false);
			break;
		case (0xE0 << 3) | 3:
			assert (false);
			break;
		case (0xE0 << 3) | 4:
			assert (false);
			break;
		case (0xE0 << 3) | 5:
			assert (false);
			break;
		case (0xE0 << 3) | 6:
			assert (false);
			break;
		case (0xE0 << 3) | 7:
			assert (false);
			break;

		/* SBC (zp,X) */
		case (0xE1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xE1 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xE1 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xE1 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xE1 << 3) | 5:
			sbc(c, getD());
			fetch();
			break;
		case (0xE1 << 3) | 6:
			assert (false);
			break;
		case (0xE1 << 3) | 7:
			assert (false);
			break;

		/* NOP # (undoc) */
		case (0xE2 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE2 << 3) | 1:
			fetch();
			break;
		case (0xE2 << 3) | 2:
			assert (false);
			break;
		case (0xE2 << 3) | 3:
			assert (false);
			break;
		case (0xE2 << 3) | 4:
			assert (false);
			break;
		case (0xE2 << 3) | 5:
			assert (false);
			break;
		case (0xE2 << 3) | 6:
			assert (false);
			break;
		case (0xE2 << 3) | 7:
			assert (false);
			break;

		/* ISB (zp,X) (undoc) */
		case (0xE3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xE3 << 3) | 2:
			c.AD = (c.AD + c.X) & 0xFF;
			setA(c.AD);
			break;
		case (0xE3 << 3) | 3:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xE3 << 3) | 4:
			setA((getD() << 8) | c.AD);
			break;
		case (0xE3 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0xE3 << 3) | 6:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xE3 << 3) | 7:
			fetch();
			break;

		/* CPX zp */
		case (0xE4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE4 << 3) | 1:
			setA(getD());
			break;
		case (0xE4 << 3) | 2:
			cmp(c, c.X, getD());
			fetch();
			break;
		case (0xE4 << 3) | 3:
			assert (false);
			break;
		case (0xE4 << 3) | 4:
			assert (false);
			break;
		case (0xE4 << 3) | 5:
			assert (false);
			break;
		case (0xE4 << 3) | 6:
			assert (false);
			break;
		case (0xE4 << 3) | 7:
			assert (false);
			break;

		/* SBC zp */
		case (0xE5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE5 << 3) | 1:
			setA(getD());
			break;
		case (0xE5 << 3) | 2:
			sbc(c, getD());
			fetch();
			break;
		case (0xE5 << 3) | 3:
			assert (false);
			break;
		case (0xE5 << 3) | 4:
			assert (false);
			break;
		case (0xE5 << 3) | 5:
			assert (false);
			break;
		case (0xE5 << 3) | 6:
			assert (false);
			break;
		case (0xE5 << 3) | 7:
			assert (false);
			break;

		/* INC zp */
		case (0xE6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE6 << 3) | 1:
			setA(getD());
			break;
		case (0xE6 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0xE6 << 3) | 3:
			c.AD = (c.AD + 1) & 0xff;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xE6 << 3) | 4:
			fetch();
			break;
		case (0xE6 << 3) | 5:
			assert (false);
			break;
		case (0xE6 << 3) | 6:
			assert (false);
			break;
		case (0xE6 << 3) | 7:
			assert (false);
			break;

		/* ISB zp (undoc) */
		case (0xE7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE7 << 3) | 1:
			setA(getD());
			break;
		case (0xE7 << 3) | 2:
			c.AD = getD();
			_WR();
			break;
		case (0xE7 << 3) | 3:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xE7 << 3) | 4:
			fetch();
			break;
		case (0xE7 << 3) | 5:
			assert (false);
			break;
		case (0xE7 << 3) | 6:
			assert (false);
			break;
		case (0xE7 << 3) | 7:
			assert (false);
			break;

		/* INX */
		case (0xE8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xE8 << 3) | 1:
			c.X = (c.X + 1) & 0xff;
			_NZ(c.X);
			fetch();
			break;
		case (0xE8 << 3) | 2:
			assert (false);
			break;
		case (0xE8 << 3) | 3:
			assert (false);
			break;
		case (0xE8 << 3) | 4:
			assert (false);
			break;
		case (0xE8 << 3) | 5:
			assert (false);
			break;
		case (0xE8 << 3) | 6:
			assert (false);
			break;
		case (0xE8 << 3) | 7:
			assert (false);
			break;

		/* SBC # */
		case (0xE9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xE9 << 3) | 1:
			sbc(c, getD());
			fetch();
			break;
		case (0xE9 << 3) | 2:
			assert (false);
			break;
		case (0xE9 << 3) | 3:
			assert (false);
			break;
		case (0xE9 << 3) | 4:
			assert (false);
			break;
		case (0xE9 << 3) | 5:
			assert (false);
			break;
		case (0xE9 << 3) | 6:
			assert (false);
			break;
		case (0xE9 << 3) | 7:
			assert (false);
			break;

		/* NOP */
		case (0xEA << 3) | 0:
			setA(c.PC);
			break;
		case (0xEA << 3) | 1:
			fetch();
			break;
		case (0xEA << 3) | 2:
			assert (false);
			break;
		case (0xEA << 3) | 3:
			assert (false);
			break;
		case (0xEA << 3) | 4:
			assert (false);
			break;
		case (0xEA << 3) | 5:
			assert (false);
			break;
		case (0xEA << 3) | 6:
			assert (false);
			break;
		case (0xEA << 3) | 7:
			assert (false);
			break;

		/* SBC # (undoc) */
		case (0xEB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xEB << 3) | 1:
			sbc(c, getD());
			fetch();
			break;
		case (0xEB << 3) | 2:
			assert (false);
			break;
		case (0xEB << 3) | 3:
			assert (false);
			break;
		case (0xEB << 3) | 4:
			assert (false);
			break;
		case (0xEB << 3) | 5:
			assert (false);
			break;
		case (0xEB << 3) | 6:
			assert (false);
			break;
		case (0xEB << 3) | 7:
			assert (false);
			break;

		/* CPX abs */
		case (0xEC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xEC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xEC << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xEC << 3) | 3:
			cmp(c, c.X, getD());
			fetch();
			break;
		case (0xEC << 3) | 4:
			assert (false);
			break;
		case (0xEC << 3) | 5:
			assert (false);
			break;
		case (0xEC << 3) | 6:
			assert (false);
			break;
		case (0xEC << 3) | 7:
			assert (false);
			break;

		/* SBC abs */
		case (0xED << 3) | 0:
			setA(c.PC++);
			break;
		case (0xED << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xED << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xED << 3) | 3:
			sbc(c, getD());
			fetch();
			break;
		case (0xED << 3) | 4:
			assert (false);
			break;
		case (0xED << 3) | 5:
			assert (false);
			break;
		case (0xED << 3) | 6:
			assert (false);
			break;
		case (0xED << 3) | 7:
			assert (false);
			break;

		/* INC abs */
		case (0xEE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xEE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xEE << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xEE << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xEE << 3) | 4:
			c.AD = (c.AD + 1) & 0xff;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xEE << 3) | 5:
			fetch();
			break;
		case (0xEE << 3) | 6:
			assert (false);
			break;
		case (0xEE << 3) | 7:
			assert (false);
			break;

		/* ISB abs (undoc) */
		case (0xEF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xEF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xEF << 3) | 2:
			setA((getD() << 8) | c.AD);
			break;
		case (0xEF << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xEF << 3) | 4:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xEF << 3) | 5:
			fetch();
			break;
		case (0xEF << 3) | 6:
			assert (false);
			break;
		case (0xEF << 3) | 7:
			assert (false);
			break;

		/* BEQ # */
		case (0xF0 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF0 << 3) | 1:
			setA(c.PC);
			c.AD = c.PC + (byte) getD();
			if ((c.P & 0x2) != 0x2) {
				fetch();
			}
			;
			break;
		case (0xF0 << 3) | 2:
			setA((c.PC & 0xFF00) | (c.AD & 0x00FF));
			if ((c.AD & 0xFF00) == (c.PC & 0xFF00)) {
				c.PC = c.AD;
				c.irq_pip >>= 1;
				c.nmi_pip >>= 1;
				fetch();
			}
			;
			break;
		case (0xF0 << 3) | 3:
			c.PC = c.AD;
			fetch();
			break;
		case (0xF0 << 3) | 4:
			assert (false);
			break;
		case (0xF0 << 3) | 5:
			assert (false);
			break;
		case (0xF0 << 3) | 6:
			assert (false);
			break;
		case (0xF0 << 3) | 7:
			assert (false);
			break;

		/* SBC (zp),Y */
		case (0xF1 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF1 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF1 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xF1 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xF1 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xF1 << 3) | 5:
			sbc(c, getD());
			fetch();
			break;
		case (0xF1 << 3) | 6:
			assert (false);
			break;
		case (0xF1 << 3) | 7:
			assert (false);
			break;

		/* JAM INVALID (undoc) */
		case (0xF2 << 3) | 0:
			setA(c.PC);
			break;
		case (0xF2 << 3) | 1:
			setAD(0xFFFF, 0xFF);
			c.IR--;
			break;
		case (0xF2 << 3) | 2:
			assert (false);
			break;
		case (0xF2 << 3) | 3:
			assert (false);
			break;
		case (0xF2 << 3) | 4:
			assert (false);
			break;
		case (0xF2 << 3) | 5:
			assert (false);
			break;
		case (0xF2 << 3) | 6:
			assert (false);
			break;
		case (0xF2 << 3) | 7:
			assert (false);
			break;

		/* ISB (zp),Y (undoc) */
		case (0xF3 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF3 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF3 << 3) | 2:
			setA((c.AD + 1) & 0xFF);
			c.AD = getD();
			break;
		case (0xF3 << 3) | 3:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0xF3 << 3) | 4:
			setA(c.AD + c.Y);
			break;
		case (0xF3 << 3) | 5:
			c.AD = getD();
			_WR();
			break;
		case (0xF3 << 3) | 6:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xF3 << 3) | 7:
			fetch();
			break;

		/* NOP zp,X (undoc) */
		case (0xF4 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF4 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF4 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xF4 << 3) | 3:
			fetch();
			break;
		case (0xF4 << 3) | 4:
			assert (false);
			break;
		case (0xF4 << 3) | 5:
			assert (false);
			break;
		case (0xF4 << 3) | 6:
			assert (false);
			break;
		case (0xF4 << 3) | 7:
			assert (false);
			break;

		/* SBC zp,X */
		case (0xF5 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF5 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF5 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xF5 << 3) | 3:
			sbc(c, getD());
			fetch();
			break;
		case (0xF5 << 3) | 4:
			assert (false);
			break;
		case (0xF5 << 3) | 5:
			assert (false);
			break;
		case (0xF5 << 3) | 6:
			assert (false);
			break;
		case (0xF5 << 3) | 7:
			assert (false);
			break;

		/* INC zp,X */
		case (0xF6 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF6 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF6 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xF6 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xF6 << 3) | 4:
			c.AD = (c.AD + 1) & 0xff;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xF6 << 3) | 5:
			fetch();
			break;
		case (0xF6 << 3) | 6:
			assert (false);
			break;
		case (0xF6 << 3) | 7:
			assert (false);
			break;

		/* ISB zp,X (undoc) */
		case (0xF7 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF7 << 3) | 1:
			c.AD = getD();
			setA(c.AD);
			break;
		case (0xF7 << 3) | 2:
			setA((c.AD + c.X) & 0x00FF);
			break;
		case (0xF7 << 3) | 3:
			c.AD = getD();
			_WR();
			break;
		case (0xF7 << 3) | 4:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xF7 << 3) | 5:
			fetch();
			break;
		case (0xF7 << 3) | 6:
			assert (false);
			break;
		case (0xF7 << 3) | 7:
			assert (false);
			break;

		/* SED */
		case (0xF8 << 3) | 0:
			setA(c.PC);
			break;
		case (0xF8 << 3) | 1:
			c.P |= 0x8;
			fetch();
			break;
		case (0xF8 << 3) | 2:
			assert (false);
			break;
		case (0xF8 << 3) | 3:
			assert (false);
			break;
		case (0xF8 << 3) | 4:
			assert (false);
			break;
		case (0xF8 << 3) | 5:
			assert (false);
			break;
		case (0xF8 << 3) | 6:
			assert (false);
			break;
		case (0xF8 << 3) | 7:
			assert (false);
			break;

		/* SBC abs,Y */
		case (0xF9 << 3) | 0:
			setA(c.PC++);
			break;
		case (0xF9 << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xF9 << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.Y) >> 8))) & 1;
			break;
		case (0xF9 << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xF9 << 3) | 4:
			sbc(c, getD());
			fetch();
			break;
		case (0xF9 << 3) | 5:
			assert (false);
			break;
		case (0xF9 << 3) | 6:
			assert (false);
			break;
		case (0xF9 << 3) | 7:
			assert (false);
			break;

		/* NOP (undoc) */
		case (0xFA << 3) | 0:
			setA(c.PC);
			break;
		case (0xFA << 3) | 1:
			fetch();
			break;
		case (0xFA << 3) | 2:
			assert (false);
			break;
		case (0xFA << 3) | 3:
			assert (false);
			break;
		case (0xFA << 3) | 4:
			assert (false);
			break;
		case (0xFA << 3) | 5:
			assert (false);
			break;
		case (0xFA << 3) | 6:
			assert (false);
			break;
		case (0xFA << 3) | 7:
			assert (false);
			break;

		/* ISB abs,Y (undoc) */
		case (0xFB << 3) | 0:
			setA(c.PC++);
			break;
		case (0xFB << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xFB << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.Y) & 0xFF));
			break;
		case (0xFB << 3) | 3:
			setA(c.AD + c.Y);
			break;
		case (0xFB << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xFB << 3) | 5:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xFB << 3) | 6:
			fetch();
			break;
		case (0xFB << 3) | 7:
			assert (false);
			break;

		/* NOP abs,X (undoc) */
		case (0xFC << 3) | 0:
			setA(c.PC++);
			break;
		case (0xFC << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xFC << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xFC << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xFC << 3) | 4:
			fetch();
			break;
		case (0xFC << 3) | 5:
			assert (false);
			break;
		case (0xFC << 3) | 6:
			assert (false);
			break;
		case (0xFC << 3) | 7:
			assert (false);
			break;

		/* SBC abs,X */
		case (0xFD << 3) | 0:
			setA(c.PC++);
			break;
		case (0xFD << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xFD << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			c.IR += (~((c.AD >> 8) - ((c.AD + c.X) >> 8))) & 1;
			break;
		case (0xFD << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xFD << 3) | 4:
			sbc(c, getD());
			fetch();
			break;
		case (0xFD << 3) | 5:
			assert (false);
			break;
		case (0xFD << 3) | 6:
			assert (false);
			break;
		case (0xFD << 3) | 7:
			assert (false);
			break;

		/* INC abs,X */
		case (0xFE << 3) | 0:
			setA(c.PC++);
			break;
		case (0xFE << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xFE << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0xFE << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xFE << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xFE << 3) | 5:
			c.AD = (c.AD + 1) & 0xff;
			_NZ(c.AD);
			setD(c.AD);
			_WR();
			break;
		case (0xFE << 3) | 6:
			fetch();
			break;
		case (0xFE << 3) | 7:
			assert (false);
			break;

		/* ISB abs,X (undoc) */
		case (0xFF << 3) | 0:
			setA(c.PC++);
			break;
		case (0xFF << 3) | 1:
			setA(c.PC++);
			c.AD = getD();
			break;
		case (0xFF << 3) | 2:
			c.AD |= getD() << 8;
			setA((c.AD & 0xFF00) | ((c.AD + c.X) & 0xFF));
			break;
		case (0xFF << 3) | 3:
			setA(c.AD + c.X);
			break;
		case (0xFF << 3) | 4:
			c.AD = getD();
			_WR();
			break;
		case (0xFF << 3) | 5:
			c.AD = (c.AD + 1) & 0xff;
			setD(c.AD);
			sbc(c, c.AD);
			_WR();
			break;
		case (0xFF << 3) | 6:
			fetch();
			break;
		case (0xFF << 3) | 7:
			assert (false);
			break;

		default:
			throw new IllegalStateException("State: " + (c.IR - 1));
		}
		c.last_nmi_state = input.nmi;
		c.irq_pip <<= 1;
		c.nmi_pip <<= 1;
		return this.out;
	}

	/**
	 * Handle the second have of a cycle (PHI2 HIGH)
	 * 
	 * @param input
	 * @return
	 */
	public Sim6502Output m6502_tock(Sim6502Input input) {
		this.in = input;
		return this.out;
	}
}
