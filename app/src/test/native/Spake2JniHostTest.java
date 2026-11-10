package com.flyfish233.crypto.spake2;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class Spake2JniHostTest {
    private static final byte[] CLIENT = "adb pair client\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\u0000".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        byte[] password = "123456".getBytes(StandardCharsets.UTF_8);
        byte[] aliceMessage;
        byte[] bobMessage;
        byte[] aliceKey;
        byte[] bobKey;

        try (Spake2Context alice = new Spake2Context(Spake2Role.Alice, CLIENT, SERVER);
             Spake2Context bob = new Spake2Context(Spake2Role.Bob, SERVER, CLIENT)) {
            aliceMessage = alice.generateMessage(password);
            bobMessage = bob.generateMessage(password);
            aliceKey = alice.processMessage(bobMessage);
            bobKey = bob.processMessage(aliceMessage);
            require(aliceMessage.length == Spake2Context.MAX_MSG_SIZE, "wrong Alice message length");
            require(bobMessage.length == Spake2Context.MAX_MSG_SIZE, "wrong Bob message length");
            require(aliceKey != null && aliceKey.length == Spake2Context.MAX_KEY_SIZE, "wrong Alice key length");
            require(bobKey != null && bobKey.length == Spake2Context.MAX_KEY_SIZE, "wrong Bob key length");
            require(Arrays.equals(aliceKey, bobKey), "JNI SPAKE2 client/server keys differ");
        }

        try (Spake2Context alice = new Spake2Context(Spake2Role.Alice, CLIENT, SERVER);
             Spake2Context bob = new Spake2Context(Spake2Role.Bob, SERVER, CLIENT)) {
            byte[] aliceMessage2 = alice.generateMessage(password);
            byte[] bobMessage2 = bob.generateMessage("654321".getBytes(StandardCharsets.UTF_8));
            byte[] aliceKey2 = alice.processMessage(bobMessage2);
            byte[] bobKey2 = bob.processMessage(aliceMessage2);
            require(aliceKey2 != null && bobKey2 != null, "SPAKE2 mismatch exchange did not complete");
            require(!Arrays.equals(aliceKey2, bobKey2), "different pairing codes unexpectedly derived the same key");
        }

        System.out.println("SPAKE2 JNI interoperability passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
