# About

Sim6502Java is a cycle accurate simulation of the venerable MOS 6502 processor.

This package also includes simulations of the 6520 PIA and 6522 VIA Chips.

Most of this program is a straight 1:1 conversion from C to Java of the [6502 Simulator](https://github.com/floooh/chips/tree/master/chips)
by Andre Weissflog, although it has been refactored to make it more object oriented and more readable.


# Usage

To use the simulator just instantiate the SIM6502 class.

Then set up an `SIM6502Input` object with the current state of the 6502 inputs.

Calling the `Sim6502.tick()` method will then simulate exactly one clock cycle of the processor.
This method returns an `Sim6502Output` object with the state of all output lines of the simulated processor.

```java
import de.innot.sim6502.Sim6502;
import de.innot.sim6502.Sim6502Input;
import de.innot.sim6502.Sim6502Output;

Sim6502 cpu = new Sim6502();

Sim6502Input input = new Sim6502Input();
Sim6502Output output;

while(true) {
	// Set input state as required
	output = cpu.tick(input);
	// handle output as required
}

```



