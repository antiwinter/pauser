package com.opentune.telegram;

import android.content.Context;

/**
 * TDLib JSON wrapper. Wraps native libtdjson.so calls.
 *
 * TDLib uses a single-threaded client model:
 *   - td_json_client_execute(requestJson): String → response JSON
 *   - td_set_verbosity(level): void
 *   - td_receive(timeout): String → update JSON (or null)
 *
 * Native .so files are bundled alongside classes.dex in the shim JAR.
 * DexClassLoader extracts them automatically when the JAR is loaded.
 */
public class TDLibClient {

    static {
        try {
            System.loadLibrary("tdjson");
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("TDLib native library not found. " +
                "Ensure libtdjson.so is bundled in the shim JAR for the target ABI.", e);
        }
    }

    /**
     * Execute a TDLib JSON-rpc request and return the response.
     *
     * @param requestJson JSON-rpc request (e.g. {"@type":"getMe","@extra":1})
     * @return response JSON string
     */
    public native String execute(String requestJson);

    /**
     * Receive an incoming update from TDLib.
     * Call in a loop with timeout to process updates.
     *
     * @param timeoutMs max wait time in seconds
     * @return update JSON or null on timeout
     */
    public native String receive(double timeoutMs);

    /** Set TDLib log verbosity (0-1023). */
    public static native void setVerbosity(int level);

    /** Create a native TDLib client instance. */
    public static native long createNativeClient();

    /** Destroy the native TDLib client. */
    public static native void destroyNativeClient(long clientId);
}
