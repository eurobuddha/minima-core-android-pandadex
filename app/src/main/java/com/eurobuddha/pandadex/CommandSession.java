package com.eurobuddha.pandadex;

import java.util.function.BooleanSupplier;

/** Main-thread identity generation, following KeySet's obsolete-load token guard.
 * A snapshot is captured before asynchronous funding and checked again at IPC dispatch. */
final class CommandSession {
    static final String CHANGED = "Wallet connection changed. This queued command was not sent. Refresh and review retained receipts before trying again.";
    interface Sender { void run(String command, NodeApi.Cb callback, BooleanSupplier authorized); }
    private final Sender sender;
    private final java.util.function.Supplier<Object> connection;
    private Object generation = new Object();
    private Object boundConnection;
    CommandSession(Sender sender) { this(sender, () -> null); }
    CommandSession(Sender sender, java.util.function.Supplier<Object> connection) {
        this.sender = sender; this.connection = connection; boundConnection = connection.get();
    }
    /** Only a fresh identity read may bind new work to a different observed connection. */
    void bindConnection() {
        Object current = connection.get();
        if (boundConnection != current) { boundConnection = current; invalidate(); }
    }
    void invalidate() { generation = new Object(); }
    Snapshot snapshot() { return new Snapshot(generation); }
    static boolean authorized(BooleanSupplier guard) {
        try { return guard != null && guard.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }
    final class Snapshot implements FundingCoins.Command {
        private final Object original, originalConnection;
        private Snapshot(Object original) { this.original = original; originalConnection = boundConnection; }
        boolean current() { return generation == original && connection.get() == originalConnection; }
        public void run(String command, NodeApi.Cb callback) {
            if (!current()) { if (callback != null) callback.onError(CHANGED); return; }
            sender.run(command, callback, this::current);
        }
    }
}
