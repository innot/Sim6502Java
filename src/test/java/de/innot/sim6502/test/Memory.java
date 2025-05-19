package de.innot.sim6502.test;

import java.io.IOException;
import java.io.InputStream;

import cz.jaybee.intelhex.DataListener;
import cz.jaybee.intelhex.IntelHexException;
import cz.jaybee.intelhex.Parser;

public class Memory {
	final byte[] mem;

	public Memory(String memfile) {
		this(0x10000, memfile);
	}

	public Memory(int size, String memfile) {

		mem = new byte[size];
		
		InputStream is = getClass().getClassLoader().getResourceAsStream(memfile);

        assert is != null;
        Parser ihp = new Parser(is);

		ihp.setDataListener(new DataListener() {

			@Override
			public void data(long address, byte[] data) {
				for (int i = 0; i < data.length; i++) {
					mem[(int) (address + i)] = data[i];
				}
			}

			@Override
			public void eof() {
				// do some action
			}
		});

		try {
			ihp.parse();
		} catch (IOException | IntelHexException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public byte read(int addr) {
		if (addr < 0x0000 || addr > 0xffff) {
			throw new IllegalArgumentException("Read from invalid address 0x" + Integer.toHexString(addr));
		}
		return mem[addr];
	}

	public void write(int addr, int data) {
		if (addr < 0x0000 || addr > 0xffff) {
			throw new IllegalArgumentException("Write to invalid address 0x" + Integer.toHexString(addr));
		}
		if (data < 0x00 || data > 0xff) {
			throw new IllegalArgumentException("Write: invalid data " + data);
		}
		mem[addr] = (byte) data;
	}
}
