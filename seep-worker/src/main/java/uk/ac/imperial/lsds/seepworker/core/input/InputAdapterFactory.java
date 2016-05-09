package uk.ac.imperial.lsds.seepworker.core.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.imperial.lsds.seep.api.ConnectionType;
import uk.ac.imperial.lsds.seep.api.DataReference;
import uk.ac.imperial.lsds.seep.api.DataStoreType;
import uk.ac.imperial.lsds.seep.api.data.Schema;
import uk.ac.imperial.lsds.seep.comm.IOComm;
import uk.ac.imperial.lsds.seep.core.IBuffer;
import uk.ac.imperial.lsds.seep.core.InputAdapter;
import uk.ac.imperial.lsds.seep.core.InputAdapterReturnType;
import uk.ac.imperial.lsds.seepcontrib.kafka.comm.KafkaDataStream;
import uk.ac.imperial.lsds.seepworker.WorkerConfig;
import uk.ac.imperial.lsds.seepworker.core.DataReferenceManager;
import uk.ac.imperial.lsds.seepworker.core.Dataset;

public class InputAdapterFactory {

	// FIXME: refactor -> all inputadapters that are per buffer do the same. code reuse
	
	final static private Logger LOG = LoggerFactory.getLogger(IOComm.class.getName());
	
	public static List<InputAdapter> buildInputAdapterForStreamId(WorkerConfig wc, 
			int streamId, 
			List<IBuffer> buffers, 
			Set<DataReference> drefs, 
			ConnectionType connType,
			DataReferenceManager drm) {
		List<InputAdapter> ias = new ArrayList<>();
		List<InputAdapter> ias_dataset = new ArrayList<>();
		List<InputAdapter> ias_network = new ArrayList<>();
		List<InputAdapter> ias_file = new ArrayList<>();
		List<InputAdapter> ias_kafka = new ArrayList<>();
		
		List<Dataset> datasets = new ArrayList<>();
		List<IBuffer> network_buffers = new ArrayList<>();
		List<IBuffer> file_buffers = new ArrayList<>();
		List<IBuffer> kafka_buffers = new ArrayList<>();
		
		for(IBuffer ib : buffers) {
			DataReference dRef = ib.getDataReference();
			boolean dRefManaged = dRef.isManaged();
			DataStoreType type = dRef.getDataStore().type();
			
			// Exception, when SYNTHETIC, simply add Dataset and continue
			if(type.equals(DataStoreType.SEEP_SYNTHETIC_GEN)) {
//				((Dataset)ib).markAcess();
				datasets.add((Dataset)ib);
				continue;
			}
			
			// Exception, when ib is FacadeInputBuffer, simply add the InputAdapter and continue
			if(ib instanceof FacadeInputBuffer) {
				InputAdapter ia = buildFacadeInputAdapter(streamId, ib);
				ias.add(ia); // add directly here
				continue;
			}
			
			if(dRefManaged) {
				// The DR is managed by SEEP
				if(drm.doesManageDataReference(dRef.getId()) != null) {
					// In this node. We should have a dataset
					if(! (ib instanceof Dataset)) {
						// throw error
					}
//					((Dataset)ib).markAcess();
					datasets.add((Dataset)ib);
				}
				else {
					// If NOT in this node. Request a NETWORK conn and we should have InputBuffer
					if(! (ib instanceof InputBuffer)) {
						// throw error
					}
					network_buffers.add(ib);
				}
			}
			else if(!dRefManaged) {
				if (type.equals(DataStoreType.NETWORK)) {
					network_buffers.add(ib);
				}
				else if(type.equals(DataStoreType.FILE)) {
					file_buffers.add(ib);
				}
				else if(type.equals(DataStoreType.KAFKA)) {
					kafka_buffers.add(ib);
				}
			}
		}
		
		if(! datasets.isEmpty()) {
			ias_dataset = buildInputAdapterOfTypeDatasetForOps(wc, streamId, drefs, datasets);
		}
		if(! network_buffers.isEmpty()) {
			ias_network = buildInputAdapterOfTypeNetworkForOps(wc, streamId, drefs, network_buffers, connType);
		}
		if(! file_buffers.isEmpty()) {
			ias_file = buildInputAdapterOfTypeFileForOps(wc, streamId, drefs, file_buffers, connType);
		}
		if(! kafka_buffers.isEmpty()) {
			ias_kafka = buildInputAdapterOfTypeKafkaForOps(wc, streamId, drefs, kafka_buffers, connType);
		}
		
		ias.addAll(ias_dataset);
		ias.addAll(ias_network);
		ias.addAll(ias_file);
		ias.addAll(ias_kafka);
		return ias;
	}
	
	private static InputAdapter buildFacadeInputAdapter(int streamId, IBuffer buffer) {
		// Check how to pass the return type information
		InputAdapter ia = new FacadeInputAdapter(streamId, InputAdapterReturnType.ONE, buffer);
		return ia;
	}
		
	private static List<InputAdapter> buildInputAdapterOfTypeDatasetForOps(
			WorkerConfig wc, int streamId, Set<DataReference> drefs, List<Dataset> datasets){
		List<InputAdapter> ias = new ArrayList<>();
		for(Dataset dataset : datasets) {
			InputAdapter ia = new DatasetInputAdapter(wc, streamId, dataset);
			ias.add(ia);
		}
		return ias;
	}

	private static List<InputAdapter> buildInputAdapterOfTypeNetworkForOps(
			WorkerConfig wc, int streamId, Set<DataReference> drefs, List<IBuffer> buffers, ConnectionType connType) {
		List<InputAdapter> ias = new ArrayList<>();
		short cType = connType.ofType();
		Schema expectedSchema = drefs.iterator().next().getDataStore().getSchema();
		if(cType == ConnectionType.ONE_AT_A_TIME.ofType()) {
			// one-queue-per-conn, one-single-queue, etc.
			LOG.info("Creating NETWORK inputAdapter for upstream streamId: {} of type {}", streamId, "ONE_AT_A_TIME");
			for(IBuffer buffer : buffers) {
				InputAdapter ia = new NetworkDataStream(wc, streamId, buffer, expectedSchema);
				ias.add(ia);
			}
		}
		else if(cType == ConnectionType.UPSTREAM_SYNC_BARRIER.ofType()) {
			LOG.info("Creating NETWORK inputAdapter for upstream streamId: {} of type {}", streamId, "UPSTREAM_SYNC_BARRIER");
			InputAdapter ia = new NetworkBarrier(wc, streamId, buffers, expectedSchema);
			ias.add(ia);
		}
		return ias;
	}
	
	private static List<InputAdapter> buildInputAdapterOfTypeFileForOps(
			WorkerConfig wc, int streamId, Set<DataReference> drefs, List<IBuffer> buffers, ConnectionType connType) {
		List<InputAdapter> ias = new ArrayList<>();
		short cType = connType.ofType();
		Schema expectedSchema = drefs.iterator().next().getDataStore().getSchema();
		if(cType == ConnectionType.ONE_AT_A_TIME.ofType()) {
			// one-queue-per-conn, one-single-queue, etc.
			LOG.info("Creating FILE inputAdapter for upstream streamId: {} of type {}", streamId, "ONE_AT_A_TIME");
			for(IBuffer buffer : buffers) {
				InputAdapter ia = new FileDataStream(wc, streamId, buffer, expectedSchema);
				ias.add(ia);
			}
		}
		return ias;
	}
	
	private static List<InputAdapter> buildInputAdapterOfTypeKafkaForOps(
			WorkerConfig wc, int streamId, Set<DataReference> drefs, List<IBuffer> buffers, ConnectionType connType) {
		List<InputAdapter> ias = new ArrayList<>();
		short cType = connType.ofType();
		Schema expectedSchema = drefs.iterator().next().getDataStore().getSchema();
		if(cType == ConnectionType.ONE_AT_A_TIME.ofType()) {
			// one-queue-per-conn, one-single-queue, etc.
			LOG.info("Creating KAFKA inputAdapter for upstream streamId: {} of type {}", streamId, "ONE_AT_A_TIME");
			for(IBuffer buffer : buffers) {
				InputAdapter ia = new KafkaDataStream(streamId, buffer, expectedSchema);
				ias.add(ia);
			}
		}
		return ias;
	}
}
