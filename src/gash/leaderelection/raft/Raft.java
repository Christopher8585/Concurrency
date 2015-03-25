package gash.leaderelection.raft;

import gash.messaging.Message;
import gash.messaging.Message.Delivery;
import gash.messaging.Node;
import gash.messaging.transports.Bus;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Raft consensus algorithm is similar to PAXOS and Flood Max though it
 * claims to be easier to understand. The paper "In Search of an Understandable
 * Consensus Algorithm" explains the concept. See
 * https://ramcloud.stanford.edu/raft.pdf
 * 
 * Note the Raft algo is both a leader election and consensus algo. It ties the
 * election process to the state of its distributed log (state) as the state is
 * part of the decision process of which node should be the leader.
 * 
 * 
 * @author gash
 *
 */
public class Raft {
	static AtomicInteger msgID = new AtomicInteger(0);
	static int size=0;
	static int leaderElected=-1;
	
	private Bus<? extends RaftMessage> transport;

	public Raft() {
		transport = new Bus<RaftMessage>(0);
	}

	public void addNode(RaftNode node) {
		if (node == null)
			return;

		node.setTransport(transport);

		@SuppressWarnings({ "rawtypes", "unchecked" })
		Node<Message> n = (Node) (node);
		transport.addNode(n);
		size++;
		if (!node.isAlive())
			node.start();
	}
	
	public void sendClientRequest(Byte[] b) {
		if(leaderElected!=-1){
			
		}
	}

	/** processes heartbeats */
	public interface HeartMonitorListener {
		public void doMonitor();
	}

	public static abstract class LogEntryBase {
		private int term;
		public abstract int getTerm();
		public abstract void setTerm(int term);
	}

	private static class LogEntry extends LogEntryBase {
		private String data;
		private int index;

		public int getTerm() {
			return super.term;
		}

		public void setTerm(int term) {
			super.term = term;
		}
	}

	/** triggers monitoring of the heartbeat */
	public static class RaftMonitor extends TimerTask {
		private RaftNode<RaftMessage> node;
		
		
		public RaftMonitor(RaftNode<RaftMessage> node) {
			if (node == null)
				throw new RuntimeException("Missing node");

			this.node = node;
		}

		@Override
		public void run() {
			node.checkBeats();
		}
	}

	/** our network node */
	public static class RaftNode<M extends RaftMessage> extends Node<M> {
		public enum RState {
			Follower, Candidate, Leader
		}
		
		private RaftMonitor monitor;
		private RState state = RState.Follower;
		private int leaderID = -1;
		private long lastKnownBeat;
		private int beatSensitivity; //= 3; // 2 misses
		private int beatDelta = 3000; // 3 seconds
		private int beatCounter = 0;
		private Timer timer;
		private List<LogEntry> logEntries=new ArrayList<LogEntry>();
		
		//For majority votes
		private int votes;
		
		
		private Bus<? extends RaftMessage> transport;

		public RaftNode(int id) {
			super(id);
			beatSensitivity=id+1;
		}
		
		public void start() {
			if (this.timer != null)
				return;

			monitor=new RaftMonitor((RaftNode<RaftMessage>) this);
			// allow the threads to start before checking HB. Also, the
			// beatDelta should be slightly greater than the frequency in which
			// messages are emitted to reduce false positives.
			int freq = (int) (beatDelta * .75);
			if (freq == 0)
				freq = 1;

			timer = new Timer();
			timer.scheduleAtFixedRate(monitor, beatDelta * 2, freq);

			super.start();
		}

		protected void checkBeats() {
			System.out.println("--> node " + getNodeId() + " heartbeat" + this.leaderID);
			// leader must sent HB to other nodes otherwise an election will
			// start
			if (this.leaderID == getNodeId()) {
				System.out.println("---------heartbeat---------");
				beatCounter = 0;
				sendAppendNotice();//HeartBeat and Append Request
				return;
			}

			long now = System.currentTimeMillis();
			if (now - lastKnownBeat > beatDelta && state != RaftNode.RState.Candidate) {
				beatCounter++;
				if (beatCounter > beatSensitivity) {
					System.out.println("---------election--------");
					System.out.println("--> node " + getNodeId() + " starting an election");
					// leader is dead! Long live me!
					state = RaftNode.RState.Candidate;
					sendRequestVoteNotice();
				}
			}
		}
		
		// For broadcast make dest=-1 
		// else add the dest ID of the node
		private void sendLeaderNotice(int dest) {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			msg.setDestination(dest);
			msg.setAction(RaftMessage.Action.Leader);
			send(msg);
		}
		// Also used as HeartBeat
		private void sendAppendNotice() {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			msg.setDestination(-1);
			LogEntry le=null;
			if(logEntries.size()>0)
				le=logEntries.get(logEntries.size()-1);
			msg.setTerm(le!=null?le.getTerm():0);
			msg.setLogIndex(le!=null?le.index:0);
			msg.setAction(RaftMessage.Action.Append);
			send(msg);
		}
		
		/** TODO args should set voting preference */
		private void sendRequestVoteNotice() {
			RaftMessage msg = new RaftMessage(Raft.msgID.incrementAndGet());
			msg.setOriginator(getNodeId());
			msg.setDeliverAs(Delivery.Broadcast);
			LogEntry le=null;
			if(logEntries.size()>0)
				le=logEntries.get(logEntries.size()-1);
			msg.setTerm(le!=null?le.getTerm():0);
			msg.setLogIndex(le!=null?le.index:0);
			msg.setDestination(-1);
			msg.setAction(RaftMessage.Action.RequestVote);
			send(msg);
		}

		private void send(RaftMessage msg) {
			// enqueue the message - if we directly call the nodes method, we
			// end up with a deep call stack and not a message-based model.
			 transport.sendMessage(msg);
		}

		/** this is called by the Node's run() - reads from its inbox */
		@Override
		public void process(RaftMessage msg) {
			// TODO process
			processMessage(msg);
		}

		private void processMessage(RaftMessage msg) {
			// TODO Auto-generated method stub
			if(msg.getAction()==RaftMessage.Action.RequestVote){
				LogEntry le=null;
				if(logEntries.size()>0)	
					le=logEntries.get(logEntries.size()-1);
				
				if(msg.getTerm()>(le!=null?le.getTerm():-1) && msg.getLogIndex()>=(le!=null?le.index:-1)){
					//accept msg sender as leader
					//leaderID=msg.getOriginator();
					//beatCounter=0;
					sendLeaderNotice(msg.getOriginator());  //Ack
				}
				else{
					//send new updated leader data
					if(leaderID!=-1){
						
					}
				}
			}
			else if(msg.getAction()==RaftMessage.Action.Leader && getNodeId()==msg.getDestination()
					&& state== RaftNode.RState.Candidate){
				if(leaderID==msg.getDestination())
					return;
				votes++;
				//Majority votes
				System.out.println("--> node " + getNodeId() + " acknowledges vote from " + msg.getOriginator() +" votes:"+votes);
				if(votes>Raft.size/2){
					System.out.println("--> node " + getNodeId() + " BECOMES THE LEADER ");
					leaderID=msg.getDestination();
					lastKnownBeat = System.currentTimeMillis();
					//state = FloodNode.FMState.Member;
					beatCounter = 0;
					votes=0;
					Raft.leaderElected=leaderID;
					sendAppendNotice();
				}
			}
			else if (msg.getAction()==RaftMessage.Action.Append){
				leaderID=msg.getOriginator();
				lastKnownBeat = System.currentTimeMillis();
				//state = FloodNode.FMState.Member;
				beatCounter = 0;
			}
		}
		
		

		public void setTransport(Bus<? extends RaftMessage> t) {
			this.transport = t;
		}
	}
}
