package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Covers {@link JDIConnectionService#describeReleasedSession()} — the one-line notice {@code connect()}
 * prepends when it tears down a differing target before a fresh attach, so the silent cross-target
 * wipe (the second gap flagged alongside issue #25's disconnect work) is announced. The notice is
 * only reachable on a live-VM target-switch success path, so it is exercised directly here.
 */
@DisplayName("connect() — released-session notice")
class JDIConnectionServiceReleasedSessionNoticeTest {

	private static JDIConnectionService serviceWith(WatcherManager watchers) {
		return JDIConnectionServiceTestSupport.newServiceWithCollaborators(
			mock(JdiEventListener.class), new BreakpointTracker(), new EventHistory(),
			watchers, new EvaluationGuard());
	}

	@Test
	@DisplayName("nothing set → empty notice keeps connect()'s reply terse")
	void shouldReturnEmptyWhenNothingSet() {
		final JDIConnectionService service = serviceWith(new WatcherManager());
		assertThat(service.describeReleasedSession()).isEmpty();
	}

	@Test
	@DisplayName("watchers present → notice names the counts, the old target, and points at jdwp_reconnect")
	void shouldDescribeClearedWatchers() {
		final WatcherManager watchers = new WatcherManager();
		watchers.createWatcher("w1", 1, "x");
		watchers.createWatcher("w2", 1, "y");
		final JDIConnectionService service = serviceWith(watchers);
		JDIConnectionServiceTestSupport.setLastSuccessfulAttach(service, "old-host", 5005);

		final String notice = service.describeReleasedSession();

		assertThat(notice)
			.contains("Released previous session at old-host:5005")
			.contains("0 breakpoint(s), 2 watcher(s) cleared")
			.contains("jdwp_reconnect");
	}

	@Test
	@DisplayName("state set but no recorded host → notice names a generic previous target")
	void shouldNameGenericPreviousTargetWhenHostUnset() {
		final WatcherManager watchers = new WatcherManager();
		watchers.createWatcher("w1", 1, "x");
		final JDIConnectionService service = serviceWith(watchers);
		// No setLastSuccessfulAttach — lastHost is null, exercising the generic-target fallback.

		final String notice = service.describeReleasedSession();

		assertThat(notice)
			.contains("Released previous session at previous target")
			.contains("jdwp_reconnect");
	}
}
