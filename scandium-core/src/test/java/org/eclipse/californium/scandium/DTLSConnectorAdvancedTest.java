/*******************************************************************************
 * Copyright (c) 2018 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Achim Kraus (Bosch Software Innovations GmbH) - initial creation
 *                                                    Based on the original test
 *                                                    in DTLSConnectorTest.
 *                                                    Update to use ConnectorHelper
 *    Achim Kraus (Bosch Software Innovations GmbH) - introduce waitForFlightReceived
 *                                                    with additional retransmission
 *                                                    compensation for faster timeouts
 *    Achim Kraus (Bosch Software Innovations GmbH) - add test for timeout of handshaker
 *                                                    with stopped retransmission
 ******************************************************************************/
package org.eclipse.californium.scandium;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.Assert.*;
import static org.eclipse.californium.scandium.ConnectorHelper.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.rule.TestNameLoggerRule;
import org.eclipse.californium.elements.rule.ThreadsRule;
import org.eclipse.californium.elements.util.ExecutorsUtil;
import org.eclipse.californium.elements.util.SerialExecutor;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.elements.util.TestThreadFactory;
import org.eclipse.californium.scandium.category.Medium;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.AdversaryClientHandshaker;
import org.eclipse.californium.scandium.dtls.ClientHandshaker;
import org.eclipse.californium.scandium.dtls.Connection;
import org.eclipse.californium.scandium.dtls.ConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.DTLSFlight;
import org.eclipse.californium.scandium.dtls.DTLSMessage;
import org.eclipse.californium.scandium.dtls.DTLSSession;
import org.eclipse.californium.scandium.dtls.HandshakeException;
import org.eclipse.californium.scandium.dtls.Handshaker;
import org.eclipse.californium.scandium.dtls.InMemoryConnectionStore;
import org.eclipse.californium.scandium.dtls.Record;
import org.eclipse.californium.scandium.dtls.RecordLayer;
import org.eclipse.californium.scandium.dtls.ResumingClientHandshaker;
import org.eclipse.californium.scandium.dtls.ResumingServerHandshaker;
import org.eclipse.californium.scandium.dtls.ServerHandshaker;
import org.eclipse.californium.scandium.dtls.SingleNodeConnectionIdGenerator;
import org.eclipse.californium.scandium.rule.DtlsNetworkRule;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies behavior of {@link DTLSConnector}.
 * <p>
 * Mainly contains integration test cases verifying the correct interaction
 * between a client and a server during handshakes including unusual message
 * order and timeouts.
 */
@RunWith(Parameterized.class)
@Category(Medium.class)
public class DTLSConnectorAdvancedTest {

	public static final Logger LOGGER = LoggerFactory.getLogger(DTLSConnectorAdvancedTest.class.getName());

	@ClassRule
	public static DtlsNetworkRule network = new DtlsNetworkRule(DtlsNetworkRule.Mode.DIRECT,
			DtlsNetworkRule.Mode.NATIVE);

	@ClassRule
	public static ThreadsRule cleanup = new ThreadsRule();

	@Rule
	public TestNameLoggerRule names = new TestNameLoggerRule();

	private static final int CLIENT_CONNECTION_STORE_CAPACITY = 5;
	private static final int MAX_TIME_TO_WAIT_SECS = 2;
	private static final int RETRANSMISSION_TIMEOUT_MS = 400;
	private static final int MAX_RETRANSMISSIONS = 2;

	static ConnectorHelper serverHelper;

	static ExecutorService executor;
	static ConnectionIdGenerator serverCidGenerator;

	DtlsConnectorConfig clientConfig;
	DTLSConnector client;
	InMemoryConnectionStore clientConnectionStore;
	List<Record> lastReceivedFlight;

	@BeforeClass
	public static void loadKeys() throws IOException, GeneralSecurityException {
		serverCidGenerator = new SingleNodeConnectionIdGenerator(6);
		DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder()
					.setRetransmissionTimeout(RETRANSMISSION_TIMEOUT_MS)
					.setMaxRetransmissions(MAX_RETRANSMISSIONS)
					.setConnectionIdGenerator(serverCidGenerator);
		serverHelper = new ConnectorHelper();
		serverHelper.startServer(builder);
		executor = ExecutorsUtil.newFixedThreadPool(2, new TestThreadFactory("DTLS-ADVANCED-"));
	}

	@AfterClass
	public static void tearDown() {
		serverHelper.destroyServer();
		ExecutorsUtil.shutdownExecutorGracefully(100, executor);
	}

	/**
	 * Actual cipher suite.
	 */
	@Parameter
	public ConnectionIdGenerator clientCidGenerator;

	/**
	 * @return List of cipher suites.
	 */
	@Parameters(name = "cid = {0}")
	public static Iterable<ConnectionIdGenerator> cidParams() {
		return Arrays.asList((ConnectionIdGenerator) null, new SingleNodeConnectionIdGenerator(0) {

			public String toString() {
				return "cid supported";
			}
		}, new SingleNodeConnectionIdGenerator(5) {

			public String toString() {
				return "cid used";
			}
		});
	}

	@Before
	public void setUp() throws Exception {
		clientConnectionStore = new InMemoryConnectionStore(CLIENT_CONNECTION_STORE_CAPACITY, 60);
		clientConnectionStore.setTag("client");
		InetSocketAddress clientEndpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
		DtlsConnectorConfig.Builder builder = newStandardClientConfigBuilder(clientEndpoint)
				.setRetransmissionTimeout(RETRANSMISSION_TIMEOUT_MS)
				.setMaxRetransmissions(MAX_RETRANSMISSIONS)
				.setMaxConnections(CLIENT_CONNECTION_STORE_CAPACITY)
				.setConnectionIdGenerator(clientCidGenerator);
		clientConfig = builder.build();

		client = serverHelper.createClient(clientConfig, clientConnectionStore);
		client.setExecutor(executor);
	}

	@After
	public void cleanUp() {
		if (client != null) {
			client.destroy();
		}
		lastReceivedFlight = null;
		serverHelper.cleanUpServer();
	}

	@Test
	public void testServerReceivingMessagesInBadOrderDuringHandshake() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		try {

			// Start connector
			rawClient.start();

			// Create handshaker with ReverseRecordLayer
			// to send message in bad order.
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(new DTLSSession(serverHelper.serverEndpoint),
					new ReverseRecordLayer(rawClient), createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);
			// Start handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer (FINISHED, CHANGE CIPHER SPEC, ...,CERTIFICATE)
			processAll(clientHandshaker, rs);

			// Wait to receive response (should be CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle it
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawClient.stop();
		}
	}

	@Test
	public void testLimitedServerReceivingMessagesInBadOrderDuringHandshake() throws Exception {
		DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder()
				.setRetransmissionTimeout(RETRANSMISSION_TIMEOUT_MS)
				.setMaxRetransmissions(MAX_RETRANSMISSIONS)
				.setMaxDeferredProcessedIncomingRecordsSize(128)
				.setConnectionIdGenerator(serverCidGenerator);
		ConnectorHelper limitedServerHelper = new ConnectorHelper();

		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		ReverseRecordLayer recordLayer = new ReverseRecordLayer(rawClient);
		try {
			// create limited server
			limitedServerHelper.startServer(builder);

			// Start connector
			rawClient.start();

			// Create handshaker with ReverseRecordLayer
			// to send message in bad order.
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(new DTLSSession(limitedServerHelper.serverEndpoint),
					recordLayer, createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);
			// Start handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer (FINISHED, CHANGE CIPHER SPEC, ...,CERTIFICATE)
			processAll(clientHandshaker, rs);

			List<Record> records = collector.waitForRecords(500,  TimeUnit.MILLISECONDS);
			assertThat(records, is(nullValue()));

			recordLayer.resendLastFlight();
			records = collector.waitForRecords(500,  TimeUnit.MILLISECONDS);
			assertThat(records, is(nullValue()));

			recordLayer.resendLastFlight();

			// Wait to receive response (should be CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle it
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawClient.stop();
			limitedServerHelper.destroyServer();
		}
	}

	@Test
	public void testClientReceivingMessagesInBadOrderDuringHandshake() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, new DTLSSession(client.getAddress(), 1),
					new ReverseRecordLayer(rawServer), createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// Wait to receive response (should be CLIENT HELLO, flight 3)
			List<Record> rs = waitForFlightReceived("flight 3", collector, 1);
			// Handle and answer
			// (SERVER_HELLO, CERTIFICATE, ... SERVER HELLO DONE, flight 4)
			processAll(serverHandshaker, rs);

			// Wait for receive response (CERTIFICATE, ... , FINISHED, flight 5)
			rs = waitForFlightReceived("flight 5", collector, 5);
			// Handle and answer (should be CCS, FINISHED, flight 6)
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawServer.stop();
		}
	}

	@Test
	public void testServerResumeReceivingMessagesInBadOrderDuringHandshake() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		ReverseRecordLayer clientRecordLayer = new ReverseRecordLayer(rawClient);
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			processAll(clientHandshaker, rs);

			// Wait to receive response from server
			// (CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle (CHANGE CIPHER SPEC, FINISHED)
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// Create resume handshaker
			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(clientSession.getSessionIdentifier(),
					serverHelper.serverEndpoint, clientSession.getSessionTicket(), 0);
			ResumingClientHandshaker resumingClientHandshaker = new ResumingClientHandshaker(resumableSession,
					clientRecordLayer, createClientConnection(), clientConfig, 1280);
			resumingClientHandshaker.addSessionListener(sessionListener);

			// Start resuming handshake (Send CLIENT HELLO, additional flight)
			resumingClientHandshaker.startHandshake();

			// Wait to receive response
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, flight 2)
			rs = waitForFlightReceived("flight 2", collector, 3);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// Handle and answer ( CHANGE CIPHER SPEC, FINISHED, flight 3)
			processAll(resumingClientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("client handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			assertTrue("server handshake failed",
					serverSessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawClient.stop();
		}
	}

	@Test
	public void testClientResumeReceivingMessagesInBadOrderDuringHandshake() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);
		ReverseRecordLayer serverRecordLayer = new ReverseRecordLayer(rawServer);

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			DTLSSession serverSession = new DTLSSession(client.getAddress(), 1);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, serverSession, serverRecordLayer,
					createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// 1. handshake
			// Wait to receive response (should be CLIENT HELLO)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer (should be SERVER_HELLO, CERTIFICATE, ...
			// SERVER HELLO DONE)
			processAll(serverHandshaker, rs);

			// Wait to receive response (CERTIFICATE, ... , FINISHED)
			rs = waitForFlightReceived("flight 3", collector, 5);
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// application data
			rs = waitForFlightReceived("app data", collector, 1);

			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(serverSession.getSessionIdentifier(), client.getAddress(),
					serverSession.getSessionTicket(), 0);
			ResumingServerHandshaker resumingServerHandshaker = new ResumingServerHandshaker(0, resumableSession,
					serverRecordLayer, createServerConnection(), serverHelper.serverConfig, 1280);
			resumingServerHandshaker.addSessionListener(sessionListener);

			// force resuming handshake
			client.forceResumeSessionFor(rawServer.getAddress());
			data = RawData.outbound("Hello World, Again!".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Wait to receive response (should be CLIENT HELLO, flight 1)
			rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer
			// (SERVER HELLO, CCS, FINISHED, flight 2).
			processAll(resumingServerHandshaker, rs);

			// Wait to receive response
			// (CCS, client FINISHED, flight 3) + (application data)
			List<Record> drops = waitForFlightReceived("flight 3 + app data", collector, 3);
			// remove application data, not retransmitted!
			drops.remove(2);

			// drop last flight 3, server resends flight 2
			serverRecordLayer.resendLastFlight();

			// Wait to receive response (CCS, client FINISHED, flight 3)
			// ("application data" doesn't belong to flight)
			rs = waitForFlightReceived("flight 3", collector, 2);
			processAll(resumingServerHandshaker, rs);

			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

		} finally {
			rawServer.stop();
		}
	}

	/**
	 * Test retransmission of last flight.
	 * 
	 * RFC6347, section 4.2.4, fig. 2
	 * 
	 * "testFinishedMessageRetransmission" drops the first transmission of
	 * flight 5 to test, if flight 5 is retransmitted. But flight 5 is just
	 * usual retransmission, the special case is flight 6. Therefore this test
	 * drops the 1. transmission of flight 6 to check, if retransmission of
	 * flight 5 triggers the retransmission of flight 6.
	 * @throws Exception if the test fails
	 */
	@Test
	public void testServerFinishedMessageRetransmission() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(new DTLSSession(serverHelper.serverEndpoint),
					clientRecordLayer, createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start handshake (Send CLIENT HELLO, flight 1)
			clientHandshaker.startHandshake();

			// Wait to receive response
			// (HELLO VERIFY REQUEST, flight 2)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie, flight 3)
			processAll(clientHandshaker, rs);

			// Wait for response
			// (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE, flight 4)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED, flight 5)
			processAll(clientHandshaker, rs);

			// Wait to receive response from server
			// (CHANGE CIPHER SPEC, FINISHED, flight 6)
			List<Record> drops = waitForFlightReceived("flight 6", collector, 2);
			// Ignore the receive response, client resends flight 5
			clientRecordLayer.resendLastFlight();

			// Wait for retransmission
			// (CHANGE CIPHER SPEC, FINISHED, flight 6)
			rs = waitForFlightReceived("flight 6", collector, 2);
			assertFlightRecordsRetransmitted(drops, rs);
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Test retransmission of last flight of resuming handshake.
	 * 
	 * RFC6347, section 4.2.4, fig. 2
	 * 
	 * "testResumeFinishedMessageRetransmission" drops the first transmission of
	 * flight 2 to test, if flight 2 is retransmitted. But flight 2 is just
	 * usual retransmission, the special case is flight 3. Therefore this test
	 * drops the 1. transmission of flight 3 to check, if retransmission of
	 * flight 2 triggers the retransmission of flight 3.
	 * @throws Exception if the test fails
	 */
	@Test
	public void testResumeClientFinishedMessageRetransmission() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);
		BasicRecordLayer serverRecordLayer = new BasicRecordLayer(rawServer);

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			DTLSSession serverSession = new DTLSSession(client.getAddress(), 1);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, serverSession, serverRecordLayer,
					createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// 1. handshake
			// Wait to receive response (should be CLIENT HELLO)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer (should be CERTIFICATE, ... SERVER HELLO DONE)
			processAll(serverHandshaker, rs);

			// Wait to receive response (CERTIFICATE, ... , FINISHED)
			rs = waitForFlightReceived("flight 3", collector, 5);
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// application data
			rs = waitForFlightReceived("app data", collector, 1);

			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(serverSession.getSessionIdentifier(), client.getAddress(),
					serverSession.getSessionTicket(), 0);
			ResumingServerHandshaker resumingServerHandshaker = new ResumingServerHandshaker(0, resumableSession,
					serverRecordLayer, createServerConnection(), serverHelper.serverConfig, 1280);
			resumingServerHandshaker.addSessionListener(sessionListener);

			// force resuming handshake
			client.forceResumeSessionFor(rawServer.getAddress());
			data = RawData.outbound("Hello Again".getBytes(), new AddressEndpointContext(rawServer.getAddress()), null,
					false);
			client.send(data);

			// Wait to receive response (should be CLIENT HELLO, flight 1)
			rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer
			// (SERVER HELLO, CCS, FINISHED, VERIFY REQUEST, flight 2).
			processAll(resumingServerHandshaker, rs);

			// Wait to receive response
			// (CCS, client FINISHED, flight 3) + (application data)
			List<Record> drops = waitForFlightReceived("flight 3", collector, 3);
			// remove application data, not retransmitted!
			drops.remove(2);

			// drop last flight 3, server resends flight 2
			serverRecordLayer.resendLastFlight();

			// Wait to receive response (CCS, client FINISHED, flight 3)
			// ("application data" doesn't belong to flight)
			rs = waitForFlightReceived("flight 3", collector, 2);
			assertFlightRecordsRetransmitted(drops, rs);
			processAll(resumingServerHandshaker, rs);
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

		} finally {
			rawServer.stop();
		}
	}

	/**
	 * Test retransmission of flight before last flight.
	 * 
	 * RFC6347, section 4.2.4, fig. 2
	 * 
	 * Drops the first transmission of flight 5 to test, if flight 5 is
	 * retransmitted. Usual retransmission, the special case is flight 6, see
	 * "testServerFinishedMessageRetransmission".
	 * @throws Exception if the test fails
	 */
	@Test
	public void testFinishedMessageRetransmission() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, new DTLSSession(client.getAddress(), 1),
					new BasicRecordLayer(rawServer), createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// Wait to receive response (should be CLIENT HELLO, flight 3)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer
			// (CERTIFICATE, ... SERVER HELLO DONE, flight 4)
			processAll(serverHandshaker, rs);

			// Ignore transmission (CERTIFICATE, ... , FINISHED, flight 5)
			List<Record> drops = waitForFlightReceived("flight 3", collector, 5);

			// Wait for retransmission (CERTIFICATE, ... , FINISHED, flight 5)
			rs = waitForFlightReceived("flight 3", collector, 5);
			assertFlightRecordsRetransmitted(drops, rs);
			// Handle and answer (should be CCS, FINISHED, flight 6)
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawServer.stop();
		}
	}

	/**
	 * Test retransmission of flight before last flight of resuming handshake.
	 * 
	 * RFC6347, section 4.2.4, fig. 2
	 * 
	 * Drops the first transmission of flight 2 to test, if flight 2 is
	 * retransmitted. Flight 2 is just usual retransmission, the special case is
	 * flight 3, see "testResumeClientFinishedMessageRetransmission". Note:
	 * scandium uses a additional HELLO VERIFY REQUEST. May be optimized in the
	 * future.
	 * @throws Exception if the test fails
	 */
	@Test
	public void testResumeFinishedMessageRetransmission() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			processAll(clientHandshaker, rs);

			// Wait to receive response from server
			// (CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle (CHANGE CIPHER SPEC, FINISHED)
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// Create resume handshaker
			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(clientSession.getSessionIdentifier(),
					serverHelper.serverEndpoint, clientSession.getSessionTicket(), 0);
			ResumingClientHandshaker resumingClientHandshaker = new ResumingClientHandshaker(resumableSession,
					clientRecordLayer, createClientConnection(), clientConfig, 1280);
			resumingClientHandshaker.addSessionListener(sessionListener);

			// Start resuming handshake (Send CLIENT HELLO, additional flight)
			resumingClientHandshaker.startHandshake();

			// Wait to receive response
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, fight 2)
			List<Record> drops = waitForFlightReceived("flight 2", collector, 3);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// drop it, force retransmission
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, fight 2)
			rs = waitForFlightReceived("flight 2", collector, 3);
			assertFlightRecordsRetransmitted(drops, rs);
			// Handle and answer ( CHANGE CIPHER SPEC, FINISHED, flight 3)
			processAll(resumingClientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("client handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			assertTrue("server handshake failed",
					serverSessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Test the server resuming handshake fails, if clients FINISH is dropped.
	 * @throws Exception if the test fails
	 */
	@Test
	public void testServerResumeTimeout() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		int remain = serverHelper.serverConnectionStore.remainingCapacity();
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			processAll(clientHandshaker, rs);

			// Wait to receive response from server
			// (CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle (CHANGE CIPHER SPEC, FINISHED)
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// Create resume handshaker
			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(clientSession.getSessionIdentifier(),
					serverHelper.serverEndpoint, clientSession.getSessionTicket(), 0);
			ResumingClientHandshaker resumingClientHandshaker = new ResumingClientHandshaker(resumableSession,
					clientRecordLayer, createClientConnection(), clientConfig, 1280);
			resumingClientHandshaker.addSessionListener(sessionListener);

			// Start resuming handshake (Send CLIENT HELLO, additional flight)
			resumingClientHandshaker.startHandshake();

			// Wait to receive response
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, fight 2)
			rs = waitForFlightReceived("flight 2", collector, 3);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// Handle and answer
			// (CHANGE CIPHER SPEC, FINISHED (drop), flight 3)
			clientRecordLayer.setDrop(-1);
			processAll(resumingClientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("client handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 1));
			Throwable error = serverSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("server handshake not failed", error);
			assertThat(serverHelper.serverConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Test the server handshake fails, if clients FINISH is dropped.
	 * @throws Exception if the test fails
	 */
	@Test
	public void testServerTimeout() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		int remain = serverHelper.serverConnectionStore.remainingCapacity();
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			clientRecordLayer.setDrop(-1);
			processAll(clientHandshaker, rs);

			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = serverSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("server handshake not failed", error);
			assertThat(serverHelper.serverConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawClient.stop();
		}
	}

	@Test
	public void testClientResumeTimeout() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);
		BasicRecordLayer serverRecordLayer = new BasicRecordLayer(rawServer);
		int remain = clientConnectionStore.remainingCapacity();

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			DTLSSession serverSession = new DTLSSession(client.getAddress(), 1);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, serverSession, serverRecordLayer,
					createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// 1. handshake
			// Wait to receive response (should be CLIENT HELLO)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer (should be CERTIFICATE, ... SERVER HELLO DONE)
			processAll(serverHandshaker, rs);

			// Wait to receive response (CERTIFICATE, ... , FINISHED)
			rs = waitForFlightReceived("flight 3", collector, 5);
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// application data
			rs = waitForFlightReceived("app data", collector, 1);

			sessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(serverSession.getSessionIdentifier(), client.getAddress(),
					serverSession.getSessionTicket(), 0);
			ResumingServerHandshaker resumingServerHandshaker = new ResumingServerHandshaker(0, resumableSession,
					serverRecordLayer, createServerConnection(), serverHelper.serverConfig, 1280);
			resumingServerHandshaker.addSessionListener(sessionListener);

			// force resuming handshake
			client.forceResumeSessionFor(rawServer.getAddress());
			data = RawData.outbound("Hello Again".getBytes(), new AddressEndpointContext(rawServer.getAddress()), null,
					false);
			client.send(data);

			// Wait to receive response (should be CLIENT HELLO, flight 1)
			rs = waitForFlightReceived("flight 1", collector, 1);

			LatchSessionListener clientSessionListener = getSessionListenerForEndpoint("client", rawServer);

			// Handle and answer
			// (SERVER HELLO, CCS, FINISHED drop, flight 2).
			serverRecordLayer.setDrop(-1);
			processAll(resumingServerHandshaker, rs);

			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = clientSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("client handshake not failed", error);
			assertThat(clientConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawServer.stop();
		}
	}

	@Test
	public void testClientTimeout() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);
		int remain = clientConnectionStore.remainingCapacity();

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			BasicRecordLayer serverRecordLayer = new BasicRecordLayer(rawServer);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, new DTLSSession(client.getAddress(), 1),
					serverRecordLayer, createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// Wait to receive response (should be CLIENT HELLO, flight 3)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer
			// (CERTIFICATE, ... SERVER HELLO DONE, flight 4)
			processAll(serverHandshaker, rs);

			LatchSessionListener clientSessionListener = getSessionListenerForEndpoint("client", rawServer);

			// Wait for transmission (CERTIFICATE, ... , FINISHED, flight 5)
			rs = waitForFlightReceived("flight 3", collector, 5);
			// Handle and answer (should be CCS, FINISHED (drop), flight 6)
			serverRecordLayer.setDrop(-1);
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = clientSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("client handshake not failed", error);
			assertThat(clientConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawServer.stop();
		}
	}

	@Test
	public void testResumeWithVerify() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);

		RecordCollectorDataHandler alt1Collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawAlt1Client = new UdpConnector(0, alt1Collector);

		RecordCollectorDataHandler alt2Collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawAlt2Client = new UdpConnector(0, alt2Collector);

		DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
		try {
			// Start connector
			rawClient.start();

			// Create handshaker
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, new BasicRecordLayer(rawClient),
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);
			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			processAll(clientHandshaker, rs);

			// Wait to receive response from server
			// (CHANGE CIPHER SPEC, FINISHED)
			rs = waitForFlightReceived("flight 6", collector, 2);
			// Handle (CHANGE CIPHER SPEC, FINISHED)
			processAll(clientHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

			// Create 1. resume handshaker
			rawAlt1Client.start();
			LatchSessionListener alt1SessionListener = new LatchSessionListener();
			DTLSSession resumableSession = new DTLSSession(clientSession.getSessionIdentifier(),
					serverHelper.serverEndpoint, clientSession.getSessionTicket(), 0);
			ResumingClientHandshaker resumingClientHandshaker = new ResumingClientHandshaker(resumableSession,
					new BasicRecordLayer(rawAlt1Client), createClientConnection(), clientConfig, 1280);
			resumingClientHandshaker.addSessionListener(alt1SessionListener);

			// Start resuming handshake (Send CLIENT HELLO, additional flight)
			resumingClientHandshaker.startHandshake();

			// Wait to receive response
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, fight 4)
			rs = waitForFlightReceived("flight 4", alt1Collector, 3);

			LatchSessionListener serverAlt1SessionListener = getSessionListenerForEndpoint("server", rawAlt1Client);

			// Create 2. resume handshaker
			rawAlt2Client.start();
			LatchSessionListener alt2SessionListener = new LatchSessionListener();
			resumableSession = new DTLSSession(clientSession.getSessionIdentifier(), serverHelper.serverEndpoint,
					clientSession.getSessionTicket(), 0);
			resumingClientHandshaker = new ResumingClientHandshaker(resumableSession,
					new BasicRecordLayer(rawAlt2Client), createClientConnection(), clientConfig, 1280);
			resumingClientHandshaker.addSessionListener(alt2SessionListener);

			// Start resuming handshake (Send CLIENT HELLO, additional flight)
			resumingClientHandshaker.startHandshake();

			// Wait to receive response
			// (HELLO_VERIFY_REQUEST, fight 2)
			rs = waitForFlightReceived("flight 2", alt2Collector, 1);

			// Send CLIENT HELLO with cookie, flight 3
			processAll(resumingClientHandshaker, rs);

			// Wait to receive response
			// (SERVER_HELLO, CHANGE CIPHER SPEC, FINISHED, fight 4)
			rs = waitForFlightReceived("flight 4", alt2Collector, 3);

			// create server session listener to ensure,
			// that server finish also the handshake
			serverHelper.serverConnectionStore.dump();

			LatchSessionListener serverAlt2SessionListener = getSessionListenerForEndpoint("server", rawAlt2Client);

			processAll(resumingClientHandshaker, rs);

			assertTrue("client 2. resumed handshake failed",
					alt2SessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
			assertTrue("server handshake failed",
					serverAlt2SessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = serverAlt1SessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("server handshake not failed", error);
		} finally {
			rawClient.stop();
			rawAlt1Client.stop();
			rawAlt2Client.stop();
		}
	}

	@Test
	public void testServerNoCCS() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		int remain = serverHelper.serverConnectionStore.remainingCapacity();
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ClientHandshaker clientHandshaker = new ClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// Handle and answer
			// (CERTIFICATE, CHANGE CIPHER SPEC, ..., FINISHED)
			clientRecordLayer.setDrop(-2);
			processAll(clientHandshaker, rs);

			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = serverSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("server handshake not failed", error);
			assertThat(serverHelper.serverConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawClient.stop();
		}
	}

	@Test
	public void testClientNoCCS() throws Exception {
		// Configure UDP connector we will use as Server
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(serverCidGenerator);
		UdpConnector rawServer = new UdpConnector(0, collector);
		int remain = clientConnectionStore.remainingCapacity();

		try {
			// Start connector (Server)
			rawServer.start();

			// Start the client
			client.start();
			RawData data = RawData.outbound("Hello World".getBytes(),
					new AddressEndpointContext(rawServer.getAddress()), null, false);
			client.send(data);

			// Create server handshaker
			BasicRecordLayer serverRecordLayer = new BasicRecordLayer(rawServer);
			LatchSessionListener sessionListener = new LatchSessionListener();
			ServerHandshaker serverHandshaker = new ServerHandshaker(0, new DTLSSession(client.getAddress(), 1),
					serverRecordLayer, createServerConnection(), serverHelper.serverConfig, 1280);
			serverHandshaker.addSessionListener(sessionListener);

			// Wait to receive response (should be CLIENT HELLO, flight 3)
			List<Record> rs = waitForFlightReceived("flight 1", collector, 1);
			// Handle and answer
			// (CERTIFICATE, ... SERVER HELLO DONE, flight 4)
			processAll(serverHandshaker, rs);

			LatchSessionListener clientSessionListener = getSessionListenerForEndpoint("client", rawServer);

			// Wait for transmission (CERTIFICATE, ... , FINISHED, flight 5)
			rs = waitForFlightReceived("flight 3", collector, 5);
			// Handle and answer (should be CCS (drop), FINISHED, flight 6)
			serverRecordLayer.setDrop(-2);
			processAll(serverHandshaker, rs);

			// Ensure handshake is successfully done
			assertTrue("handshake failed",
					sessionListener.waitForSessionEstablished(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = clientSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			assertNotNull("client handshake not failed", error);
			assertThat(clientConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawServer.stop();
		}
	}

	@Test
	public void testServerAdverseryClient() throws Exception {
		// Configure and create UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler(clientCidGenerator);
		UdpConnector rawClient = new UdpConnector(0, collector);
		BasicRecordLayer clientRecordLayer = new BasicRecordLayer(rawClient);
		int remain = serverHelper.serverConnectionStore.remainingCapacity();
		try {

			// Start connector
			rawClient.start();

			// Create handshaker
			DTLSSession clientSession = new DTLSSession(serverHelper.serverEndpoint);
			LatchSessionListener sessionListener = new LatchSessionListener();
			AdversaryClientHandshaker clientHandshaker = new AdversaryClientHandshaker(clientSession, clientRecordLayer,
					createClientConnection(), clientConfig, 1280);
			clientHandshaker.addSessionListener(sessionListener);

			// Start 1. handshake (Send CLIENT HELLO)
			clientHandshaker.startHandshake();

			// Wait to receive response (should be HELLO VERIFY REQUEST)
			List<Record> rs = waitForFlightReceived("flight 2", collector, 1);
			// Handle and answer (CLIENT HELLO with cookie)
			processAll(clientHandshaker, rs);

			// Wait for response (SERVER_HELLO, CERTIFICATE, ... , SERVER_DONE)
			rs = waitForFlightReceived("flight 4", collector, 5);

			// create server session listener to ensure,
			// that server finish also the handshake
			LatchSessionListener serverSessionListener = getSessionListenerForEndpoint("server", rawClient);

			// Handle and answer
			// (CERTIFICATE, (NO CHANGE CIPHER SPEC), ..., FINISHED)
			processAll(clientHandshaker, rs);

			// Ensure handshake failed
			int timeout = RETRANSMISSION_TIMEOUT_MS * (2 << (MAX_RETRANSMISSIONS + 2));
			Throwable error = serverSessionListener.waitForSessionFailed(timeout, TimeUnit.MILLISECONDS);
			if (error == null) {

				rs = waitForFlightReceived("flight 5", collector, 2);
				processAll(clientHandshaker, rs);

				clientHandshaker.sendApplicationData("Hello".getBytes());

				rs = waitForFlightReceived("flight 6 (app data)", collector, 1);
				for (Record data : rs) {
					data.applySession(clientHandshaker.getSession());
					System.out.println(data);
					DTLSMessage message = data.getFragment();
					byte[] array = message.toByteArray();
					System.out.println(StringUtil.byteArray2Hex(array) + " / " + new String(array));
				}

				RawData message = serverHelper.serverRawDataProcessor.getLatestInboundMessage();
				System.out.println(StringUtil.byteArray2Hex(message.getBytes()) + " / " + new String(message.getBytes()));
			}
			assertNotNull("server handshake not failed", error);
			assertThat(serverHelper.serverConnectionStore.remainingCapacity(), is(remain));
		} finally {
			rawClient.stop();
		}
	}

	private void processAll(Handshaker handshaker, List<Record> records)
			throws GeneralSecurityException, HandshakeException {
		DTLSSession session = handshaker.getSession();
		for (Record record : records) {
			record.applySession(session);
			handshaker.processMessage(record);
		}
	}

	private List<Record> waitForFlightReceived(String description, RecordCollectorDataHandler collector, int records)
			throws InterruptedException {
		List<Record> rs = collector.waitForFlight(records, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
		assertNotNull(description + " timeout", rs);
		if (rs.size() != records && lastReceivedFlight != null && lastReceivedFlight.size() <= rs.size()) {
			// check for retransmission
			int index = 0;
			int lastSize = lastReceivedFlight.size();
			for (; index < lastSize; ++index) {
				Record record1 = lastReceivedFlight.get(index);
				Record record2 = rs.get(index);
				if (record2.getEpoch() != record1.getEpoch()) {
					break;
				}
				if (record2.getType() != record1.getType()) {
					break;
				}
				if (record2.getFragmentLength() != record1.getFragmentLength()) {
					break;
				}
				if (record2.getSequenceNumber() > record1.getSequenceNumber()) {
					break;
				}
			}
			if (index == lastSize) {
				// retransmission
				if (lastSize == rs.size()) {
					// wait for next flight
					rs = collector.waitForFlight(records, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
					assertNotNull(description + " timeout", rs);
				} else {
					// drop records of retransmitted flight
					List<Record> newFlight = new ArrayList<Record>();
					for (; index < rs.size(); ++index) {
						newFlight.add(rs.get(index));
					}
					rs = newFlight;
				}
			}
		}
		if (rs.size() != records) {
			for (Record record : rs) {
				if (record.getEpoch() == 0) {
					try {
						record.applySession(null);
						record.getFragment();
					} catch (GeneralSecurityException e) {
						LOGGER.error("",  e);
					} catch (HandshakeException e) {
						LOGGER.error("",  e);
					}
				}
				LOGGER.info("   {}", record);
			}
			if (rs.size() < records) {
				assertThat(description + " missing records", rs.size(), is(records));
			} else {
				assertThat(description + " extra records", rs.size(), is(records));
			}
		}
		lastReceivedFlight = rs;
		return rs;
	}

	private void assertFlightRecordsRetransmitted(final List<Record> flight1, final List<Record> flight2) {

		assertThat("retransmitted flight has different number of records", flight2.size(), is(flight1.size()));
		for (int index = 0; index < flight1.size(); ++index) {
			Record record1 = flight1.get(index);
			Record record2 = flight2.get(index);
			assertThat("retransmitted flight record has different epoch", record2.getEpoch(), is(record1.getEpoch()));
			assertThat("retransmitted flight record has different type", record2.getType(), is(record1.getType()));
			assertThat("retransmitted flight record has different lenght", record2.getFragmentLength(),
					is(record1.getFragmentLength()));
			assertThat("retransmitted flight record has no newer seqn", record2.getSequenceNumber(),
					is(greaterThan(record1.getSequenceNumber())));
		}
	}

	private LatchSessionListener getSessionListenerForEndpoint(String side, UdpConnector clientEndpoint) {
		InetSocketAddress address = clientEndpoint.getAddress();
		LatchSessionListener serverSessionListener = serverHelper.sessionListenerMap.get(address);
		assertNotNull("missing " + side + "-side session listener for " + address);
		return serverSessionListener;
	}

	private Connection createServerConnection() {
		Connection connection = new Connection(client.getAddress(), new SerialExecutor(executor));
		connection.setConnectionId(serverCidGenerator.createConnectionId());
		return connection;
	}

	private Connection createClientConnection() {
		Connection connection = new Connection(serverHelper.serverEndpoint, new SerialExecutor(executor));
		if (clientCidGenerator != null) {
			connection.setConnectionId(clientCidGenerator.createConnectionId());
		}
		return connection;
	}

	public static class BasicRecordLayer implements RecordLayer {

		private final AtomicInteger drop = new AtomicInteger(0);
		protected final UdpConnector connector;
		protected volatile DTLSFlight lastFlight;
		protected volatile Connection lastConnection;

		public BasicRecordLayer(UdpConnector connector) {
			this.connector = connector;
		}

		public void setDrop(int drop) {
			this.drop.set(drop);
		}

		@Override
		public void sendFlight(DTLSFlight flight, Connection connection) throws IOException {
			lastFlight = flight;
			lastConnection = connection;
			for (Record record : getMessagesOfFlight(flight)) {
				connector.sendRecord(record.getPeerAddress(), record.toByteArray());
			}
		}

		public void resendLastFlight() throws GeneralSecurityException, IOException {
			DTLSFlight flight = lastFlight;
			Connection connection = lastConnection;
			if (null == flight) {
				throw new IllegalStateException("no last flight!");
			}
			flight.incrementTries();
			flight.setNewSequenceNumbers();
			sendFlight(flight, connection);
		}

		public List<Record> getMessagesOfFlight(DTLSFlight flight) {
			List<Record> messages = flight.getMessages();
			int drop = this.drop.get();
			if (drop != 0) {
				int index = drop - 1;
				messages = new ArrayList<Record>(messages);
				if (drop < 0) {
					index = messages.size() + drop;
				}
				if (0 <= index && index < messages.size()) {
					messages.remove(index);
				}
			}
			return messages;
		}

		@Override
		public void processRecord(Record record, Connection connection) {
			// records are fetched with getMessagesOfFlight and 
			// handed over to the handshaker within the test
		}
	};

	public static class ReverseRecordLayer extends BasicRecordLayer {

		public ReverseRecordLayer(UdpConnector connector) {
			super(connector);
		}

		@Override
		public List<Record> getMessagesOfFlight(DTLSFlight flight) {
			List<Record> messages = super.getMessagesOfFlight(flight);
			if (messages.size() > 1) {
				List<Record> reverse = new ArrayList<Record>();
				for (int index = messages.size() - 1; index >= 0; index--) {
					reverse.add(messages.get(index));
				}
				messages = reverse;
			}
			return messages;
		}
	};
}
