package uk.ac.imperial.lsds.seepworker.core.output;

import java.nio.channels.WritableByteChannel;

import uk.ac.imperial.lsds.seep.api.DataReference;
import uk.ac.imperial.lsds.seep.api.RuntimeEventRegister;
import uk.ac.imperial.lsds.seep.api.data.OTuple;
import uk.ac.imperial.lsds.seep.core.OBuffer;

public class NullOutputBuffer implements OBuffer {

	@Override
	public int id() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public DataReference getDataReference() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean drainTo(WritableByteChannel channel) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean write(byte[] data, RuntimeEventRegister reg) {
		// TODO Auto-generated method stub
		return false;
	}
	
	@Override
	public boolean write(OTuple o, RuntimeEventRegister reg) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean readyToWrite() {
		// TODO Auto-generated method stub
		return false;
	}
	
	@Override
	public void flush() {
	}

}
