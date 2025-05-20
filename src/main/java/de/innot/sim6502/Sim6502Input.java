package de.innot.sim6502;

public class Sim6502Input {

	/** RESET line. Active low. Set to <code>false</code> to inititate a reset. */
	public boolean reset = false;

	/** NMI line. Active low. NMI is triggered on a falling edge of this line. */
	public boolean nmi = true;

	/** IRQ line. Active low. IRQ is level triggered when this line is <code>false</code>. */
	public boolean irq = true;

	/** RDY line. Active high. Set to <code>true</code> to get the 6502 out of the halt state. */
	public boolean ready = false;

	/** The data bits 0-7 of the 6502. */
	public int data = 0x00;
}
