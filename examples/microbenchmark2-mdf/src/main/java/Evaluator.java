import java.util.List;

import uk.ac.imperial.lsds.seep.api.API;
import uk.ac.imperial.lsds.seep.api.SeepTask;
import uk.ac.imperial.lsds.seep.api.data.ITuple;



public class Evaluator implements SeepTask {

	private int totalCalls = 0;
	
	@Override
	public void close() {
		System.out.println(this + " - TC: " + totalCalls);
	}

	@Override
	public void processData(ITuple arg0, API arg1) {
		totalCalls++;
		// do some calculation with the utility function 
		arg1.storeEvaluateResults(System.currentTimeMillis()); // a long, as an abstract notion of quality
		// propagate the results downstream
		arg1.send(arg0.getData());
	}

	@Override
	public void processDataGroup(List<ITuple> arg0, API arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setUp() {
		// TODO Auto-generated method stub

	}

}
