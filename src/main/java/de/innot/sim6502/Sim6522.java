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
 * MOS 6522 VIA emulator.
 * 
 * Derived from the 6522 Simulator by Andre Weissflog
 * (<a href="https://github.com/floooh/chips/">Project repo</a>)
 * 
 * Converted to Java and refactored to be more object oriented.
 * 
 * @implNote The Shift Register is not yet implemented.
 * 
 * @author Thomas Holland
 * 
 * @version 1.0 First release
 * 
 * 
 */

public class Sim6522 {

	// register indices

	/** input/output register B **/
	public final static int REG_RB = 0;
	/** input/output register A */
	public final static int REG_RA = 1;
	/** data direction B */
	public final static int REG_DDRB = 2;
	/** data direction A */
	public final static int REG_DDRA = 3;
	/** T1 low-order latch / counter */
	public final static int REG_T1CL = 4;
	/** T1 high-order counter */
	public final static int REG_T1CH = 5;
	/** T1 low-order latches */
	public final static int REG_T1LL = 6;
	/** T1 high-order latches */
	public final static int REG_T1LH = 7;
	/** T2 low-order latch / counter */
	public final static int REG_T2CL = 8;
	/** T2 high-order counter */
	public final static int REG_T2CH = 9;
	/** shift register */
	public final static int REG_SR = 10;
	/** auxiliary control register */
	public final static int REG_ACR = 11;
	/** peripheral control register */
	public final static int REG_PCR = 12;
	/** interrupt flag register */
	public final static int REG_IFR = 13;
	/** interrupt enable register */
	public final static int REG_IER = 14;
	/** input/output A without handshake */
	public final static int REG_RA_NOH = 15;

	final static int NUM_REGS = 16;

	// interrupt bits
	final static int IRQ_CA2 = 1 << 0;
	final static int IRQ_CA1 = 1 << 1;
	final static int IRQ_SR = 1 << 2;
	final static int IRQ_CB2 = 1 << 3;
	final static int IRQ_CB1 = 1 << 4;
	final static int IRQ_T2 = 1 << 5;
	final static int IRQ_T1 = 1 << 6;
	final static int IRQ_ANY = 1 << 7;

	// delay-pipeline bit offsets
	final static int PIP_TIMER_COUNT = 0;
	final static int PIP_TIMER_LOAD = 8;
	final static int PIP_IRQ = 0;

	/** Port State **/
	private static class PortState {
		int inpr;
		int pins;
		int outr;
		int ddr;
		boolean c1_in;
		boolean c1_triggered;
		boolean c2_in;
		boolean c2_out;
		boolean c2_triggered;
	}

	/** Timer State **/
	private static class TimerState {
		/** 16-bit initial value latch, NOTE: T2 only has an 8-bit latch **/
		int latch;

		/** 16-bit counter **/
		int counter;

		/** toggles between true and false when counter underflows **/
		boolean t_bit;

		/** true for 1 cycle when counter underflow **/
		boolean t_out;

		/**
		 * merged delay-pipelines: 2-cycle 'counter active': bits 0..7 1-cycle 'force
		 * load': bits 8..16
		 */
		int pip;
	}

	/** Interrupt State **/
	private static class IntState {
		/** interrupt enable register */
		int ier;
		/** interrupt flag register */
		int ifr;
		int pip;
	}

	final PortState pa;
	final PortState pb;
	final TimerState t1;
	final TimerState t2;
	final IntState intr;

	/** Auxiliary control register */
	int acr;

	/** peripheral control register */
	int pcr;

	Sim6522Input last_input;
	final Sim6522Output output = new Sim6522Output();

	// PCR test macros (MAME naming)
	private boolean M6522_PCR_CA1_LOW_TO_HIGH() {
		return (this.pcr & 0x01) > 0;
	}

	private boolean M6522_PCR_CA1_HIGH_TO_LOW() {
		return (this.pcr & 0x01) == 0;
	}

	private boolean M6522_PCR_CB1_LOW_TO_HIGH() {
		return (this.pcr & 0x10) > 0;
	}

	private boolean M6522_PCR_CB1_HIGH_TO_LOW() {
		return (this.pcr & 0x10) == 0;
	}

	private boolean M6522_PCR_CA2_INPUT() {
		return (this.pcr & 0x08) == 0;
	}

	private boolean M6522_PCR_CA2_OUTPUT() {
		return (this.pcr & 0x08) > 0;
	}

	private boolean M6522_PCR_CA2_LOW_TO_HIGH() {
		return (this.pcr & 0x0c) == 0x04;
	}

	private boolean M6522_PCR_CA2_HIGH_TO_LOW() {
		return (this.pcr & 0x0c) == 0x00;
	}

	private boolean M6522_PCR_CA2_IND_IRQ() {
		return (this.pcr & 0x0a) == 0x02;
	}

	private boolean M6522_PCR_CA2_AUTO_HS() {
		return (this.pcr & 0x0c) == 0x08;
	}

	private boolean M6522_PCR_CA2_HS_OUTPUT() {
		return (this.pcr & 0x0e) == 0x08;
	}

	private boolean M6522_PCR_CA2_PULSE_OUTPUT() {
		return (this.pcr & 0x0e) == 0x0a;
	}

	private boolean M6522_PCR_CA2_FIX_OUTPUT() {
		return (this.pcr & 0x0c) == 0x0c;
	}

	private boolean M6522_PCR_CA2_OUTPUT_LEVEL() {
		return (this.pcr & 0x02) > 0;
	}

	private boolean M6522_PCR_CB2_INPUT() {
		return (this.pcr & 0x80) == 0;
	}

	private boolean M6522_PCR_CB2_OUTPUT() {
		return (this.pcr & 0x80) > 0;
	}

	private boolean M6522_PCR_CB2_LOW_TO_HIGH() {
		return (this.pcr & 0xc0) == 0x40;
	}

	private boolean M6522_PCR_CB2_HIGH_TO_LOW() {
		return (this.pcr & 0xc0) == 0x00;
	}

	private boolean M6522_PCR_CB2_IND_IRQ() {
		return (this.pcr & 0xa0) == 0x20;
	}

	private boolean M6522_PCR_CB2_AUTO_HS() {
		return (this.pcr & 0xc0) == 0x80;
	}

	private boolean M6522_PCR_CB2_HS_OUTPUT() {
		return (this.pcr & 0xe0) == 0x80;
	}

	private boolean M6522_PCR_CB2_PULSE_OUTPUT() {
		return (this.pcr & 0xe0) == 0xa0;
	}

	private boolean M6522_PCR_CB2_FIX_OUTPUT() {
		return (this.pcr & 0xc0) == 0xc0;
	}

	private boolean M6522_PCR_CB2_OUTPUT_LEVEL() {
		return (this.pcr & 0x20) > 0;
	}

	// ACR test macros (MAME naming)
	private boolean M6522_ACR_PA_LATCH_ENABLE() {
		return (this.acr & 0x01) > 0;
	}

	private boolean M6522_ACR_PB_LATCH_ENABLE() {
		return (this.acr & 0x02) > 0;
	}

	private boolean M6522_ACR_SR_DISABLED() {
		return (this.acr & 0x1c) == 0;
	}

	private boolean M6522_ACR_SI_T2_CONTROL() {
		return (this.acr & 0x1c) == 0x04;
	}

	private boolean M6522_ACR_SI_O2_CONTROL() {
		return (this.acr & 0x1c) == 0x08;
	}

	private boolean M6522_ACR_SI_EXT_CONTROL() {
		return (this.acr & 0x1c) == 0x0c;
	}

	private boolean M6522_ACR_SO_T2_RATE() {
		return (this.acr & 0x1c) == 0x10;
	}

	private boolean M6522_ACR_SO_T2_CONTROL() {
		return (this.acr & 0x1c) == 0x14;
	}

	private boolean M6522_ACR_SO_O2_CONTROL() {
		return (this.acr & 0x1c) == 0x18;
	}

	private boolean M6522_ACR_SO_EXT_CONTROL() {
		return (this.acr & 0x1c) == 0x1c;
	}

	private boolean M6522_ACR_T1_SET_PB7() {
		return (this.acr & 0x80) > 0;
	}

	private boolean M6522_ACR_T1_CONTINUOUS() {
		return (this.acr & 0x40) > 0;
	}

	private boolean M6522_ACR_T2_COUNT_PB6() {
		return (this.acr & 0x20) > 0;
	}

	/*-- IMPLEMENTATION ----------------------------------------------------------*/

	private void init_port(PortState p) {
		p.inpr = 0x00; // the original mos_6522.h had a 0xFF here, but according to datasheet all
						// registers are set to 0
		p.pins = 0x00; // dito.
		p.outr = 0;
		p.ddr = 0;
		p.c1_in = false;
		p.c1_triggered = false;
		p.c2_in = false;
		p.c2_out = true;
		p.c2_triggered = false;
	}

	private void init_timer(TimerState t, boolean is_reset) {
		/* counters and latches are not initialized at reset */
		if (!is_reset) {
			t.latch = 0xFFFF;
			t.counter = 0;
			t.t_bit = false;
		}
		t.t_out = false;
		t.pip = 0;
	}

	private void init_interrupt(IntState intr) {
		intr.ier = 0;
		intr.ifr = 0;
		intr.pip = 0;
	}

	/**
	 * Instantiate a new Sim6522 object.
	 * 
	 * All internal values are set to their initial state.
	 */
	public Sim6522() {
		this.pa = new PortState();
		this.pb = new PortState();
		this.t1 = new TimerState();
		this.t2 = new TimerState();
		this.intr = new IntState();

		init_port(this.pa);
		init_port(this.pb);
		init_timer(this.t1, false);
		init_timer(this.t2, false);
		init_interrupt(this.intr);

		this.acr = 0;
		this.pcr = 0;
		this.t1.latch = 0xFFFF;
		this.t2.latch = 0xFFFF;
	}

	/**
	 * Reset the 6522.
	 * 
	 * The RESET input clears all internal registers to logic 0, (except T1, T2 and
	 * SR). This places all peripheral interface lines in the input state, disables
	 * the timers, shift registers etc. and disables interrupting from the chip"
	 */
	public void m6522_reset() {
		init_port(this.pa);
		init_port(this.pb);
		init_timer(this.t1, true);
		init_timer(this.t2, true);
		init_interrupt(this.intr);
		this.acr = 0;
		this.pcr = 0;
	}

	/*--- delay-pipeline ---*/
	/** set a new state at pipeline pos */
	private int _M6522_PIP_SET(int pip, int offset, int pos) {
		pip |= (1 << (offset + pos));
		return pip;
	}

	/** clear a new state at pipeline pos */
	private int _M6522_PIP_CLR(int pip, int offset, int pos) {
		pip &= ~(1 << (offset + pos));
		return pip;
	}

	/** reset an entire pipeline */
	private int _M6522_PIP_RESET(int pip, int offset) {
		pip &= ~(0xFF << offset);
		return pip;
	}

	/** test pipeline state, pos 0 is the 'output bit' */
	private boolean _M6522_PIP_TEST(int pip, int offset, int pos) {
		return 0 != (pip & (1 << (offset + pos)));
	}

	/*--- port implementation ---*/
	private void _m6522_read_port_pins(Sim6522Input input) {
		/* check CA1/CA2/CB1/CB2 triggered */
		boolean new_ca1 = input.ca1;
		boolean new_ca2 = input.ca2;
		boolean new_cb1 = input.cb1;
		boolean new_cb2 = input.cb2;
		this.pa.c1_triggered = (this.pa.c1_in != new_ca1)
				&& ((new_ca1 && M6522_PCR_CA1_LOW_TO_HIGH()) || (!new_ca1 && M6522_PCR_CA1_HIGH_TO_LOW()));
		this.pa.c2_triggered = (this.pa.c2_in != new_ca2)
				&& ((new_ca2 && M6522_PCR_CA2_LOW_TO_HIGH()) || (!new_ca2 && M6522_PCR_CA2_HIGH_TO_LOW()));
		this.pb.c1_triggered = (this.pb.c1_in != new_cb1)
				&& ((new_cb1 && M6522_PCR_CB1_LOW_TO_HIGH()) || (!new_ca1 && M6522_PCR_CB1_HIGH_TO_LOW()));
		this.pb.c2_triggered = (this.pb.c2_in != new_cb2)
				&& ((new_cb2 && M6522_PCR_CB2_LOW_TO_HIGH()) || (!new_ca2 && M6522_PCR_CB2_HIGH_TO_LOW()));
		this.pa.c1_in = new_ca1;
		this.pa.c2_in = new_cb2;
		this.pb.c1_in = new_cb1;
		this.pb.c2_in = new_cb2;

		/*
		 * with latching enabled, only update input register when CA1 / CB1 goes active
		 */
		if (M6522_ACR_PA_LATCH_ENABLE()) {
			if (this.pa.c1_triggered) {
				this.pa.inpr = input.pa;
			}
		} else {
			this.pa.inpr = input.pa;
		}
		if (M6522_ACR_PB_LATCH_ENABLE()) {
			if (this.pb.c1_triggered) {
				this.pb.inpr = input.pb;
			}
		} else {
			this.pb.inpr = input.pb;
		}
	}

	private int _m6522_merge_pb7(int data) {
		if (M6522_ACR_T1_SET_PB7()) {
			data &= ~(1 << 7);
			if (this.t1.t_bit) {
				data |= (1 << 7);
			}
		}
		return data;
	}

	private void _m6522_write_port_pins() {
		this.pa.pins = (this.pa.inpr & ~this.pa.ddr) | (this.pa.outr & this.pa.ddr);
		this.pb.pins = _m6522_merge_pb7((this.pb.inpr & ~this.pb.ddr) | (this.pb.outr & this.pb.ddr));

	}


	private void _m6522_set_intr(int data) {
		this.intr.ifr |= data;
	}

	/** Clears the given interrupt flags */
	private void _m6522_clear_intr(int data) {
		this.intr.ifr &= ~data;
		/* clear main interrupt flag? */
		if (0 == (this.intr.ifr & this.intr.ier & 0x7F)) {
			this.intr.ifr &= 0x7F;
			/* cancel any interrupts in the delay pipeline */
			this.intr.pip = _M6522_PIP_RESET(this.intr.pip, PIP_IRQ);
		}
	}

	private void _m6522_clear_pa_intr() {
		_m6522_clear_intr(IRQ_CA1 | ((M6522_PCR_CA2_IND_IRQ()) ? 0 : IRQ_CA2));
	}

	private void _m6522_clear_pb_intr() {
		_m6522_clear_intr(IRQ_CB1 | ((M6522_PCR_CB2_IND_IRQ() ? 0 : IRQ_CB2)));
	}

	private void _m6522_write_ier(int data) {
		if ((data & 0x80) > 0) {
			this.intr.ier |= data & 0x7F;
		} else {
			this.intr.ier &= ~(data & 0x7F);
		}
	}

	private void _m6522_write_ifr(int data) {
		if ((data & IRQ_ANY) > 0) {
			data = 0x7F;
		}
		_m6522_clear_intr(data);
	}

	/*
	 * On timer behaviour:
	 * 
	 * http://forum.6502.org/viewtopic.php?f=4&t=2901
	 * 
	 * (essentially: T1 is always reloaded from latch, both in continuous and
	 * oneshot mode, while T2 is never reloaded)
	 */
	private void _m6522_tick_t1() {

		/* decrement counter? */
		if (_M6522_PIP_TEST(this.t1.pip, PIP_TIMER_COUNT, 0)) {
			this.t1.counter--;
		}

		/* timer underflow? */
		this.t1.t_out = (0xFFFF == this.t1.counter);
		if (this.t1.t_out) {
			/* continuous or oneshot mode? */
			if (M6522_ACR_T1_CONTINUOUS()) {
				this.t1.t_bit = !this.t1.t_bit;
				/* trigger T1 interrupt on each underflow */
				_m6522_set_intr(IRQ_T1);
			} else {
				if (!this.t1.t_bit) {
					/* trigger T1 only once */
					_m6522_set_intr(IRQ_T1);
					this.t1.t_bit = true;
				}
			}
			/*
			 * reload T1 from latch on each underflow, this happens both in oneshot and
			 * continous mode
			 */
			_M6522_PIP_SET(this.t1.pip, PIP_TIMER_LOAD, 1);
		}

		/* reload timer from latch? */
		if (_M6522_PIP_TEST(this.t1.pip, PIP_TIMER_LOAD, 0)) {
			this.t1.counter = this.t1.latch;
		}
	}

	private void _m6522_tick_t2(Sim6522Input input) {

		/* either decrement on PB6, or on tick */
		if (M6522_ACR_T2_COUNT_PB6()) {
			/* count falling edge of PB6 */
			if ((input.pb & 0x80) == 0 && (this.last_input.pb & 0x80) > 0) {
				this.t2.counter--;
			}
		} else if (_M6522_PIP_TEST(this.t2.pip, PIP_TIMER_COUNT, 0)) {
			this.t2.counter--;
		}

		/* underflow? */
		this.t2.t_out = (0xFFFF == this.t2.counter);
		if (this.t2.t_out) {
			/* t2 is always oneshot */
			if (!this.t2.t_bit) {
				/* FIXME: 6526-style "Timer B Bug"? */
				_m6522_set_intr(IRQ_T2);
				this.t2.t_bit = true;
			}
			/* NOTE: T2 never reloads from latch on hitting zero */
		}
	}

	private void _m6522_tick_pipeline() {
		/* feed counter pipelines, both counters are always counting */
		this.t1.pip = _M6522_PIP_SET(this.t1.pip, PIP_TIMER_COUNT, 2);
		this.t2.pip = _M6522_PIP_SET(this.t2.pip, PIP_TIMER_COUNT, 2);

		/* interrupt pipeline */
		if ((this.intr.ifr & this.intr.ier) > 0) {
			this.intr.pip = _M6522_PIP_SET(this.intr.pip, PIP_IRQ, 1);
		}

		/* tick pipelines forward */
		this.t1.pip = (this.t1.pip >> 1) & 0x7F7F;
		this.t2.pip = (this.t2.pip >> 1) & 0x7F7F;
		this.intr.pip = (this.intr.pip >> 1) & 0x7F7F;
	}

	private void _m6522_update_cab() {
		if (this.pa.c1_triggered) {
			_m6522_set_intr(IRQ_CA1);
			if (M6522_PCR_CA2_AUTO_HS()) {
				this.pa.c2_out = true;
			}
		}
		if (this.pa.c2_triggered && M6522_PCR_CA2_INPUT()) {
			_m6522_set_intr(IRQ_CA2);
		}
		if (this.pb.c1_triggered) {
			_m6522_set_intr(IRQ_CB1);
			if (M6522_PCR_CB2_AUTO_HS()) {
				this.pb.c2_out = true;
			}
		}
		// FIXME: shift-in/out on CB1
		if (this.pb.c2_triggered && M6522_PCR_CB2_INPUT()) {
			_m6522_set_intr(IRQ_CB2);
		}
	}

	private void _m6522_update_irq() {

		/* main interrupt bit (delayed by pip) */
		if (_M6522_PIP_TEST(this.intr.pip, PIP_IRQ, 0)) {
			this.intr.ifr |= (1 << 7);
		}

		/* merge IRQ bit */
        output.irq = 0 == (this.intr.ifr & (1 << 7)); // IRQ is active low
	}

	/* perform a tick */
	private void update_internal_state(Sim6522Input input) {
		_m6522_read_port_pins(input);
		_m6522_update_cab();
		_m6522_tick_t1();
		_m6522_tick_t2(input);
		_m6522_update_irq();
		_m6522_write_port_pins();
		_m6522_tick_pipeline();
	}

	private void update_output() {
		_m6522_write_port_pins();		// call this again to reflect changes of the registers
		output.pa = this.pa.pins & this.pa.ddr;
		output.pa_dir = this.pa.ddr;

		output.pb = this.pb.pins & this.pb.ddr;
		output.pb_dir = this.pb.ddr;

		output.ca2 = this.pa.c2_out;
		output.ca2_dir = M6522_PCR_CA2_OUTPUT();

		output.cb2 = this.pb.c2_out;
		output.cb2_dir = M6522_PCR_CB2_OUTPUT();
	}

	/**
	 * Read the content of the given register.
	 * 
	 * @param addr 4 bit address of the register
	 * @return 8 bit register content
	 */
	private int read_register(int addr) {
		int data = 0;
		switch (addr) {
		case REG_RB:
			if (M6522_ACR_PB_LATCH_ENABLE()) {
				data = this.pb.inpr;
			} else {
				data = this.pb.pins;
			}
			_m6522_clear_pb_intr();
			break;

		case REG_RA:
			if (M6522_ACR_PA_LATCH_ENABLE()) {
				data = this.pa.inpr;
			} else {
				data = this.pa.pins;
			}
			_m6522_clear_pa_intr();
			if (M6522_PCR_CA2_PULSE_OUTPUT() || M6522_PCR_CA2_AUTO_HS()) {
				this.pa.c2_out = false;
			}
			if (M6522_PCR_CA2_PULSE_OUTPUT()) {
				/* FIXME: pulse output delay pipeline */
			}
			break;

		case REG_DDRB:
			data = this.pb.ddr;
			break;

		case REG_DDRA:
			data = this.pa.ddr;
			break;

		case REG_T1CL:
			data = this.t1.counter & 0xFF;
			_m6522_clear_intr(IRQ_T1);
			break;

		case REG_T1CH:
			data = this.t1.counter >> 8;
			break;

		case REG_T1LL:
			data = this.t1.latch & 0xFF;
			break;

		case REG_T1LH:
			data = this.t1.latch >> 8;
			break;

		case REG_T2CL:
			data = this.t2.counter & 0xFF;
			_m6522_clear_intr(IRQ_T2);
			break;

		case REG_T2CH:
			data = this.t2.counter >> 8;
			break;

		case REG_SR:
			/* FIXME */
			break;

		case REG_ACR:
			data = this.acr;
			break;

		case REG_PCR:
			data = this.pcr;
			break;

		case REG_IFR:
			data = this.intr.ifr;
			break;

		case REG_IER:
			data = this.intr.ier | 0x80;
			break;

		case REG_RA_NOH:
			if (M6522_ACR_PA_LATCH_ENABLE()) {
				data = this.pa.inpr;
			} else {
				data = this.pa.pins;
			}
			break;
		}
		return data;
	}

	/**
	 * Write the data byte to a register.
	 * 
	 * @param addr 4 bit register address
	 * @param data 8 bit data value
	 */
	private void write_register(int addr, int data) {
		switch (addr) {
		case REG_RB:
			this.pb.outr = data;
			_m6522_clear_pb_intr();
			if (M6522_PCR_CB2_AUTO_HS()) {
				this.pb.c2_out = false;
			}
			break;

		case REG_RA:
			this.pa.outr = data;
			_m6522_clear_pa_intr();
			if (M6522_PCR_CA2_PULSE_OUTPUT() || M6522_PCR_CA2_AUTO_HS()) {
				this.pa.c2_out = false;
			}
			if (M6522_PCR_CA2_PULSE_OUTPUT()) {
				/* FIXME: pulse output delay pipeline */
			}
			break;

		case REG_DDRB:
			this.pb.ddr = data;
			break;

		case REG_DDRA:
			this.pa.ddr = data;
			break;

		case REG_T1CL:
		case REG_T1LL:
			this.t1.latch = (this.t1.latch & 0xFF00) | data;
			break;

		case REG_T1CH:
			this.t1.latch = (data << 8) | (this.t1.latch & 0x00FF);
			_m6522_clear_intr(IRQ_T1);
			this.t1.t_bit = false;
			this.t1.counter = this.t1.latch;
			break;

		case REG_T1LH:
			this.t1.latch = (data << 8) | (this.t1.latch & 0x00FF);
			_m6522_clear_intr(IRQ_T1);
			break;

		case REG_T2CL:
			this.t2.latch = (this.t2.latch & 0xFF00) | data;
			break;

		case REG_T2CH:
			this.t2.latch = (data << 8) | (this.t2.latch & 0x00FF);
			_m6522_clear_intr(IRQ_T2);
			this.t2.t_bit = false;
			this.t2.counter = this.t2.latch;
			break;

		case REG_SR:
			/* FIXME */
			break;

		case REG_ACR:
			this.acr = data;
			/* FIXME: shift timer */
			/*
			 * if (M6522_ACR_T1_CONTINUOUS(c)) { // FIXME: continuous counter delay?
			 * _M6522_PIP_CLR(this.t1.pip, M6522_PIP_TIMER_COUNT, 0);
			 * _M6522_PIP_CLR(this.t1.pip, M6522_PIP_TIMER_COUNT, 1); }
			 */
			/*
			 * FIXME(?) this properly transitions T2 from counting PB6 to clock counter mode
			 */
			if (!M6522_ACR_T2_COUNT_PB6()) {
				this.t2.pip = _M6522_PIP_CLR(this.t2.pip, PIP_TIMER_COUNT, 0);
			}
			break;

		case REG_PCR:
			this.pcr = data;
			if (M6522_PCR_CA2_FIX_OUTPUT()) {
				this.pa.c2_out = M6522_PCR_CA2_OUTPUT_LEVEL();
			}
			if (M6522_PCR_CB2_FIX_OUTPUT()) {
				this.pb.c2_out = M6522_PCR_CB2_OUTPUT_LEVEL();
			}
			break;

		case REG_IFR:
			_m6522_write_ifr(data);
			break;

		case REG_IER:
			_m6522_write_ier(data);
			break;

		case REG_RA_NOH:
			this.pa.outr = data;
			break;
		}
	}

	/**
	 * Simulate a single clock cycle.
	 * 
	 * This should be called on the rising edge of Φ2 (the clock signal), as at this
	 * point the CPU address, r/w and data lines are valid.
	 * 
	 * @param input The values of all input pins.
	 * 
	 * @return The new output pin states.
	 */
	public Sim6522Output tick(Sim6522Input input) {
		
		if (!input.reset) { // active low
			m6522_reset();
			output.data = 0;
			update_output();
			return output;
		}

		/*
		 * The original m6522.h had this after the register ops, causing register port
		 * reads to reflect the state of the previous cycle. The datasheet is not clear
		 * on this. IMHO the datasheet does not reflect this and port reads should be
		 * the current state.
		 */
		update_internal_state(input);

		if (input.cs1 && !input.cs2) {
			int addr = input.rs;
			if (input.rw) {
				// Read register
                output.data = read_register(addr);
			} else {
				// Write Register
				int data = input.data;
				write_register(addr, data);
			}
		}
		update_output();

		this.last_input = input.copy();
		return output;
	}
}
