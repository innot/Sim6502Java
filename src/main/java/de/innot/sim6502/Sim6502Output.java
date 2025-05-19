package de.innot.sim6502;

public class Sim6502Output {
	public int addr = 0x0000;
	public int data = 0x00;
	public boolean dataHighZ = true;
	public boolean rw = true;
	public boolean sync = false;
}
