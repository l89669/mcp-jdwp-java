package one.edee.mcp.jdwp.discovery;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal in-process JDWP-shaped server used by handshake-probe tests. Each instance binds an
 * ephemeral port on the loopback interface and accepts exactly one connection; the per-instance
 * behaviour is selected by the static factory used to create it.
 *
 * <p>Closes its server socket and shuts down its worker pool on {@link #close()} so a test that
 * leaks a server still releases the port within ~2 seconds.
 */
final class FakeJdwpServer implements AutoCloseable {

	private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);

	private final ServerSocket socket;
	private final ExecutorService pool;

	private FakeJdwpServer(Behaviour behaviour) throws Exception {
		this.socket = new ServerSocket(0);
		this.pool = Executors.newSingleThreadExecutor(r -> {
			final Thread t = new Thread(r, "fake-jdwp");
			t.setDaemon(true);
			return t;
		});
		pool.submit(() -> {
			try (Socket client = socket.accept();
				 InputStream in = client.getInputStream();
				 OutputStream out = client.getOutputStream()) {
				behaviour.run(in, out);
			} catch (Exception ignored) {
				// connection closed by client / interrupted on shutdown — expected
			}
			return null;
		});
	}

	/** Echoes back the 14-byte JDWP handshake — the cooperating real-JVM behaviour. */
	static FakeJdwpServer cooperating() throws Exception {
		return new FakeJdwpServer((in, out) -> {
			readHandshake(in);
			out.write(HANDSHAKE);
			out.flush();
			// Linger so the client side completes its read before we tear the socket down.
			Thread.sleep(2000);
		});
	}

	/**
	 * Reads the handshake bytes but never replies — simulates a non-JDWP service that happens
	 * to be bound on the port (smtp, http server with strict timeouts, etc.).
	 */
	static FakeJdwpServer silent() throws Exception {
		return new FakeJdwpServer((in, out) -> {
			readHandshake(in);
			Thread.sleep(2000);
		});
	}

	/**
	 * Echoes 14 garbage bytes instead of the JDWP magic — simulates the rare case where
	 * something else accepts the connection and writes back nonsense.
	 */
	static FakeJdwpServer wrongBytes() throws Exception {
		return new FakeJdwpServer((in, out) -> {
			readHandshake(in);
			final byte[] garbage = new byte[HANDSHAKE.length];
			for (int i = 0; i < garbage.length; i++) {
				garbage[i] = (byte) ('X');
			}
			out.write(garbage);
			out.flush();
			Thread.sleep(2000);
		});
	}

	/**
	 * Accepts the connection then closes it immediately after reading the handshake — exercises
	 * the {@code read() == -1} branch in the probe.
	 */
	static FakeJdwpServer closesMidRead() throws Exception {
		return new FakeJdwpServer((in, out) -> {
			readHandshake(in);
			// Close happens via the try-with-resources on exit.
		});
	}

	/**
	 * Accepts the connection, sleeps {@code delayMs} before reading or replying. Used to
	 * pin down the connect-vs-read budget allocation in {@code probeHandshake}.
	 */
	static FakeJdwpServer delayedEcho(int delayMs) throws Exception {
		return new FakeJdwpServer((in, out) -> {
			Thread.sleep(delayMs);
			readHandshake(in);
			out.write(HANDSHAKE);
			out.flush();
			Thread.sleep(500);
		});
	}

	/**
	 * Drip-feeds the handshake reply one byte at a time, sleeping {@code delayPerByteMs} between
	 * each byte. Used to demonstrate that SO_TIMEOUT is per-read rather than per-total-read.
	 */
	static FakeJdwpServer dripEcho(int delayPerByteMs) throws Exception {
		return new FakeJdwpServer((in, out) -> {
			readHandshake(in);
			for (byte b : HANDSHAKE) {
				out.write(new byte[]{ b });
				out.flush();
				Thread.sleep(delayPerByteMs);
			}
		});
	}

	int port() {
		return socket.getLocalPort();
	}

	@Override
	public void close() throws Exception {
		pool.shutdownNow();
		socket.close();
		pool.awaitTermination(2, TimeUnit.SECONDS);
	}

	private static void readHandshake(InputStream in) throws Exception {
		final byte[] buf = new byte[HANDSHAKE.length];
		int read = 0;
		while (read < buf.length) {
			final int n = in.read(buf, read, buf.length - read);
			if (n < 0) {
				return;
			}
			read += n;
		}
	}

	@FunctionalInterface
	private interface Behaviour {
		void run(InputStream in, OutputStream out) throws Exception;
	}
}
