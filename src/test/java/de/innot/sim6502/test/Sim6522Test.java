/**
 *
 */
package de.innot.sim6502.test;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.innot.sim6502.Sim6522;
import de.innot.sim6502.Sim6522Input;
import de.innot.sim6502.Sim6522Output;

/**
 *
 */
class Sim6522Test {

    Sim6522 via;
    Sim6522Input input;
    Sim6522Output output;

    @BeforeEach
    void beforeEach() {
        via = new Sim6522();
        input = new Sim6522Input();
        via.tick(input);    // Do one cycle to set up the internal registers.
        input.reset = true;
    }

    /**
     * Test method for {@link Sim6522#Sim6522()}.
     */
    @Test
    void test_port_ddr() {
        // Default: DDR is all input.
        output = via.tick(input);

        assertEquals(0x00, output.pa_dir);
        assertEquals(0x00, output.pa);
        assertEquals(0x00, output.pb_dir);
        assertEquals(0x00, output.pb);

        // check that setting PA does not generate output
        writeRegister(Sim6522.REG_RA, 0xaa);
        assertEquals(0x00, output.pa);

        writeRegister(Sim6522.REG_RB, 0x55);
        assertEquals(0x00, output.pb);

        // set port A lower 4 bits to output
        writeRegister(Sim6522.REG_DDRA, 0x0f);
        assertEquals(0x0f, output.pa_dir);
        assertEquals(0x0a, output.pa);

        // set port B upper 4 bits to output
        writeRegister(Sim6522.REG_DDRB, 0xf0);
        assertEquals(0xf0, output.pb_dir);
        assertEquals(0x50, output.pb);
    }

    @Test
    void test_port_latch() {

        // check port without latch
        int data;
        for (int i = 0; i <= 0xff; i++) {
            input.pa = i;
            data = readRegister(Sim6522.REG_RA);
            assertEquals(i, data);

            input.pb = i;
            data = readRegister(Sim6522.REG_RB);
            assertEquals(i, data);
        }

        // now activate latch for both ports
        writeRegister(Sim6522.REG_ACR, 0b00000011);

        // the current port value should still be the last set (0xff), even if another
        // input is at the port
        input.pa = 0xaa;
        input.pb = 0x55;

        for (int i = 0; i < 10; i++) {
            data = readRegister(Sim6522.REG_RA);
            assertEquals(0xff, data);

            data = readRegister(Sim6522.REG_RB);
            assertEquals(0xff, data);
        }

        // Now latch the ports. Default is on a negative edge.
        input.ca1 = true;
        input.cb1 = true;
        via.tick(input);

        // still no latching
        data = readRegister(Sim6522.REG_RA);
        assertEquals(0xff, data);

        data = readRegister(Sim6522.REG_RB);
        assertEquals(0xff, data);

        input.ca1 = false;        // negative edge should trigger the latch
        input.cb1 = false;
        via.tick(input);

        // now the port values should be updated and changes in the port are ignored.
        input.pa = 0xde;
        input.pb = 0xad;
        data = readRegister(Sim6522.REG_RA);
        assertEquals(0xaa, data);

        input.pa = 0xbe;
        input.pb = 0xef;
        data = readRegister(Sim6522.REG_RB);
        assertEquals(0x55, data);

        // now check latching on a positive edge
        writeRegister(Sim6522.REG_PCR, 0b00010001);    // set PCR CB1 Control and CA1 Control to 1
        input.pa = 0x11;
        input.pb = 0x22;
        input.ca1 = true;
        input.cb1 = true;
        via.tick(input);

        input.pa = 0xde;
        input.pb = 0xad;
        data = readRegister(Sim6522.REG_RA);
        assertEquals(0x11, data);

        input.pa = 0xbe;
        input.pb = 0xef;
        data = readRegister(Sim6522.REG_RB);
        assertEquals(0x22, data);


    }

    @Test
    void test_timer1_one_shot() {

        writeRegister(Sim6522.REG_IER, 0b1010_0000);    // enable T2 IRQ

        writeRegister(Sim6522.REG_T2CL, 0); // IRQ after ten cycles
        writeRegister(Sim6522.REG_T2CH, 1);    // start timer
    }

    @Test
    void test_timer2_one_shot() {

        int reg;

        // Test One-Shot mode with IRQ enabled
        writeRegister(Sim6522.REG_IER, 0b1010_0000);    // enable T2 IRQ

        writeRegister(Sim6522.REG_T2CL, 0); // IRQ after ten cycles
        writeRegister(Sim6522.REG_T2CH, 1);    // start timer

        for (int i = 255; i >= 0; --i) {
            reg = readRegister(Sim6522.REG_T2CL);
            assertEquals(i, reg);
            assertTrue(output.irq);
        }

        // The next tick should cause an irq
        output = via.tick(input);
        assertFalse(output.irq);

        // Check the flags are set correctly
        reg = readRegister(Sim6522.REG_IFR);
        assertEquals(0b10100000, reg & 0b10100000);

        // Check that the Counter high byte is set to 0xff
        assertEquals(0xff, readRegister(Sim6522.REG_T2CH));

        // Check that the Counter high byte is 0xff - 3. This also resets the IRQ line
        reg = readRegister(Sim6522.REG_T2CL);
        assertEquals(0xff - 3, reg);

        output = via.tick(input);
        assertTrue(output.irq);
    }

    @Test
    void test_timer2_pulse_counting() {

        input.pb = 0b0100_0000;     // Set PB6 high

        writeRegister(Sim6522.REG_IER, 0b1010_0000);    // enable T2 IRQ
        writeRegister(Sim6522.REG_ACR, 0b0010_0000);    // set T2 to pulse counting

        final int pulses = 10;
        writeRegister(Sim6522.REG_T2CL, pulses);     // IRQ after ten pulses
        writeRegister(Sim6522.REG_T2CH, 0);    // start timer

        for (int i = pulses; i >= 0; --i) {
            assertEquals(i, readRegister(Sim6522.REG_T2CL));
            assertTrue(output.irq);
            input.pb = 0x00;
            output = via.tick(input);
            input.pb = 0b0100_0000;
            output = via.tick(input);
        }

        // The last tick in the loop should have caused an IRQ
        assertFalse(output.irq);

        // Clear by writing to T2CH
        writeRegister(Sim6522.REG_T2CH, 0);
        output = via.tick(input);
        assertTrue(output.irq);
    }

    public void writeRegister(int register, int value) {
        assert (register >= 0 && register <= 15);
        assert (value >= 0 && value <= 255);

        this.enable();
        input.rw = false;
        input.rs = register;
        input.data = value;
        output = via.tick(input);
        this.disable();
    }

    public int readRegister(int register) {
        assert (register >= 0 && register <= 15);

        this.enable();
        input.rw = true;
        input.rs = register;
        input.data = 0x00;
        output = via.tick(input);
        this.disable();
        return output.data;
    }

    /**
     * Set the CS lines to active the chip.
     */
    private void enable() {
        input.cs1 = true;
        input.cs2 = false;
    }

    public void disable() {
        input.cs1 = false;
        input.cs2 = true;
    }

}
