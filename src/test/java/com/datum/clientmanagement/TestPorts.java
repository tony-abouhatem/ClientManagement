package com.datum.clientmanagement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/** Local TCP ports for tests. */
public final class TestPorts {

    private TestPorts() {
    }

    /**
     * A port that was free a moment ago: use it to bind a container, or leave it unused to get connection refused.
     */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
