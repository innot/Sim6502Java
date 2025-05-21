package de.innot.sim6502.test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import de.innot.sim6502.Sim6502;
import de.innot.sim6502.Sim6502Input;
import de.innot.sim6502.Sim6502Output;

class Sim6502Test {

	private static class Error {
		public int in_test_nr = 0;
		public int at_addr = 0;
	}

	@Disabled	// This test is very slow. It is disabled by default.
	@Test
	public void testFunctional() {
		Memory mem = new Memory("6502_functional_test.hex");

		// patch reset vector to point at the start of the test program
		mem.write(0xfffc, 0x00);
		mem.write(0xfffd, 0x04);

		Error error = this.run_processor(mem, 1);
		if (error != null) {
			String msg = "Test %d failed at address 0x%s";
			fail(String.format(msg, error.in_test_nr, toHex(error.at_addr, 4)));
		}
		assertNull(null); // just to make static code analysis happy :-)
	}

	@Test
	public void testIRQs() {
		
		Memory mem = new Memory("6502_interrupt_test.hex");

		// patch reset vector to point at the start of the test program
		mem.write(0xfffc, 0x00);
		mem.write(0xfffd, 0x04);

		Error error = this.run_processor(mem, 0);
		
		if (error != null) {
			String msg = "Test %d failed at address 0x%s";
			fail(String.format(msg, error.in_test_nr, toHex(error.at_addr, 4)));
		}
		assertNull(null);
	}

	private Error run_processor(Memory mem, int verbosity) {

		Sim6502 cpu = new Sim6502();

		Sim6502Input input = new Sim6502Input();
		Sim6502Output output;
		
		int io_port = 0x00;		// initial value of the simulated I/O-Port for IRQ/NMI generation.

		// A few reset cycles to get the sim stable
		input.ready = true;
		input.reset = false;
		for (int i = 0; i < 4; i++)
			cpu.tick(input);

		// start the simulator
		long start_time = System.currentTimeMillis();
		int cycle = 0;
		input.reset = true;
		while (true) {
			cycle++;
			
			input.irq = !((io_port & 0x01) == 0x01);
			input.nmi = !((io_port & 0x02) == 0x02);


			output = cpu.tick(input);

			if (output.rw) {
				// read cycle
				input.data = mem.read(output.addr);
			} else {
				// write cycle
				mem.write(output.addr, output.data);
			}

			if (verbosity >= 2) {
				System.out.println("\nCycle " + cycle);
				System.out.println(cpu.getState());
				System.out.println( //
						"Bus: addr=0x" + Integer.toHexString(output.addr) + //
								" data_out=0x" + Integer.toHexString(output.data & 0xff) + //
								" data_in=0x" + Integer.toHexString(input.data & 0xff) + //
								" r/w=" + ((output.rw) ? "R" : "W"));

			}
			if (output.addr == 0x0200 && !output.rw && verbosity >= 1) {
				int number = output.data;
				System.out.println("Test " + number + " started");
			}
			
			/*
			 * Address 0xbffc is a simulated I/O register to assert the IRQ and NMI lines.
			 */
			if(output.addr == 0xbffc) {
				if (output.rw) {
					input.data = io_port;
				} else {
					io_port = output.data;
				}
			}

			/*
			 * An access of address 0xf000 indicates an error. As this access should be set
			 * in an Subroutine, the return address is taken from the stack and is returned
			 * together with the test number.
			 */
			if (output.addr == 0xf000) {
				// error
				Error err = new Error();
				err.in_test_nr = mem.read(0x200);
				int sp = cpu.getState().getSP() + 0x100;
				int loc = mem.read(sp + 1) & 0xff;
				loc |= (mem.read(sp + 2) << 8) & 0xffff;
				err.at_addr = loc - 2;
				return err;
			}

			/*
			 * An access of address 0xf001 indicates that the program has completed
			 * successfully.
			 */
			if (output.addr == 0xf001) {
				if (verbosity >= 1) {
					double duration = (System.currentTimeMillis() - start_time) / 1000.0;
					double instr_per_sec = (double) cycle / duration;

					System.out.printf("%d cycles in %.2f seconds = Clockspeed of %.3f MHz", cycle, duration,
							instr_per_sec / 1000000);
				}
				return null;
			}
		}
	}

	static String toHex(int value, int digits) {
		String hex = "0000" + Integer.toHexString(value);
		return hex.substring(hex.length() - digits);
	}
}
