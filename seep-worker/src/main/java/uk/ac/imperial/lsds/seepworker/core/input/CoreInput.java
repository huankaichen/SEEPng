package uk.ac.imperial.lsds.seepworker.core.input;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryo.Kryo;

import uk.ac.imperial.lsds.seep.api.DataReference;
import uk.ac.imperial.lsds.seep.api.DataStoreType;
import uk.ac.imperial.lsds.seep.comm.Comm;
import uk.ac.imperial.lsds.seep.comm.Connection;
import uk.ac.imperial.lsds.seep.comm.protocol.ProtocolCommandFactory;
import uk.ac.imperial.lsds.seep.comm.protocol.SeepCommand;
import uk.ac.imperial.lsds.seep.core.IBuffer;
import uk.ac.imperial.lsds.seep.core.InputAdapter;
import uk.ac.imperial.lsds.seepworker.WorkerConfig;

public class CoreInput {
	
	final private static Logger LOG = LoggerFactory.getLogger(CoreInput.class);
	
	private WorkerConfig wc;
	private Map<Integer, Set<DataReference>> input;
	private Map<Integer, IBuffer> iBuffers;
	private List<InputAdapter> inputAdapters;
	
	public CoreInput(WorkerConfig wc, Map<Integer, Set<DataReference>> input, Map<Integer, IBuffer> iBuffers, List<InputAdapter> inputAdapters) {
		this.wc = wc;
		this.input = input;
		this.iBuffers = iBuffers;
		this.inputAdapters = inputAdapters;
		
		LOG.info("Configured CoreInput with {} inputAdapters", inputAdapters.size());
	}
	
	public Map<Integer, Set<DataReference>> getDataReferences() {
		return input;
	}
	
	public Set<Integer> getAllDataReferences() {
		Set<Integer> drefs = new HashSet<>();
		for(Set<DataReference> drset : input.values()) {
			for(DataReference dr : drset) {
				drefs.add(dr.getId());
			}
		}
		return drefs;
	}
	
	public List<InputAdapter> getInputAdapters(){
		return inputAdapters;
	}
	
	public boolean requiresConfigureSelectorOfType(DataStoreType type){
		for(InputAdapter ia : inputAdapters){
			if(ia.getDataStoreType().equals(type)){
				return true;
			}
		}
		return false;
	}
	
	public Map<Integer, IBuffer> getIBufferProvider(){
		return iBuffers;
	}
	
	public void requestInputConnections(Comm comm, Kryo k, InetAddress myIp) {
		Map<Integer, IBuffer> externalIBuffers = getIBufferThatRequireNetwork();
		LOG.info("Requesting {} input connections...", externalIBuffers.size());
		for(IBuffer ib : externalIBuffers.values()) {
			DataReference dr = ib.getDataReference();
			if(dr.isManaged()) {
				// Create dataRef request and send to the worker
				SeepCommand requestStreamDataReference = ProtocolCommandFactory.buildRequestDataReference(dr.getId(), myIp, wc.getInt(WorkerConfig.DATA_PORT));
				Connection targetConn = new Connection(dr.getControlEndPoint());
				LOG.trace("Sending RequestStreamDataReference with id: {} to: {} at: {}", dr.getId(), targetConn, System.currentTimeMillis());
				comm.send_object_sync(requestStreamDataReference, targetConn, k);
			}
			else {
				LOG.error("Requesting DataReference that is not managed by SEEPng");
			}
		}
	}

	public Map<Integer, IBuffer> getIBufferThatRequireNetwork() {
		Map<Integer, IBuffer> toReturn = new HashMap<>();
		for(Entry<Integer, IBuffer> entry : iBuffers.entrySet()) {
			int streamId = entry.getKey();
			IBuffer ib = entry.getValue();
			if(ib instanceof InputBuffer) {
				toReturn.put(streamId, ib);
			}
		}
		return toReturn;
	}
	
}
