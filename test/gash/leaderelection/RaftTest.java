package gash.leaderelection;

import gash.leaderelection.raft.Raft;
import gash.leaderelection.raft.RaftMessage;
import gash.leaderelection.raft.Raft.RaftMonitor;
import gash.leaderelection.raft.Raft.RaftNode;
import gash.messaging.StatNode;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RaftTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {

	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Test
	public void testStartup() throws Exception {
		Raft raft = new Raft();

		for (int n = 0; n < 5; n++) {
			RaftNode node = new RaftNode(n);
			raft.addNode(node);
			if (!node.isAlive())
				node.start();
		}
		// allow a couple of leaders to die so that we can see the leader
		// election process in action
		Thread.sleep(40000);

		//stat.report();
	}
}
