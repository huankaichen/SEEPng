package uk.ac.imperial.lsds.seepworker.comm;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.imperial.lsds.seep.api.DataStoreType;
import uk.ac.imperial.lsds.seep.api.data.Type;
import uk.ac.imperial.lsds.seep.comm.Connection;
import uk.ac.imperial.lsds.seep.comm.OutgoingConnectionRequest;
import uk.ac.imperial.lsds.seep.core.DataStoreSelector;
import uk.ac.imperial.lsds.seep.core.EventAPI;
import uk.ac.imperial.lsds.seep.core.IBuffer;
import uk.ac.imperial.lsds.seep.core.OBuffer;
import uk.ac.imperial.lsds.seep.infrastructure.SeepEndPointType;
import uk.ac.imperial.lsds.seepworker.WorkerConfig;

public class NetworkSelector implements EventAPI, DataStoreSelector {

	final private static Logger LOG = LoggerFactory.getLogger(NetworkSelector.class);
	
	private ServerSocketChannel listenerSocket;
	private Selector acceptorSelector;
	private boolean acceptorWorking = false;
	private Thread acceptorWorker;
	
	private Reader[] readers;
	private Writer[] writers;
	private CountDownLatch writersConfiguredLatch;
	private int numReaderWorkers;
	private int totalNumberPendingConnectionsPerThread;
	
	private Thread[] readerWorkers;
	private Thread[] writerWorkers;
	private int numWriterWorkers;
	
	private int myId;
	private Map<Integer, SelectionKey> writerKeys;
	private Map<SelectionKey, Integer> readerKeys;
	
	// incoming id -> local input buffer
	private Map<Integer, IBuffer> ibMap;
	private int numUpstreamConnections;
	
	public NetworkSelector(WorkerConfig wc, int opId) {
		this.myId = opId;
		this.writersConfiguredLatch = new CountDownLatch(0); // Initially non-defined, nobody waits here
		this.numReaderWorkers = wc.getInt(WorkerConfig.NUM_NETWORK_READER_THREADS);
		this.numWriterWorkers = wc.getInt(WorkerConfig.NUM_NETWORK_WRITER_THREADS);
		this.totalNumberPendingConnectionsPerThread = wc.getInt(WorkerConfig.MAX_PENDING_NETWORK_CONNECTION_PER_THREAD);
		LOG.info("Configuring NetworkSelector with: {} readers, {} workers and {} maxPendingNetworkConn",
				numReaderWorkers, numWriterWorkers, totalNumberPendingConnectionsPerThread);
		// Create pool of reader threads
		readers = new Reader[numReaderWorkers];
		readerWorkers = new Thread[numReaderWorkers];
		for(int i = 0; i < numReaderWorkers; i++){
			readers[i] = new Reader(i, totalNumberPendingConnectionsPerThread);
			Thread reader = new Thread(readers[i]);
			reader.setName("Network-Reader-"+i);
			readerWorkers[i] = reader;
		}
		// Create pool of writer threads
		writers = new Writer[numWriterWorkers];
		writerWorkers = new Thread[numWriterWorkers];
		for(int i = 0; i < numWriterWorkers; i++){
			writers[i] = new Writer(i);
			Thread writer = new Thread(writers[i]);
			writer.setName("Network-Writer-"+i);
			writerWorkers[i] = writer;
		}
		this.writerKeys = new HashMap<>();
		this.readerKeys = new HashMap<>();
		// Create the acceptorSelector
		try {
			this.acceptorSelector = Selector.open();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static NetworkSelector makeNetworkSelectorWithMap(int myId){
		Properties p = new Properties();
		p.setProperty(WorkerConfig.MASTER_IP, "127.0.0.1");
		p.setProperty(WorkerConfig.PROPERTIES_FILE, "");
		p.setProperty(WorkerConfig.NUM_NETWORK_READER_THREADS, "1");
		p.setProperty(WorkerConfig.NUM_NETWORK_WRITER_THREADS, "1");
		p.setProperty(WorkerConfig.MAX_PENDING_NETWORK_CONNECTION_PER_THREAD, "1");
		WorkerConfig wc = new WorkerConfig(p);
		return new NetworkSelector(wc, myId);
	}
	
	/**
	 * Configures a server in myIp and dataPort. There is one per worker node.
	 * @param myIp
	 * @param dataPort
	 */
	public void configureServerToListen(InetAddress myIp, int dataPort) {
		ServerSocketChannel channel = null;
		try {
			channel = ServerSocketChannel.open();
			SocketAddress sa = new InetSocketAddress(myIp, dataPort);
			channel.configureBlocking(false);
			channel.bind(sa);
			channel.register(acceptorSelector, SelectionKey.OP_ACCEPT);
			LOG.info("Configured Acceptor thread to listen at: {}", sa.toString());
		}
		catch (ClosedChannelException cce) {
			// TODO Auto-generated catch block
			cce.printStackTrace();
		}
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		this.listenerSocket = channel;
		this.acceptorWorker = new Thread(new AcceptorWorker());
		this.acceptorWorker.setName("Network-Acceptor");
	}
	
	/**
	 * Used to notify of new outgoing connection requests. Assigns requests to writer threads that configure them.
	 * @param outgoingConnectionRequest
	 */
	public void configureOutgoingConnection(Set<OutgoingConnectionRequest> outgoingConnectionRequest) {
		LOG.info("Request to configure {} outgoing connections", outgoingConnectionRequest.size());
		int writerIdx = 0;
		int totalWriters = writers.length;
		for(OutgoingConnectionRequest ocr : outgoingConnectionRequest) {
			writers[(writerIdx++)%totalWriters].newConnection(ocr);
		}
		this.writersConfiguredLatch = new CountDownLatch(outgoingConnectionRequest.size()); // Initialize countDown with num of outputConns
	}
	
	/**
	 * Used to configure incoming connections. Assigns requests to reader threads that configure them.
	 * @param ibMap
	 */
	public void configureExpectedIncomingConnection(Map<Integer, IBuffer> ibMap) {
		this.ibMap = ibMap;
		int expectedUpstream = ibMap.size();
		this.numUpstreamConnections  = expectedUpstream;
		LOG.info("Expecting {} upstream connections", numUpstreamConnections);
	}
	
	@Override
	public DataStoreType type() {
		return DataStoreType.NETWORK;
	}
	
	@Override
	public boolean startSelector() {
		// Start readers
		for(Thread r : readerWorkers){
			LOG.info("Starting reader: {}", r.getName());
			r.start();
		}
		// Start writers
		for(Thread w : writerWorkers){
			LOG.info("Starting writer: {}", w.getName());
			w.start();
		}
		try {
			LOG.trace("Waiting for all output connections to configure. Remaining: {}", writersConfiguredLatch.getCount());
			this.writersConfiguredLatch.await();
			LOG.trace("All output connections are now configured");
		} 
		catch (InterruptedException e) {
			e.printStackTrace();
		}
		return true;
	}
	
	@Override
	public boolean initSelector() {
		this.acceptorWorking = true;
		// Check whether there is a network acceptor worker. There won't be one if there are no input network connections.
		if(acceptorWorker != null){
			LOG.info("Starting acceptor thread: {}", acceptorWorker.getName());
			this.acceptorWorker.start();
		}
		return true;
	}
	
	@Override
	public boolean stopSelector() {
		this.acceptorWorking = false;
		
		for(Reader r : readers){
			r.stop();
		}
		for(Writer w : writers){
			w.stop();
		}
		LOG.info("Stopped reader, writers and acceptor workers");
		return true;
	}
	
	@Override
	public void readyForWrite(int id) {
		writerKeys.get(id).selector().wakeup();
	}

	@Override
	public void readyForWrite(List<Integer> ids) {
		for(Integer id : ids){
			readyForWrite(id);
		}
	}
	
	/** 
	 * This class is the server thread that accepts new connections and assigns them to reader threads.
	 * @author ra
	 *
	 */
	class AcceptorWorker implements Runnable {

		@Override
		public void run() {
			LOG.info("Started Acceptor worker: {}", Thread.currentThread().getName());
			int readerIdx = 0;
			int totalReaders = readers.length;
			
			while(acceptorWorking) {
				try{
					int readyChannels = acceptorSelector.select();
					while(readyChannels == 0){
						continue;
					}
					Set<SelectionKey> selectedKeys = acceptorSelector.selectedKeys();
					Iterator<SelectionKey> keyIt = selectedKeys.iterator();
					while(keyIt.hasNext()){
						SelectionKey key = keyIt.next();
						
						// accept events
						if(key.isAcceptable()){
							// Accept connection and assign in a round robin fashion to readers
							SocketChannel incomingCon = listenerSocket.accept();
							int chosenReader = (readerIdx++)%totalReaders;
							readers[chosenReader].newConnection(incomingCon);
							readers[chosenReader].wakeUp();
						}
						if(! key.isValid()){
							LOG.error("Acceptor key is disconnected !");
							System.exit(0);
						}
					}
					keyIt.remove();
					
				}
				catch(IOException e){
					e.printStackTrace();
				}
			}
		}
	}
	
	/** 
	 * This class reads from a collection of incoming connections and writes to IBuffer that are the entrance to the system.
	 * @author ra
	 *
	 */
	class Reader implements Runnable {

		private int id;
		private boolean working;
		private Queue<SocketChannel> pendingConnections;
		
		private Selector readSelector;
		
		Reader(int id, int totalNumberOfPendingConnectionsPerThread){
			this.id = id;
			this.working = true;
			this.pendingConnections = new ArrayDeque<SocketChannel>(totalNumberOfPendingConnectionsPerThread);
			try {
				this.readSelector = Selector.open();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public int id(){
			return id;
		}
		
		public void stop(){
			this.working = false; // let thread die
		}
		
		public void newConnection(SocketChannel incomingChannel){
			this.pendingConnections.add(incomingChannel);
			LOG.info("New pending connection for Reader to configure");
		}
		
		public void wakeUp(){
			this.readSelector.wakeup();
		}
		
		@Override
		public void run() {
			LOG.info("Started Reader worker: {}", Thread.currentThread().getName());
			while(working) {
				// First handle potential new connections that have been queued up
				this.handleNewConnections();
				try {
					int readyChannels = readSelector.select();
					if(readyChannels == 0){
						continue;
					}
					Set<SelectionKey> selectedKeys = readSelector.selectedKeys();
					Iterator<SelectionKey> keyIt = selectedKeys.iterator();
					while(keyIt.hasNext()) {
						SelectionKey key = keyIt.next();
						keyIt.remove();
						// read
						if(key.isReadable()){
							if(needsToConfigureConnection(key)) {
								handleConnectionIdentifier(key);
							}
							else {
								IBuffer ib = (IBuffer)key.attachment();
								SocketChannel channel = (SocketChannel) key.channel();
								int id = readerKeys.get(key);
								ib.readFrom(channel);
							}
						}
						if(! key.isValid()) {
							String conn = ((SocketChannel)key.channel()).socket().getRemoteSocketAddress().toString();
							LOG.warn("Invalid incoming data connection to: {}", conn);
						}
					}
				}
				catch(IOException ioe) {
					ioe.printStackTrace();
				}
			}
			this.closeReader();
		}
		
		private boolean needsToConfigureConnection(SelectionKey key) {
			return !(key.attachment() instanceof IBuffer);
		}
		
		private boolean handleConnectionIdentifier(SelectionKey key) {
			boolean moreConnectionsPending = true;
			ByteBuffer dst = ByteBuffer.allocate(100);
			try {
				int readBytes = ((SocketChannel)key.channel()).read(dst);
				if(readBytes != Type.INT.sizeOf(null)){
					// TODO: throw some type of error
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			dst.flip();
			int id = dst.getInt();
			LOG.info("Received conn identifier: {}", id);
			Map<Integer, IBuffer> ibMap = (Map<Integer, IBuffer>)key.attachment();
			LOG.info("Configuring IBuffer for received conn identifier: {}", id);
			IBuffer responsibleForThisChannel = ibMap.get(id);
			if(responsibleForThisChannel == null){
				// TODO: throw exception
				LOG.error("Problem here, no existent IBuffer for id: {}", id);
				System.exit(0);
			}
			// TODO: could we keep numUpstreamConnections internal to inputAdapter? probably not...
			numUpstreamConnections--;
			if(numUpstreamConnections == 0) {
				moreConnectionsPending =  false;
			}
			// Once we've identified the IBuffer responsible for this channel we attach the new object
			key.attach(null);
			key.attach(responsibleForThisChannel);
			readerKeys.put(key, id);
			return moreConnectionsPending;
		}
		
		private void handleNewConnections() {
			SocketChannel incomingCon = null;
			while((incomingCon = this.pendingConnections.poll()) != null) {
				try{
					incomingCon.configureBlocking(false);
					incomingCon.socket().setTcpNoDelay(true);
					// register new incoming connection in the thread-local selector
					SelectionKey key = incomingCon.register(readSelector, SelectionKey.OP_READ);
					// We attach the inputAdapterProvider Map, so that we can identify the channel once it starts
					key.attach(ibMap);
					LOG.info("Configured new incoming connection at: {}", incomingCon.toString());
				}
				catch(SocketException se) {
					se.printStackTrace();
				}
				catch(IOException ioe) {
					ioe.printStackTrace();
				}
			}
		}
		
		private void closeReader() {
			// FIXME: test this
			try {
				// close channel and cancel registration
				for(SelectionKey sk : readSelector.keys()) {
					sk.channel().close();
					sk.cancel();
				}
				// close pendingConnections
				for(SocketChannel sc : pendingConnections) {
					sc.close();
				}
				// close selector
				readSelector.close();
			} 
			catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	/** 
	 * This class manages a collection of OBuffer that it drains to write to channels that are output connections.
	 * @author ra
	 *
	 */
	class Writer implements Runnable {
		
		private int id;
		private boolean working;
		private Queue<OutgoingConnectionRequest> pendingConnections;
		
		// buffer id - outputbuffer
		private Map<Integer, OBuffer> outputBufferMap;
		private Map<Integer, Boolean> needsConfigureOutputConnection;
		
		private Selector writeSelector;
		
		Writer(int id) {
			this.id = id;
			this.working = true;
			this.outputBufferMap = new HashMap<>();
			this.needsConfigureOutputConnection = new HashMap<>();
			this.pendingConnections = new ArrayDeque<OutgoingConnectionRequest>();
			try {
				this.writeSelector = Selector.open();
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		public int id(){
			return id;
		}
		
		public void stop(){
			this.working = false;
		}
		
		public void newConnection(OutgoingConnectionRequest ocr) {
			LOG.trace("Writer: {} has a pending connection to: {}", id, ocr.connection);
			this.pendingConnections.add(ocr);
		}
		
		@Override
		public void run(){
			LOG.info("Started Writer worker: {}", Thread.currentThread().getName());
			while(working){
				// First handle potential new connections that have been queued up
				handleNewConnections();
				pollBuffers();
				try {
					int readyChannels = writeSelector.select();
					if(readyChannels == 0){
						continue;
					}
					Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
					Iterator<SelectionKey> keyIt = selectedKeys.iterator();
					while(keyIt.hasNext()) {
						SelectionKey key = keyIt.next();
						keyIt.remove();
						// connectable
						if(key.isConnectable()) {
							SocketChannel sc = (SocketChannel) key.channel();
							if(sc.isConnectionPending()) {
								LOG.info("Attempting to finish conn to: "+sc.toString());
								sc.finishConnect();
							}
							int interest = SelectionKey.OP_WRITE;
							key.interestOps(interest); // as soon as it connects it can write the init protocol
							LOG.info("Finished establishing output connection to: {}", sc.toString());
						}
						// writable
						if(key.isWritable()) {
							OBuffer ob = (OBuffer)key.attachment();
							SocketChannel channel = (SocketChannel)key.channel();
							
							if(needsConfigureOutputConnection.get(ob.id())) {
								handleSendIdentifier(ob.id(), channel);
								unsetWritable(key);
								needsConfigureOutputConnection.put(ob.id(), false);
								// Notify of a new configured connection
								writersConfiguredLatch.countDown();
								LOG.trace("CountDown to configure all output conns: {}", writersConfiguredLatch.getCount());
							}
							else {
								// write batch
								boolean fullyWritten = ob.drainTo(channel);
								if(fullyWritten) unsetWritable(key);
							}
						}
						if(! key.isValid()){
							String conn = ((SocketChannel)key.channel()).socket().getRemoteSocketAddress().toString();
							LOG.warn("Invalid outgoing data connection to: {}", conn);
						}
					}
				}
				catch(IOException ioe){
					ioe.printStackTrace();
				}
			}
			this.closeWriter();
		}
		
		private void pollBuffers(){
			for(OBuffer ob : outputBufferMap.values()){
				if(ob.readyToWrite()){
					SelectionKey key = writerKeys.get(ob.id());
					int interestOps = key.interestOps() | SelectionKey.OP_WRITE;
					key.interestOps(interestOps);
				}
			}
		}
		
		private void handleSendIdentifier(int oBufferId, SocketChannel channel){
			ByteBuffer bb = ByteBuffer.allocate(Integer.SIZE);
			Type.INT.write(bb, oBufferId);
			bb.flip();
			try {
				int writtenBytes = channel.write(bb);
				if(writtenBytes != Type.INT.sizeOf(null)){
					// TODO: throw some type of error
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			LOG.info("Sent connection identifier: {}", oBufferId);
		}
		
		private void unsetWritable(SelectionKey key){
			final int newOps = key.interestOps() & ~SelectionKey.OP_WRITE;
			key.interestOps(newOps);
		}
		
		private void handleNewConnections() {
			try {
				OutgoingConnectionRequest ocr = null;
				while((ocr = this.pendingConnections.poll()) != null) {
					OBuffer ob = ocr.oBuffer;
					Connection c = ocr.connection;
					SocketChannel channel = SocketChannel.open();
					InetSocketAddress address = c.getInetSocketAddress(SeepEndPointType.DATA);
			        Socket socket = channel.socket();
			        socket.setKeepAlive(true); // Unlikely in non-production scenarios we'll be up for more than 2 hours but...
			        socket.setTcpNoDelay(true); // Disabling Nagle's algorithm
			        try {
			        	channel.configureBlocking(false);
			            channel.connect(address);
			        }
			        catch (UnresolvedAddressException uae) {
			            channel.close();
			            uae.printStackTrace();
			        }
			        catch (IOException io) {
			            channel.close();
			            io.printStackTrace();
			        }
					channel.configureBlocking(false);
					int interestSet = SelectionKey.OP_CONNECT;
					SelectionKey key = channel.register(writeSelector, interestSet);
					key.attach(ob);
					outputBufferMap.put(ob.id(), ob);
					needsConfigureOutputConnection.put(ob.id(), true);
					LOG.info("Configured new output connection with OP: {} at {}", ob.id(), address.toString());
					// Associate id - key in the networkSelectorMap
					writerKeys.put(ob.id(), key);
				}
			}
			catch(IOException io){
				io.printStackTrace();
			}
		}
		
		private void closeWriter(){
			// FIXME: test this
			try {
				for(SelectionKey sk : writeSelector.keys()){
					sk.channel().close();
					sk.cancel();
				}
				writeSelector.close();
			}
			catch (IOException io){
				// TODO: proper handling
				io.printStackTrace();
			}
		}
	}
}
