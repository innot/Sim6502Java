/**
 *
 */
package de.innot.sim6502.test;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.innot.sim6502.Sim6520;
import de.innot.sim6502.Sim6520Input;
import de.innot.sim6502.Sim6520Output;

/**
 *
 */
class Sim6520Test {

    Sim6520 pia;
    Sim6520Input input;
    Sim6520Output output;

    final static int REG_RA = Sim6520.REG_RA;
    final static int REG_RB = Sim6520.REG_RB;
    final static int REG_CRA = Sim6520.REG_CRA;
    final static int REG_CRB = Sim6520.REG_CRB;

    @BeforeEach
    void beforeEach() {
        pia = new Sim6520();
        input = new Sim6520Input();
        pia.tick(input); // Do a cycle to set up the internal registers.
        input.reset = true; // go out of reset
    }

    /**
     * Test Register Read/Write
     */
    @Test
    void test_register_read_write() {
        writeRegister(REG_RA, 0x00);
        assertEquals(0x00, readRegister(REG_RA));
        writeRegister(REG_RA, 0xff);
        assertEquals(0xff, readRegister(REG_RA));

        writeRegister(REG_RB, 0x00);
        assertEquals(0x00, readRegister(REG_RB));
        writeRegister(REG_RB, 0xff);
        assertEquals(0xff, readRegister(REG_RB));

        writeRegister(REG_CRA, 0x00);
        assertEquals(0x00, readRegister(REG_CRA));
        writeRegister(REG_CRA, 0xff);
        assertEquals(0x3f, readRegister(REG_CRA));  // top two bits can not be written to.

        writeRegister(REG_CRB, 0x00);
        assertEquals(0x00, readRegister(REG_CRB));
        writeRegister(REG_CRB, 0xff);
        assertEquals(0x3f, readRegister(REG_CRB));  // top two bits can not be written to.
    }


    /**
     * Test DDR
     */
    @Test
    void test_port_ddr() {
        // Default: DDR is all input.
        output = pia.tick(input);

        assertEquals(0x00, output.pa_dir);
        assertEquals(0x00, output.pa);
        assertEquals(0x00, output.pb_dir);
        assertEquals(0x00, output.pb);

        // check that setting PA does not generate output
        writeRegister(REG_CRA, Sim6520.CR_ORA_SELECT); // REG_RA to PORT
        writeRegister(REG_RA, 0xaa);
        assertEquals(0x00, output.pa);

        writeRegister(REG_CRB, Sim6520.CR_ORA_SELECT); // REG_RB to PORT
        writeRegister(REG_RB, 0x55);
        assertEquals(0x00, output.pb);

        // set port A lower 4 bits to output
        writeRegister(REG_CRA, 0x00); // REG_RA to DDR
        writeRegister(REG_RA, 0x0f);
        assertEquals(0x0f, output.pa_dir);
        assertEquals(0x0a, output.pa);

        // set port B upper 4 bits to output
        writeRegister(REG_CRB, 0x00); // REG_RB to DDR
        writeRegister(REG_RB, 0xf0);
        assertEquals(0xf0, output.pb_dir);
        assertEquals(0x50, output.pb);
    }

    /**
     * Test simple irq on all 4 lines
     */
    @Test
    public void test_simple_irq() {

        input.ca1 = true;
        input.ca2 = true;
        input.cb1 = true;
        input.cb2 = true;

        int cra = Sim6520.CR_ORA_SELECT;// we do not need DDR, so set to ORA
        int crb = Sim6520.CR_ORA_SELECT;// we do not need DDR, so set to ORA

        writeRegister(REG_CRA, cra);
        writeRegister(REG_CRB, crb);

        // Enable CA1 for IRQ, default negative edge
        writeRegister(REG_CRA, cra |= Sim6520.CR_IRQ1_ENABLE);
        assertTrue(output.irqa);
        assertTrue(output.irqb);

        input.ca1 = false;
        output = phi2_high_tick(input);
        assertFalse(output.irqa);
        assertTrue(output.irqb);

        // clear IRQ Flag by reading from the output register
        readRegister(REG_RA);
        assertTrue(output.irqa);

        // Enable CA2 for IRQ, default negative edge
        writeRegister(REG_CRB, Sim6520.CR_IRQ2_ENABLE);
        assertTrue(output.irqa);
        assertTrue(output.irqb);

        input.ca2 = false;
        output = phi2_high_tick(input);
        assertFalse(output.irqa);
        assertTrue(output.irqb);

        // clear IRQ Flag by reading from the output register
        readRegister(REG_RA);
        assertTrue(output.irqa);

        // Check for IRQ on positive edge
        input.ca1 = false;
        writeRegister(REG_CRA, cra |= Sim6520.CR_IRQ1_ENABLE | Sim6520.CR_IRQ1_TRANSITION);
        assertTrue(output.irqa);
        assertTrue(output.irqb);

        input.ca1 = true;
        output = phi2_high_tick(input);
        assertFalse(output.irqa);
        assertTrue(output.irqb);

        // clear IRQ Flag by reading from the output register
        readRegister(REG_RA);
        assertTrue(output.irqa);

        // same for CA2
        input.ca2 = false;
        writeRegister(REG_CRA, cra |= Sim6520.CR_IRQ2_ENABLE | Sim6520.CR_IRQ2_TRANSITION);
        assertTrue(output.irqa);
        assertTrue(output.irqb);

        input.ca2 = true;
        output = phi2_high_tick(input);
        assertFalse(output.irqa);
        assertTrue(output.irqb);

        // clear IRQ Flag by reading from the output register
        readRegister(REG_RA);
        assertTrue(output.irqb);

        // TODO: test the same with CB1 / CB2
    }

    @Test
    public void test_irq_problem() {
        // This tests an issue that occured when running the PET Diagnostics ROM
        input.ca1 = true;
        input.ca2 = true;
        input.cb1 = true;
        input.cb2 = true;

        for (int reg : new int[]{REG_CRA, REG_CRB}) {

            writeRegister(reg, 0x3c); // with C[A|B]2 to output and IRQ[A|B]2 set no IRQs should occur
            assertTrue(output.irqa);
            assertTrue(output.irqb);

            this.disable();
            phi2_high_tick(input);
            output = phi2_low_tick(input);
            assertTrue(output.irqa);
            assertTrue(output.irqb);

            input.cb1 = false;
            phi2_high_tick(input);
            output = phi2_low_tick(input);
            assertTrue(output.irqa);
            assertTrue(output.irqb);
        }
    }

    public void writeRegister(int register, int value) {
        assert (register >= 0 && register <= 15);
        assert (value >= 0 && value <= 255);

        this.enable();
        input.rw = false;
        input.rs = register;
        input.data = value;
        phi2_high_tick(input);
        output = phi2_low_tick(input);
        this.disable();
    }

    public int readRegister(int register) {
        assert (register >= 0 && register <= 15);

        this.enable();
        input.rw = true;
        input.rs = register;
        input.data = 0x00;
        phi2_high_tick(input);
        output = phi2_low_tick(input);
        this.disable();
        return output.data;
    }

    public Sim6520Output phi2_low_tick(Sim6520Input input) {
        input.phi2 = false;
        return pia.tick(input);
    }

    public Sim6520Output phi2_high_tick(Sim6520Input input) {
        input.phi2 = true;
        return pia.tick(input);
    }

    /**
     * Set the CS lines to active to enable the chip.
     */
    private void enable() {
        input.cs0 = true;
        input.cs1 = true;
        input.cs2 = false;
    }

    public void disable() {
        input.cs0 = false;
        input.cs1 = false;
        input.cs2 = true;
    }

}
