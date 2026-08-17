package com.tarosie.granularity.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Phase 0's acceptance criterion: a round-trip salt sync reproduces identical hashes on both sides. */
class WorldSaltTest {

    @AfterEach
    void clearClientState() {
        WorldSalt.ClientView.clear();
    }

    @Test
    @DisplayName("server derives, client decodes, both sides hash identically")
    void roundTripSyncAgreesOnHashes() {
        long worldSeed = -4_872_119_337_465_223_001L;

        // Server side.
        WorldSalt server = WorldSalt.derive(worldSeed);
        byte[] payload = server.encode();
        assertEquals(WorldSalt.ENCODED_BYTES, payload.length);

        // Wire, then client side.
        WorldSalt.ClientView.accept(WorldSalt.decode(payload));
        WorldSalt client = WorldSalt.ClientView.get();

        assertEquals(server, client);
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                assertEquals(server.hash(z, x), client.hash(z, x),
                        "client and server must derive the same world at (" + x + ", " + z + ")");
            }
        }
    }

    @Test
    @DisplayName("derivation is deterministic in the seed — same seed, same world")
    void derivationIsDeterministic() {
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 24301L}) {
            assertEquals(WorldSalt.derive(seed), WorldSalt.derive(seed));
        }
    }

    @Test
    @DisplayName("adjacent seeds do not produce adjacent salts")
    void derivationSeparatesNearbySeeds() {
        // Cheap smoke test for the one-way step actually mixing. It would pass for mix64 too --
        // what rules mix64 out is invertibility, which no unit test can demonstrate, so the
        // reasoning lives in WorldSalt's class javadoc rather than here.
        long a = WorldSalt.derive(0L).value();
        long b = WorldSalt.derive(1L).value();
        assertNotEquals(a, b);
        assertTrue(Long.bitCount(a ^ b) > 16,
                "expected the derivation to diffuse a one-bit seed change; differing bits: "
                        + Long.bitCount(a ^ b));
    }

    @Test
    @DisplayName("encode/decode is exact across the full 64-bit range")
    void codecRoundTrips() {
        for (long value : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL}) {
            WorldSalt salt = WorldSalt.of(value);
            assertEquals(salt, WorldSalt.decode(salt.encode()));
            assertEquals(value, WorldSalt.decode(salt.encode()).value());
        }
    }

    @Test
    @DisplayName("encoding is big-endian and fixed width")
    void encodingIsBigEndian() {
        assertArrayEquals(
                new byte[] {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF},
                WorldSalt.of(0x0123456789ABCDEFL).encode());
    }

    @Test
    @DisplayName("a short or long payload is rejected rather than silently misread")
    void decodeRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> WorldSalt.decode(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> WorldSalt.decode(new byte[9]));
    }

    @Test
    @DisplayName("reading the salt before login throws instead of defaulting to zero")
    void unsyncedClientThrows() {
        assertTrue(!WorldSalt.ClientView.isPresent());
        assertThrows(IllegalStateException.class, WorldSalt.ClientView::get);
    }

    @Test
    @DisplayName("disconnect clears the salt so it cannot leak into the next server's world")
    void disconnectClearsTheSalt() {
        WorldSalt.ClientView.accept(WorldSalt.derive(7L));
        assertTrue(WorldSalt.ClientView.isPresent());

        WorldSalt.ClientView.clear();
        assertTrue(!WorldSalt.ClientView.isPresent());
        assertThrows(IllegalStateException.class, WorldSalt.ClientView::get);
    }

    @Test
    @DisplayName("the salt keys the hash — a different salt gives a different world")
    void saltActuallyKeysTheHash() {
        WorldSalt a = WorldSalt.derive(1L);
        WorldSalt b = WorldSalt.derive(2L);
        assertNotEquals(a.hash(0, 0), b.hash(0, 0));
        assertEquals(Rng.positionHash(3, 11, a.value()), a.hash(3, 11));
    }
}
