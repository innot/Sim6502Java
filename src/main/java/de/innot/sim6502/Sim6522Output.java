package de.innot.sim6502;

public class Sim6522Output {

	/** IRQ Signal, active low **/
	public boolean irq = true;

	/** Port A **/
	public int pa = 0;

	/** Port A Data Direction. Bits 0-7: 0 = Pin is Input, 1 = Pin is Output */
	public int pa_dir = 0;

	/** Port B **/
	public int pb = 0;

	/** Port B Data Direction. Bits 0-7: 0 = Pin is Input, 1 = Pin is Output */
	public int pb_dir = 0;

	/** CA1, Control Bit 1, Port A **/
	// public boolean ca1; // This Pin is input only

	/** CA2, Control Bit 2, Port A **/
	public boolean ca2 = false;

	/** CA2 Direction. 0 = Input, 1 = Output **/
	public boolean ca2_dir = false;

	/** CB1, Control Bit 1, Port B **/
	public boolean cb1 = false;

	/** CB1 Direction. 0 = Input, 1 = Output **/
	public boolean cb1_dir = false;

	/** CB2, Control Bit 2, Port B **/
	public boolean cb2 = false;

	/** CB2 Direction. 0 = Input, 1 = Output **/
	public boolean cb2_dir = false;
	
	/** Data **/
	public int data = 0;
}
