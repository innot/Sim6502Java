package de.innot.sim6502;

public class Sim6520Input {

	/** Data bits 0-7 **/
	public int data = 0;

	/** CS1, active high **/
	public boolean cs1 = false;

	/** CS2, active low **/
	public boolean cs2 = true;

	/** CS0, active high **/
	public boolean cs0 = true;

	/** Address Bits 0-1 **/
	public int rs = 0;

	/** R/W, high=Read, low=Write **/
	public boolean rw = true;

	/** Clock **/
	public boolean phi2 = false;

	/** Reset, active low **/
	public boolean reset = false;

	/** Port A **/
	public int pa = 0;

	/** Port B **/
	public int pb = 0;

	/** CA1, Control Bit 1, Port A **/
	public boolean ca1;

	/** CA2, Control Bit 2, Port A **/
	public boolean ca2;

	/** CB1, Control Bit 1, Port B **/
	public boolean cb1;

	/** CB2, Control Bit 2, Port B **/
	public boolean cb2;

	public Sim6520Input copy() {
		Sim6520Input inp = new Sim6520Input();
		inp.data = this.data;
		inp.cs1 = this.cs1;
		inp.cs2 = this.cs2;
		inp.cs0 = this.cs0;
		inp.rs = this.rs;
		inp.rw = this.rw;
		inp.phi2 = this.phi2;
		inp.reset = this.reset;
		inp.pa = this.pa;
		inp.pb = this.pb;
		inp.ca1 = this.ca1;
		inp.ca2 = this.ca2;
		inp.cb1 = this.cb1;
		inp.cb2 = this.cb2;

		return inp;
	}


}
