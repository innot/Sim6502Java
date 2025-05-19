package de.innot.sim6502;

public class Sim6502Input {

	public boolean reset = false;
	public boolean nmi = false;
	public boolean irq = false;
	public boolean ready = false;
	public int data = 0x00;
}
