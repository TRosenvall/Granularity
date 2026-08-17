package com.tarosie.granularity.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The per-world salt that {@link Rng#positionHash} is keyed against, and the transport for getting
 * it to the client.
 *
 * <p>Design §4: <i>"the client cannot know the world seed. Sync a derived salt (never the real
 * seed) at login; both sides hash position against it."</i> Two requirements pull against each
 * other there, and both are real:
 *
 * <ul>
 *   <li><b>The salt must be a function of the seed.</b> Composition is derived from it, so two
 *       worlds created from the same seed have to look identical — that is a property players
 *       expect and would notice losing. A random per-world salt stored in save data would be
 *       strictly safer and is the wrong answer.</li>
 *   <li><b>The seed must not be recoverable from the salt.</b> This rules out deriving it with
 *       {@link Rng#mix64}, which is the obvious move and is wrong: the splitmix64 finalizer is a
 *       <i>bijection</i>, so {@code mix64(seed)} hands the seed straight back to anyone who knows
 *       the constants. Truncating a cryptographic digest is what makes the step one-way.</li>
 * </ul>
 *
 * <p><b>Honest limit.</b> Truncated SHA-256 is not invertible by algebra, but it is searchable. A
 * seed the player typed as text collapses to the 2^32 values of {@code String.hashCode}, and small
 * integer seeds are common — either is within reach of a targeted brute-force given a known salt.
 * So this hides the seed from inspection, not from a determined attacker. That is the guarantee
 * design §4 actually needs (do not hand the client the seed outright); if a stronger one is ever
 * wanted, it costs the same-seed-same-world property and should be a deliberate trade, not a patch.
 */
public final class WorldSalt {

    /**
     * Domain separator. Changing this string regenerates every world from the same seed, so it is
     * versioned: a future derivation change bumps {@code v1} rather than editing in place.
     */
    private static final byte[] DOMAIN = "granularity/world-salt/v1".getBytes(StandardCharsets.UTF_8);

    /** Wire size of an encoded salt, in bytes. */
    public static final int ENCODED_BYTES = Long.BYTES;

    private final long value;

    private WorldSalt(long value) {
        this.value = value;
    }

    /**
     * Server side: derive the salt from the world seed. Deterministic, so the same seed always
     * yields the same world, and one-way, so the synced value does not disclose the seed.
     */
    public static WorldSalt derive(long worldSeed) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE. If it is missing, the
            // world cannot be generated consistently, and failing loudly beats a silent fallback.
            throw new IllegalStateException("SHA-256 unavailable; cannot derive world salt", e);
        }
        digest.update(DOMAIN);
        digest.update(encodeLong(worldSeed));
        byte[] hash = digest.digest();

        long salt = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            salt = (salt << 8) | (hash[i] & 0xFFL);
        }
        return new WorldSalt(salt);
    }

    /** Wraps an already-known salt value — for tests, and for the client side of {@link #decode}. */
    public static WorldSalt of(long value) {
        return new WorldSalt(value);
    }

    /** The value to pass as {@link Rng#positionHash}'s {@code salt}. */
    public long value() {
        return value;
    }

    /**
     * Convenience for the common call. See {@link Rng} on the axis mapping: {@code y} is the
     * prototype's row axis and maps to world z.
     */
    public long hash(long y, long x) {
        return Rng.positionHash(y, x, value);
    }

    /** Big-endian, fixed width. */
    public byte[] encode() {
        return encodeLong(value);
    }

    /** Inverse of {@link #encode}. */
    public static WorldSalt decode(byte[] payload) {
        if (payload.length != ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Expected " + ENCODED_BYTES + " bytes, got " + payload.length);
        }
        long value = 0L;
        for (byte b : payload) {
            value = (value << 8) | (b & 0xFFL);
        }
        return new WorldSalt(value);
    }

    private static byte[] encodeLong(long v) {
        byte[] out = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            out[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorldSalt other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "WorldSalt[" + Long.toHexString(value) + "]";
    }

    /**
     * The server's salt for the loaded world, derived once from the seed.
     *
     * <p>Separate from {@link ClientView} rather than shared, even though an integrated server puts
     * both in one JVM. Call sites are side-specific — drops are computed server-side, meshes
     * client-side — so keeping the two apart means a call on the wrong side fails loudly in single
     * player instead of working there and desyncing only in multiplayer, which is the worst
     * possible place for this class of bug to first appear.
     */
    public static final class ServerView {
        private static volatile WorldSalt current;

        private ServerView() {
        }

        /** Called when the world loads, with the salt derived from its seed. */
        public static void accept(WorldSalt salt) {
            current = salt;
        }

        /** Called on server stop, so a stale salt cannot leak into the next world loaded. */
        public static void clear() {
            current = null;
        }

        public static WorldSalt get() {
            WorldSalt salt = current;
            if (salt == null) {
                throw new IllegalStateException("World salt requested before the world loaded");
            }
            return salt;
        }

        public static boolean isPresent() {
            return current != null;
        }
    }

    /**
     * The client's copy of the synced salt.
     *
     * <p>Unset until login delivers one, and reading it unset throws rather than defaulting to
     * zero. That is deliberate: a zero default would produce a plausible-looking world that
     * disagrees with the server everywhere, which is precisely the silent-desync failure this whole
     * module exists to prevent. Better a stack trace on the first block rendered.
     *
     * <p>Not yet wired to a {@code CustomPacketPayload} — nothing consumes the salt until Phase 1
     * gives it a composition function to key. {@link #encode} and {@link #decode} are the payload's
     * body; registration is the only piece missing.
     */
    public static final class ClientView {
        private static volatile WorldSalt current;

        private ClientView() {
        }

        /** Called on login, with the decoded payload. */
        public static void accept(WorldSalt salt) {
            current = salt;
        }

        /** Called on disconnect, so a stale salt cannot leak into the next server's world. */
        public static void clear() {
            current = null;
        }

        public static WorldSalt get() {
            WorldSalt salt = current;
            if (salt == null) {
                throw new IllegalStateException(
                        "World salt requested before login sync delivered it");
            }
            return salt;
        }

        public static boolean isPresent() {
            return current != null;
        }
    }
}
