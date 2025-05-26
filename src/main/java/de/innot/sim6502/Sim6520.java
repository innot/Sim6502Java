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
 * MOS 6520 PIA emulator.
 * 
 * 
 * Based on the
 * {@link <a href="https://archive.org/details/rockwell_r6520_pia">Rockwell
 * R6520</a>} PIA Datasheet Rev.4
 * 
 * 
 * @author Thomas Holland
 * 
 * @version 1.0 First release
 * 
 * 
 */

public class Sim6520 {

	// register indices

	/** input/output/ddr register A **/
	public final static int REG_RA = 0;

	/** control register A **/
	public final static int REG_CRA = 1;

	/** input/output/ddr register B **/
	public final static int REG_RB = 2;

	/** control register B **/
	public final static int REG_CRB = 3;

	final static int NUM_REGS = 4;

	// interrupt bits
	final static int IRQ_1 = 1 << 7;
	final static int IRQ_2 = 1 << 6;

	// delay-pipeline bit offsets
	final static int PIP_TIMER_COUNT = 0;
	final static int PIP_TIMER_LOAD = 8;
	final static int PIP_IRQ = 0;

	/** Port State **/
	private class PortState {
		int inpr;

		int pins;
		int outr;
		int ddr;
		boolean c1_in;
		boolean c1_triggered;
		boolean c2_in;
		boolean c2_out;
		boolean c2_trigger_to_low;
		boolean c2_trigger_to_high;
	}

	PortState pa;
	PortState pb;

	/** control register a */
	int cra;

	/** control register b */
	int crb;

	/**
	 * IRQ[A|B]1 Enable. 0 = Disabled (IRQ Signal is not generated) 1 = Enabled (
	 * IRQ Signal is generated)
	 * <ul>
	 */
	public final static int CR_IRQ1_ENABLE = 1 << 0;

	/**
	 * IRQ[A|B]1 Transition Detection Select.
	 * <ul>
	 * <li>0 = Negative Edge (high-to-low)</li>
	 * <li>1 = Positive Edge (low-to-high)</li>
	 * <ul>
	 */
	public final static int CR_IRQ1_TRANSITION = 1 << 1;

	/**
	 * Output Register Select.
	 * <ul>
	 * <li>0 = DDR selected</li>
	 * <li>1 = OR[A|B] selected</li>
	 * </ul>
	 **/
	public final static int CR_ORA_SELECT = 1 << 2;

	// Next 2 bits are for CAB2 as Input

	/**
	 * IRQ[A|B]2 Enable. (Only when C[A|B]2 is set as input)
	 * <ul>
	 * <li>0 = Disabled (IRQ Signal is not generated)</li>
	 * <li>1 = Enabled ( IRQ Signal is generated)</li>
	 * <ul>
	 */
	public final static int CR_IRQ2_ENABLE = 1 << 3;

	/**
	 * IRQ[A|B]2 Transition Detection flag. (Only when C[A|B]2 is set as input)
	 * <ul>
	 * <li>0 = Negative Edge (high-to-low).</li>
	 * <li>1 = Positive Edge (low-to-high).</li>
	 * </ul>
	 */
	public final static int CR_IRQ2_TRANSITION = 1 << 4;

	// Next 2 bits are for CAB2 as Output

	/**
	 * CA2 Read Strobe Restore Control. (Only when CA2 is set as output)
	 * <ul>
	 * <li>0 = CA2 returns high on next CA1 transition following read of Output
	 * Register A. Transition Edge determined by CR_IRQ1_TRANSITION.</li>
	 * <li>1 = CA2 returns high on next Φ2 negative edge following read of Output
	 * Register A</li>
	 * <ul>
	 */
	public final static int CRA_CA2_READ_STROBE_RESTORE = 1 << 3;

	/**
	 * CB2 Write Strobe Restore Control. (Only when CB2 is set as output)
	 * <ul>
	 * <li>0 = CB2 returns high on next CB1 transition following write of Output
	 * Register B. Transition Edge determined by CR_IRQ1_TRANSITION.</li>
	 * <li>1 = CB2 returns high on next Φ2 negative edge following write of Output
	 * Register B</li>
	 * <ul>
	 */
	public final static int CRB_CB2_WRITE_STROBE_RESTORE = 1 << 3;

	/**
	 * CA2 Output Control. (Only when CA2 is set as output)
	 * <ul>
	 * <li>0 = CA2 goes low on next Φ2 negative edge following read of Output
	 * Register A. Returns high as specified by CRA bit 3</li>
	 * <li>1 = CA2 goes high when a 0, and low when a 1, is written to CRA bit
	 * 3</li>
	 * </ul>
	 */
	public final static int CRA_CA2_OUTPUT_CONTROL = 1 << 4;

	/**
	 * CB2 Output Control. (Only when CB2 is set as output)
	 * <ul>
	 * <li>0 = CB2 goes low on next Φ2 negative edge following write of Output
	 * Register B. Returns high as specified by CRA bit 3</li>
	 * <li>1 = CB2 goes low when a 0, and high when a 1, is written to CRB bit
	 * 3</li>
	 * </ul>
	 */
	public final static int CRB_CB2_OUTPUT_CONTROL = 1 << 4;

	/**
	 * C[A|B]2 Mode Select.
	 * <ul>
	 * <li>0 = Input Mode</li>
	 * <li>1 = Output Mode</li>
	 * <ul>
	 */
	public final static int CRA_CAB2_MODE = 1 << 5;

	/**
	 * IRQ[A|B]2 Flag.
	 * <ul>
	 * <li>0 = No C[A|B]2 Transition detected</li>
	 * <li>1 = C[A|B]2 Transition detected. Cleared by read of Output Register.</li>
	 * <ul>
	 */
	public final static int CR_IRQ_2_FLAG = 1 << 6;

	/**
	 * IRQ[A|B]1 Flag.
	 * <ul>
	 * <li>0 = No C[A|B]1 Transition detected</li>
	 * <li>1 = C[A|B]1 Transition detected. Cleared by read of Output Register.</li>
	 * <ul>
	 */
	public final static int CR_IRQ_1_FLAG = 1 << 7;

	private static boolean isIRQ1PosTransition(int cr) {
		return (cr & CR_IRQ1_TRANSITION) != 0;
	}

	private static boolean isIRQ1NegTransition(int cr) {
		return (cr & CR_IRQ1_TRANSITION) == 0;
	}

	private static boolean isIRQ2PosTransition(int cr) {
		return (cr & CR_IRQ2_TRANSITION) != 0;
	}

	private static boolean isIRQ2NegTransition(int cr) {
		return (cr & CR_IRQ2_TRANSITION) == 0;
	}

	private static boolean isCAB2Output(int cr) {
		return (cr & CRA_CAB2_MODE) != 0;
	}

	private static boolean isCAB2Input(int cr) {
		return (cr & CRA_CAB2_MODE) == 0;
	}

	private static boolean isCAB2OutputControl(int cr) {
		return (cr & CRA_CA2_OUTPUT_CONTROL) != 0;
	}

	/*-- IMPLEMENTATION ----------------------------------------------------------*/

	Sim6520Output output = new Sim6520Output();

	private void init_port(PortState p) {
		p.inpr = 0x00; // the original mos_6520.h had a 0xFF here, but according to datasheet all
						// registers are set to 0
		p.pins = 0x00; // dito.
		p.outr = 0;
		p.ddr = 0;
		p.c1_in = false;
		p.c1_triggered = false;
		p.c2_in = false;
		p.c2_out = true;
		p.c2_trigger_to_low = false;
		p.c2_trigger_to_high = false;
	}

	/**
	 * Instantiate a new Sim6520 object.
	 * 
	 * All internal values are set to their initial state.
	 */
	public Sim6520() {
		this.pa = new PortState();
		this.pb = new PortState();

		m6520_reset();
	}

	/**
	 * Reset the 6520.
	 * 
	 * The RESET input clears all internal registers to logic 0, (except T1, T2 and
	 * SR). This places all peripheral interface lines in the input state, disables
	 * the timers, shift registers etc. and disables interrupting from the chip"
	 */
	public void m6520_reset() {
		init_port(this.pa);
		init_port(this.pb);
		this.cra = 0;
		this.crb = 0;
	}

	/*--- port implementation ---*/

	private void cab_change_detection(Sim6520Input input) {

		//
		// CA 1/2
		//

		boolean new_ca1 = input.ca1;
		boolean ca1_transition = this.pa.c1_in != new_ca1;
		boolean reset_ca2 = false;

		// Check for CA1 transitions and flag IRQ as appropriate for edge

		// CA1 Falling edge
		if (ca1_transition && new_ca1 == false && isIRQ1NegTransition(this.cra)) {
			this.cra |= CR_IRQ_1_FLAG;
			reset_ca2 = true;
		}
		// CA1 rising edge
		if (ca1_transition && new_ca1 == true && isIRQ1PosTransition(this.cra)) {
			this.cra |= CR_IRQ_1_FLAG;
			reset_ca2 = true;
		}

		boolean new_ca2 = input.ca2;
		boolean ca2_transition = this.pa.c2_in != new_ca2;

		// Only check if CA2 is set to input
		if (isCAB2Input(this.cra)) {
			if (ca2_transition && new_ca2 == false && isIRQ2NegTransition(this.cra)) {
				this.cra |= CR_IRQ_2_FLAG;
			}
			if (ca2_transition && new_ca2 == true && isIRQ2PosTransition(this.cra)) {
				this.cra |= CR_IRQ_2_FLAG;
			}
		}
		//
		// CB 1/2
		//

		boolean new_cb1 = input.cb1;
		boolean cb1_transition = this.pb.c1_in != new_cb1;
		boolean reset_cb2 = false;

		// Check for CB1 transitions and flag IRQ as appropriate for edge
		if (cb1_transition && new_cb1 == false && isIRQ1NegTransition(this.crb)) {
			this.crb |= CR_IRQ_1_FLAG;
			reset_cb2 = true;
		}
		if (cb1_transition && new_cb1 == true && isIRQ1PosTransition(this.crb)) {
			this.crb |= CR_IRQ_1_FLAG;
			reset_cb2 = true;
		}

		boolean new_cb2 = input.cb2;
		boolean cb2_transition = this.pb.c2_in != new_cb2;

		if (isCAB2Input(this.crb)) {
			if (cb2_transition && new_cb2 == false && isIRQ2NegTransition(this.crb)) {
				this.crb |= CR_IRQ_2_FLAG;
			}
			if (cb2_transition && new_cb2 == true && isIRQ2PosTransition(this.crb)) {
				this.crb |= CR_IRQ_2_FLAG;
			}
		}

		this.pa.c1_in = new_ca1;
		this.pa.c2_in = new_ca2;
		this.pb.c1_in = new_cb1;
		this.pb.c2_in = new_cb2;

		/* Reset the strobes if required */
		if (reset_ca2 && this.pa.c1_triggered) {
			this.pa.c2_out = true;
			this.pa.c1_triggered = false;
		}
		if (reset_cb2 && this.pb.c1_triggered) {
			this.pb.c2_out = true;
			this.pb.c1_triggered = false;
		}
	}

	/**
	 * Generate the CA2 and CB2 output strobes as required.
	 * 
	 * This must be called after {@link #read_register(int)} and
	 * {@link #cab_change_detection(Sim6520Input)}.
	 */
	private void handle_strobes(Sim6520Input input) {

		if (isCAB2Output(this.cra) && !isCAB2OutputControl(this.cra)) {
			// only continue if CA2 is an output and set to automatic strobes

			if (!input.phi2 && this.pa.c2_trigger_to_low) {
				/*
				 * CA2 goes low on the first negative (high-to-tow) PHI2 clock transition
				 * following a read of Output Register A. CA2 returns high as specified by bit 3
				 * (CRA_CA2_READ_STROBE_RESTORE).
				 */
				this.pa.c2_out = false;
				this.pa.c2_trigger_to_low = false;
				this.pa.c2_trigger_to_high = true;
			} else if ((this.cra & CRA_CA2_READ_STROBE_RESTORE) == 1) {
				/*
				 * CA2 returns high on the next PHI2 clock negative transition following a read
				 * of Output Register A.
				 */
				if (!input.phi2 && this.pa.c2_trigger_to_high) {
					this.pa.c2_out = true;
					this.pa.c2_trigger_to_high = false;
				}
			} else {
				/*
				 * CA2 returns high on the next active CA1 transition following a read of Output
				 * Register A as specified By bit 1.
				 */
				if (this.pa.c2_trigger_to_high) {
					this.pa.c1_triggered = true;
					this.pa.c2_trigger_to_high = false;
				}
			}
		}

		if (isCAB2Output(this.crb) && !isCAB2OutputControl(this.crb)) {
			// only continue if CB2 is an output and set to automatic strobes

			if (!input.phi2 && this.pb.c2_trigger_to_low) {
				/*
				 * CB2 goes low on the first negative PHI2 clock transition following a write to
				 * Output Register B. CB2 returns high as specified by bit 3.
				 */
				this.pb.c2_out = false;
				this.pb.c2_trigger_to_low = false;
				this.pb.c2_trigger_to_high = true;
			} else if ((this.crb & CRB_CB2_WRITE_STROBE_RESTORE) == 1) {
				/*
				 * CB2 returns high on the next PHI2 clock negative transition following a write
				 * to Output Register B.
				 */
				if (!input.phi2 && this.pb.c2_trigger_to_high) {
					this.pb.c2_out = true;
					this.pb.c2_trigger_to_high = false;
				}
			} else {
				/*
				 * CB2 returns high on the next active CB1 transition following a write to
				 * Output Register B as specified By bit 1.
				 */
				if (this.pb.c2_trigger_to_high) {
					this.pb.c1_triggered = true;
					this.pb.c2_trigger_to_high = false;
				}
			}
		}

	}

	private void read_port_pins(Sim6520Input input) {
		this.pa.inpr = input.pa;
		this.pb.inpr = input.pb;

		// When reading the port output register a mix of the inpr and outr according to
		// the ddr is returned.
		this.pa.pins = (this.pa.inpr & ~this.pa.ddr) | (this.pa.outr & this.pa.ddr);
		this.pb.pins = (this.pb.inpr & ~this.pb.ddr) | (this.pb.outr & this.pb.ddr);
	}

	private void update_output() {

		output.pa = this.pa.pins & this.pa.ddr;
		output.pa_dir = this.pa.ddr;

		output.pb = this.pb.pins & this.pb.ddr;
		output.pb_dir = this.pb.ddr;

		output.ca2 = this.pa.c2_out;
		output.ca2_dir = isCAB2Output(this.cra);

		output.cb2 = this.pb.c2_out;
		output.cb2_dir = isCAB2Output(this.crb);

		// Set IRQ lines to low as required. They are cleared to high by reading from
		// the output register.
		if ((this.cra & CR_IRQ1_ENABLE) != 0 || (this.cra & CR_IRQ2_ENABLE) != 0) {
			int irq = (this.cra & CR_IRQ_1_FLAG) | (this.cra & CR_IRQ_2_FLAG);
			output.irqa = !(irq != 0); // negate because active low
		}

		if ((this.crb & CR_IRQ1_ENABLE) != 0 || (this.crb & CR_IRQ2_ENABLE) != 0) {
			int irq = (this.crb & CR_IRQ_1_FLAG) | (this.crb & CR_IRQ_2_FLAG);
			output.irqb = !(irq != 0); // negate because active low
		}
	}

	/**
	 * Read the content of the given register.
	 * 
	 * @param addr 2 bit address of the register
	 * @return 8 bit register content
	 */
	private int read_register(int addr) {
		switch (addr) {
		case REG_RA:
			if ((this.cra & CR_ORA_SELECT) == 0) {
				return this.pa.ddr;
			} else {
				this.cra &= ~(CR_IRQ_1_FLAG | CR_IRQ_2_FLAG); // Clear IRQ flags
				this.pa.c2_trigger_to_low = true;
				return this.pa.pins;
			}

		case REG_RB:
			if ((this.crb & CR_ORA_SELECT) == 0) {
				return this.pb.ddr;
			} else {
				this.crb &= ~(CR_IRQ_1_FLAG | CR_IRQ_2_FLAG); // Clear IRQ flags
				return this.pb.pins;
			}

		case REG_CRA:
			return this.cra & 0xff;

		case REG_CRB:
			return this.crb & 0xff;

		default:
			throw new IllegalStateException("Invalid register address");
		}
	}

	/**
	 * Write the data byte to a register.
	 * 
	 * @param addr 2 bit register address
	 * @param data 8 bit data value
	 */
	private void write_register(int addr, int data) {
		switch (addr) {
		case REG_RA:
			// check if DDR or ORA is written to
			if ((this.cra & CR_ORA_SELECT) == 0) {
				this.pa.ddr = data;
			} else {
				this.pa.outr = data;
			}
			break;

		case REG_RB:
			// check if DDR or ORB is written to
			if ((this.crb & CR_ORA_SELECT) == 0) {
				this.pb.ddr = data;
			} else {
				this.pb.outr = data;
				this.pb.c2_trigger_to_low = true;
			}
			break;

		case REG_CRA:
			this.cra = data & 0b00111111; // irq flags are not changed by a write

			// Set CA2 Direction output
			output.ca2_dir = (data & CRA_CAB2_MODE) != 0;

			if (isCAB2Output(data) && isCAB2OutputControl(data)) {
				// CA2 is set directly from bit 3 (CA2 READ STROBE RESTORE CONTROL)
				this.pa.c2_out = (data & CRA_CA2_READ_STROBE_RESTORE) != 0;
			}
			break;

		case REG_CRB:
			this.crb = data & 0b00111111; // irq flags are not changed by a write

			// Set CB2 Direction output
			output.cb2_dir = (data & CRA_CAB2_MODE) != 0;

			if (isCAB2Output(data) && isCAB2OutputControl(data)) {
				// CB2 is set directly from bit 3 (CB2 WRITE STROBE RESTORE CONTROL)
				this.pb.c2_out = (data & CRB_CB2_WRITE_STROBE_RESTORE) != 0;
			}
			break;
		}
	}

	/**
	 * Simulate a single clock cycle.
	 * 
	 * This must be called on on any edge change of the clock signal, as
	 * 
	 * @param input The values of all input pins.
	 * @return The new output pin states.
	 */
	public Sim6520Output tick(Sim6520Input input) {

		if (input.reset == false) { // active low
			m6520_reset();
			output.data = 0;
			update_output();
			return output;
		}

		read_port_pins(input);

		cab_change_detection(input);

		if (input.phi2 == true) {
			// Register Read/Write only happens on the positive edge of phi2 (when CPU has
			// set up all control lines)

			if (input.cs1 && !input.cs2 && input.cs0) {
				int addr = input.rs;
				if (input.rw) {
					// Read register
					int data = read_register(addr);
					output.data = data;
				} else {
					// Write Register
					int data = input.data;
					write_register(addr, data);
				}
			}
		}

		handle_strobes(input);

		update_output();
		return output;
	}
}
