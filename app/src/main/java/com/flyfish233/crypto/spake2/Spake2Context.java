package com.flyfish233.crypto.spake2;

import java.util.Objects;
import javax.security.auth.Destroyable;

/**
 * ADB-compatible SPAKE2-over-Edwards25519 context backed by pinned BoringSSL.
 * One instance must be used for exactly one pairing attempt.
 */
public final class Spake2Context implements Destroyable, AutoCloseable {
    public static final int MAX_MSG_SIZE = 32;
    public static final int MAX_KEY_SIZE = 64;
    private long nativeContext;

    static {
        System.loadLibrary("adb_spake2");
    }

    public Spake2Context(Spake2Role role, byte[] myName, byte[] theirName) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(myName, "myName");
        Objects.requireNonNull(theirName, "theirName");
        if (myName.length == 0 || theirName.length == 0 ||
                myName.length > 4096 || theirName.length > 4096) {
            throw new IllegalArgumentException("Invalid SPAKE2 participant names");
        }
        nativeContext = nativeCreate(
                role == Spake2Role.Alice ? 0 : 1,
                myName.clone(), theirName.clone());
        if (nativeContext == 0) {
            throw new IllegalStateException("Unable to create SPAKE2 context");
        }
    }

    public synchronized byte[] generateMessage(byte[] password) {
        Objects.requireNonNull(password, "password");
        if (nativeContext == 0) throw new IllegalStateException("The context was destroyed");
        if (password.length == 0 || password.length > 65536) {
            throw new IllegalArgumentException("Invalid SPAKE2 password material");
        }
        return nativeGenerateMessage(nativeContext, password);
    }

    /**
     * Returns null when the peer message fails SPAKE2 validation, matching Kadb's
     * nullable Android pairing contract.
     */
    public synchronized byte[] processMessage(byte[] theirMsg) {
        Objects.requireNonNull(theirMsg, "theirMsg");
        if (nativeContext == 0) throw new IllegalStateException("The context was destroyed");
        if (theirMsg.length == 0 || theirMsg.length > MAX_MSG_SIZE) {
            throw new IllegalArgumentException("Invalid peer SPAKE2 message");
        }
        return nativeProcessMessage(nativeContext, theirMsg);
    }

    @Override
    public synchronized boolean isDestroyed() {
        return nativeContext == 0;
    }

    @Override
    public synchronized void destroy() {
        if (nativeContext != 0) {
            long handle = nativeContext;
            nativeContext = 0;
            nativeDestroy(handle);
        }
    }

    @Override
    public void close() {
        destroy();
    }

    private static native long nativeCreate(int role, byte[] myName, byte[] theirName);
    private static native byte[] nativeGenerateMessage(long context, byte[] password);
    private static native byte[] nativeProcessMessage(long context, byte[] theirMsg);
    private static native void nativeDestroy(long context);
}
