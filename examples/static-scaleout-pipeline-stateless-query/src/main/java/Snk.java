import java.util.List;

import uk.ac.imperial.lsds.seep.api.API;
import uk.ac.imperial.lsds.seep.api.SeepTask;
import uk.ac.imperial.lsds.seep.api.data.ITuple;
import uk.ac.imperial.lsds.seep.api.operator.sinks.Sink;


public class Snk implements Sink {

	private final int PERIOD = 1000;
	private long time;
	private int count;
	
	@Override
	public void setUp() {
		// TODO Auto-generated method stub
	}

	@Override
	public void processData(ITuple data, API api) {
		count++;
//		int userId = data.getInt("userId");
//		long ts = data.getLong("ts");
//		String text = data.getString("text");
//		
//		System.out.println("UID: "+userId+" ts: "+ts+" text: "+text);
		if((System.currentTimeMillis() - time) > PERIOD){
			System.out.println("e/s: "+count);
			count = 0;
			time = System.currentTimeMillis();
		}
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub
	}

	@Override
	public void processDataGroup(List<ITuple> arg0, API arg1) {
		// TODO Auto-generated method stub
		
	}

}
