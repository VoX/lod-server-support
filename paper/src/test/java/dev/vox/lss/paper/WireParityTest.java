package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mirror of the Fabric {@code dev.vox.lss.networking.payloads.WireParityTest}: pins the Paper
 * codec ({@link PaperPayloadHandler}) to the IDENTICAL explicit byte reference. Because both
 * suites assert against the same reference ops, any drift between the Fabric and Paper wire
 * formats fails one of them. S2C payloads: Paper-encoded bytes == reference. C2S payloads: Paper
 * decode of a reference frame yields the expected fields.
 */
class WireParityTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static byte[] ref(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    // ---- C2S (Paper decodes a Fabric-shaped frame) ----

    @Test
    void handshake() {
        for (int[] f : new int[][]{{15, 1}, {300, 0}, {15, -1}}) {
            byte[] frame = ref(b -> {
                b.writeVarInt(f[0]);
                b.writeVarInt(f[1]);
            });
            var d = PaperPayloadHandler.decodeHandshake(frame);
            assertNotNull(d);
            assertEquals(f[0], d.protocolVersion(), "handshake protocol " + f[0]);
            assertEquals(f[1], d.capabilities(), "handshake caps " + f[1]);
        }
    }

    @Test
    void batchChunkRequest() {
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] frame = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        var d = PaperPayloadHandler.decodeBatchChunkRequest(frame);
        assertNotNull(d);
        assertEquals(3, d.count());
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
        // empty
        var empty = PaperPayloadHandler.decodeBatchChunkRequest(new byte[]{0});
        assertNotNull(empty);
        assertEquals(0, empty.count());
    }

    @Test
    void batchChunkRequestExtremeCorners() {
        // ts=-1 (unknown/first request) and ts=Long.MAX_VALUE plus the (MAX,MAX)/(MAX,MIN)
        // corner positions — identical reference frame to the Fabric twin's
        // #batchChunkRequestExtremeCorners.
        long[] pos = {PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MAX_VALUE),
                PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE)};
        long[] ts = {-1L, Long.MAX_VALUE};
        byte[] frame = ref(b -> {
            b.writeVarInt(2);
            for (int i = 0; i < 2; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        var d = PaperPayloadHandler.decodeBatchChunkRequest(frame);
        assertNotNull(d);
        assertEquals(2, d.count());
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
    }

    // ---- S2C (Paper encodes; bytes must match the reference) ----

    @Test
    void sessionConfig() {
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            b.writeBoolean(true);
            b.writeVarInt(8);
            b.writeBoolean(false);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                3, true, 8, false));
    }

    @Test
    void sessionConfigVarIntBoundaries() {
        // lodDistance crossing the 1->2 byte VarInt boundary (127/128) and the 2048 config
        // max — the one variable-width field left in the 4-field frame. Identical reference
        // ops to the Fabric twin's #sessionConfigVarIntBoundaries.
        int[] cases = {127, 128, 2048};
        for (int lod : cases) {
            byte[] expected = ref(b -> {
                b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
                b.writeBoolean(true);
                b.writeVarInt(lod);
                b.writeBoolean(false);
                // v20-only data-version append (XVER §2.2); Paper reads it directly.
                b.writeVarInt(net.minecraft.SharedConstants.getCurrentVersion()
                        .dataVersion().version());
            });
            assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.PROTOCOL_VERSION, true, lod, false),
                    "sessionConfig lod=" + lod);
        }
    }

    @Test
    void batchResponse() {
        // Identical fill to the Fabric twin — type 0 is the retired-and-reserved v16
        // rate-limited tag, 200 a hypothetical future one. Both platforms' encoders must stay
        // type-agnostic and produce the same bytes for both; that is the parity being pinned.
        byte[] types = {(byte) 0, LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED, (byte) 200};
        long[] positions = {0L, PositionUtil.packPosition(10, 20), -1L,
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(4);
            for (int i = 0; i < 4; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeBatchResponse(types, positions, 4));
    }

    @Test
    void batchResponseAtMaxCountMatchesReference() {
        // 4096 entries puts the count VarInt in its 2-byte regime — the only count regime
        // production ever ships at full flush. Identical fill to the Fabric twin's
        // #batchResponseAtMaxCountMatchesReference, so Fabric codec bytes == Paper encoder
        // bytes at max count.
        int max = LSSConstants.MAX_BATCH_RESPONSES;
        byte[] types = new byte[max];
        long[] positions = new long[max];
        for (int i = 0; i < max; i++) {
            types[i] = (byte) (i % 3);
            positions[i] = PositionUtil.packPosition(i - 2048, 2048 - i);
        }
        byte[] expected = ref(b -> {
            b.writeVarInt(max);
            for (int i = 0; i < max; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeBatchResponse(types, positions, max));
    }

    @Test
    void dirtyColumns() {
        long[] positions = {PositionUtil.packPosition(10, 20), PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (long p : positions) b.writeLong(p);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeDirtyColumns(positions));
    }

    @Test
    void voxelColumnVanillaDimensions() {
        byte[] sections = {1, 2, 3};
        for (String dim : new String[]{
                "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            byte[] expected = ref(b -> {
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeUtf(dim);
                b.writeLong(-1L);
                b.writeByte(-1); // serve-source: unknown (source-less convenience path)
                b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    -5, Integer.MAX_VALUE, dim, -1L, sections), "voxelColumn " + dim);
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, "lsstest:custom", 42L, sections));
    }

    @Test
    void voxelColumnEmptySectionBytes() {
        // sectionBytes=byte[0] is the legitimate "nothing visible" shape: it must encode as
        // a single 0x00 length VarInt (identical reference to the Fabric twin, whose decoder
        // is what hands the client its defensive empty-bytes ingest report).
        byte[] expected = ref(b -> {
            b.writeInt(11);
            b.writeInt(-7);
            b.writeUtf("minecraft:overworld");
            b.writeLong(5L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
            b.writeByteArray(new byte[0]);
        });
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                11, -7, "minecraft:overworld", 5L, new byte[0]);
        assertArrayEquals(expected, encoded);
        assertEquals(0, encoded[encoded.length - 1],
                "empty section bytes must encode as a single 0x00 length VarInt");
    }

    // ---- Constants pins (Paper-classpath twins of the Fabric ProtocolConstantsTest) ----

    @Test
    void channelConstantsParseDistinctUnderLssAndVersionPinned() {
        String[] channels = {
                LSSConstants.CHANNEL_HANDSHAKE, LSSConstants.CHANNEL_CHUNK_REQUEST,
                LSSConstants.CHANNEL_SESSION_CONFIG, LSSConstants.CHANNEL_DIRTY_COLUMNS,
                LSSConstants.CHANNEL_VOXEL_COLUMN, LSSConstants.CHANNEL_BATCH_RESPONSE};
        var distinct = new HashSet<String>();
        for (String channel : channels) {
            var id = Identifier.parse(channel); // throws on a typo'd channel string
            assertEquals(LSSConstants.MOD_ID, id.getNamespace(),
                    channel + " must live under the lss: namespace");
            distinct.add(id.toString());
        }
        assertEquals(6, distinct.size(), "channel ids must be pairwise distinct");
        // Bump the literal only with a deliberate wire change reviewed on both platforms.
        // 16 -> 17: the declarative want-set model retires the rate-limited bounce (byte 0).
        // 18: serve-source byte; 19: codec byte (zstd column frames).
        // 20: identity-dictionary section bodies (cross-version-identity-encoding-plan.md).
        assertEquals(20, LSSConstants.PROTOCOL_VERSION);
    }

    @Test
    void wireIdentityBytesArePinnedLiterally() {
        // Twin of the Fabric pin (ProtocolConstantsTest): the parity fixtures reference
        // these constants symbolically, so renumbering fails nothing else — while released
        // clients hard-code the values. Change only with a protocol bump.
        assertEquals(1, LSSConstants.RESPONSE_UP_TO_DATE);
        assertEquals(2, LSSConstants.RESPONSE_NOT_GENERATED);
        assertEquals(0, LSSConstants.RESPONSE_RATE_LIMITED_V16);
        assertEquals(16, LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
        assertEquals(18, LSSConstants.V18_COMPAT_PROTOCOL_VERSION);
        assertEquals(1, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        assertEquals(0, LSSConstants.COLUMN_SOURCE_IN_MEMORY);
        assertEquals(1, LSSConstants.COLUMN_SOURCE_DISK);
        assertEquals(2, LSSConstants.COLUMN_SOURCE_GENERATION);
    }

    // ---- Meta: the parity corpus must cover the whole v17 payload surface ----

    @Test
    void referenceFramesCoverEveryDeclaredChannel() throws IllegalAccessException {
        // A 7th payload needs a new CHANNEL_* constant; this set-equality trips then,
        // forcing a reference frame in this suite (and the Fabric twin) before it goes
        // green again. The literals below double as this suite's coverage list.
        var declared = new HashSet<String>();
        for (var f : LSSConstants.class.getDeclaredFields()) {
            if (f.getName().startsWith("CHANNEL_") && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        var covered = Set.of("lss:handshake_c2s", "lss:batch_chunk_req", "lss:client_info",
                "lss:session_config", "lss:dirty_columns", "lss:voxel_column", "lss:batch_response",
                // Far players (E1): bodies are FarPlayerWire byte[] on BOTH platforms —
                // parity holds by construction (one codec, two carriers). Honesty note
                // (review m10): the Paper CARRIER (NMS DiscardedPayload / the plugin
                // messaging ingress) is untestable at Tier 1 — no reference frames
                // exist HERE; FarPlayerWireTest owns the body bytes and the Fabric twin
                // pins its own carrier framing. The census entry only pins that the
                // channels are declared.
                "lss:far_player_prefs", "lss:far_player_roster", "lss:far_player_updates",
                // Region summaries (P2): RegionSummaryWire byte[] bodies on BOTH
                // platforms — the same one-codec-two-carriers doctrine as far players
                // (RegionSummaryWireTest owns the body bytes; the Fabric twin pins its
                // carrier framing; Paper's carrier is the same NMS DiscardedPayload /
                // plugin-messaging ingress as above).
                "lss:region_summary_req", "lss:region_summary",
                // Stamped up_to_date (stamped-up-to-date-plan.md §3): ColumnStampsWire
                // byte[] bodies, same doctrine (ColumnStampsWireTest owns the bytes;
                // Paper's carrier is the same NMS DiscardedPayload send).
                "lss:col_stamps");
        assertEquals(covered, declared,
                "every LSS channel must be accounted for in this census — raw-byte[]"
                + " channels are enumerated with their codec-owning suite named in the"
                + " comment above (frames live there); a FriendlyByteBuf payload needs"
                + " reference frames in BOTH WireParityTests");
    }

    @Test
    void v19EchoStaysFourFieldWithNoDataVersionAppend() {
        // The v20-only append must NOT ride the v19 echo — the strict v0.9.x client
        // hard-kicks on a trailing byte (Fabric twin pins the same shape).
        byte[] expected = ref(b -> {
            b.writeVarInt(LSSConstants.V19_COMPAT_PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.V19_COMPAT_PROTOCOL_VERSION, true, 256, true));
    }

    @Test
    void clientInfoDecodesOneVarIntAndToleratesTrailingBytes() {
        // The lss:client_info sidecar (XVER §2.2): one VarInt data version; trailing
        // bytes tolerated (a future client may append fields).
        byte[] plain = ref(b -> b.writeVarInt(3955));
        assertEquals(3955, PaperPayloadHandler.decodeClientInfo(plain));
        byte[] trailing = ref(b -> {
            b.writeVarInt(3955);
            b.writeVarInt(42);
        });
        assertEquals(3955, PaperPayloadHandler.decodeClientInfo(trailing));
    }

    // ---- v16 compat legacy shapes (docs/planning/v16-compat-design.md §2) ----
    // Reference ops mirror the v0.6.2 encoders VERBATIM and are IDENTICAL to the Fabric
    // twin's v16 tests — the cross-impl parity guard for the legacy dialect.

    @Test
    void sessionConfigV16EncodesTheSixFieldLayoutEchoingVersion16() {
        byte[] expected = ref(b -> {
            b.writeVarInt(16);     // MUST echo 16 — the v0.6.2 codec hard-gates on this VarInt
            b.writeBoolean(true);  // enabled
            b.writeVarInt(101);    // lodDistanceChunks
            b.writeVarInt(200);    // syncOnLoadConcurrencyLimitPerPlayer (the client's pacing)
            b.writeVarInt(7);      // generationConcurrencyLimitPerPlayer
            b.writeBoolean(false); // generationEnabled
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfigV16(
                true, 101, 200, 7, false));
    }

    @Test
    void rewriteColumnToV16DropsExactlyTheSourceByteForEveryProducerTag() {
        // Dialect totality at the wire: whichever producer baked the frame, the splice must
        // yield the IDENTICAL legacy frame — a leaked source byte is parsed by the old
        // client as the section-array length VarInt and hard-kicks it.
        byte[] sections = {9, 8, 7};
        byte[] expectedLegacy = ref(b -> {
            b.writeInt(3);
            b.writeInt(-4);
            b.writeUtf("minecraft:overworld");
            b.writeLong(1234L);
            b.writeByteArray(sections);
        });
        byte[] sources = {LSSConstants.COLUMN_SOURCE_IN_MEMORY, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_SOURCE_GENERATION, (byte) -1};
        for (byte source : sources) {
            byte[] v18Frame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    3, -4, "minecraft:overworld", 1234L, source, sections);
            assertArrayEquals(expectedLegacy, PaperPayloadHandler.rewriteColumnToV16(v18Frame),
                    "source tag " + source + " must vanish identically");
            assertEquals(PositionUtil.packPosition(3, -4),
                    PaperPayloadHandler.readColumnPackedPos(v18Frame),
                    "the shim's prune key must come from the frame's leading coordinate ints");
        }
    }

    @Test
    void rewriteColumnToV16KeepsTheGhostClearAndLongDimensions() {
        // The 0-section authoritative clear must survive the splice (the v0.6.2 client
        // handles it: isClearColumn + air-fill), and a >127-char dimension string (2-byte
        // VarInt UTF prefix) must not shift the computed source-byte offset.
        String longDim = "lsstest:" + "d".repeat(120);
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf(longDim);
            b.writeLong(42L);
            b.writeByteArray(new byte[0]);
        });
        byte[] v18Frame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, longDim, 42L, LSSConstants.COLUMN_SOURCE_IN_MEMORY, new byte[0]);
        assertArrayEquals(expected, PaperPayloadHandler.rewriteColumnToV16(v18Frame));
    }

    // ---- v18 compat legacy shapes (docs/planning/v18-compat-design.md §2.6) ----
    // Reference ops mirror the v0.8.2 decoder VERBATIM and are IDENTICAL to the Fabric
    // twin's v18 tests — the cross-impl parity guard for the protocol-18 dialect.

    @Test
    void sessionConfigEchoing18IsTheCurrentFourFieldLayout() {
        // The v18 reply is NOT a new shape — the same 4-field encode with the version
        // value 18 (the v0.8.x gate hard-requires its own version; the encoder must not
        // branch on the value).
        byte[] expected = ref(b -> {
            b.writeVarInt(LSSConstants.V18_COMPAT_PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.V18_COMPAT_PROTOCOL_VERSION, true, 256, true));
    }

    @Test
    void rewriteColumnToV18DropsExactlyTheCodecByteForEveryProducerTag() {
        // Dialect totality at the wire: whichever producer baked the frame — INCLUDING the
        // store (source 3, a value no v0.8.x client ever saw; it passes through verbatim
        // under the forward-safety rule) — the splice must keep the source byte and drop
        // only the codec byte. A leaked codec byte is parsed by the v18 client as the
        // section-array length VarInt and hard-kicks it.
        byte[] sections = {9, 8, 7};
        byte[] sources = {LSSConstants.COLUMN_SOURCE_IN_MEMORY, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_SOURCE_GENERATION, LSSConstants.COLUMN_SOURCE_STORE, (byte) -1};
        for (byte source : sources) {
            byte[] expectedV18 = ref(b -> {
                b.writeInt(3);
                b.writeInt(-4);
                b.writeUtf("minecraft:overworld");
                b.writeLong(1234L);
                b.writeByte(source); // kept verbatim — the v18 layout HAS the source byte
                b.writeByteArray(sections);
            });
            byte[] currentFrame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    3, -4, "minecraft:overworld", 1234L, source, sections);
            assertArrayEquals(expectedV18, PaperPayloadHandler.rewriteColumnToV18(currentFrame),
                    "source tag " + source + " must survive with only the codec byte gone");
        }
    }

    @Test
    void rewriteColumnToV18ThrowsOnANonRawCodec() {
        // The v18 layout has nowhere to carry a codec: a zstd-framed body spliced through
        // would decode as garbage on the old client (hard-kick class), so the splice must
        // THROW and let the egress guard's warn-drop contain it — mirroring the v16 pin.
        byte[] framed = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                1, 2, "minecraft:overworld", 7L, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_CODEC_ZSTD, new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class,
                () -> PaperPayloadHandler.rewriteColumnToV18(framed));
    }

    @Test
    void rewriteColumnToV18KeepsTheGhostClearAndLongDimensions() {
        // The 0-section authoritative clear must survive the splice (the v0.8.x client
        // handles it: isClearColumn + air-fill), and a >127-char dimension string (2-byte
        // VarInt UTF prefix) must not shift the computed codec-byte offset.
        String longDim = "lsstest:" + "d".repeat(120);
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf(longDim);
            b.writeLong(42L);
            b.writeByte(LSSConstants.COLUMN_SOURCE_IN_MEMORY);
            b.writeByteArray(new byte[0]);
        });
        byte[] currentFrame = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                0, 0, longDim, 42L, LSSConstants.COLUMN_SOURCE_IN_MEMORY, new byte[0]);
        assertArrayEquals(expected, PaperPayloadHandler.rewriteColumnToV18(currentFrame));
    }
    // ---- map-policy session flags (map-policy plan A2/A5): Paper's hand encoder ----
    // The expected hex was captured from the PRE-CHANGE encoder (2026-10-13). Paper's
    // encoder writes the RUNTIME data version (5023 = 9f27 on this line), while the Fabric
    // twin SessionConfigFlagsWireTest pins the codec with an explicit one (4554 = ca23):
    // the v20 frames share the LAYOUT (fields, then the flags after the data version), not
    // the bytes; the 19/18/16 echoes carry no data version and are byte-identical.

    private static final int OLD_CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS
            | LSSConstants.CAPABILITY_ZSTD_COLUMNS | LSSConstants.CAPABILITY_FAR_PLAYERS;
    private static final int NEW_CAPS = OLD_CAPS | LSSConstants.CAPABILITY_MAP_POLICY;

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    @Test
    void theCapturedDataVersionTailMatchesThisLine() {
        assertEquals("9f27", hex(ref(b -> b.writeVarInt(SharedConstants.getCurrentVersion()
                        .dataVersion().version()))),
                "the captured pre-change hex below embeds this line's data version (5023)"
                        + " — a port re-captures it");
    }

    @Test
    void aSessionWithoutTheBitGetsThe0180BytesOnPaperWhateverThePolicy() {
        for (boolean deny : new boolean[]{false, true}) {
            assertEquals("14018004019f27", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.PROTOCOL_VERSION, true, 512, true, OLD_CAPS, deny)));
            assertEquals("140040009f27", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.PROTOCOL_VERSION, false, 64, false, OLD_CAPS, deny)));
        }
        assertEquals("14018004019f27", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, true, 512, true)), "the capability-less overload");
    }

    @Test
    void everyEchoDialectToADeclaringHandshakeIsThe0180FrameOnPaper() {
        for (boolean deny : new boolean[]{false, true}) {
            assertEquals("1301800401", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, true, 512, true, NEW_CAPS, deny)));
            assertEquals("1300800401", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, false, 512, true, NEW_CAPS, deny)),
                    "the QUIET refusal to a raced 19 rung");
            assertEquals("1201800401", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, true, 512, true, NEW_CAPS, deny)));
        }
        assertEquals("10018004c8012801", hex(PaperPayloadHandler.encodeSessionConfigV16(
                true, 512, 200, 40, true)), "the v16 layout (and the re-attach prompt) never carries flags");
    }

    @Test
    void aReasonJoinsTheFlagsOnPaperExactlyAsOnFabric() {
        // 0.23.0 plan §3 (the Fabric twin: SessionConfigFlagsWireTest). enabled=false, 512, gen,
        // the runtime data version, then the flags.
        String off = "14008004019f27";
        assertEquals(off + "00", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, 0, 512, true, NEW_CAPS, false)));
        assertEquals(off + "02", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, LSSConstants.SESSION_FLAG_OFF_SERVER, 512, true, NEW_CAPS, false)));
        assertEquals(off + "04", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, LSSConstants.SESSION_FLAG_OFF_WORLD, 512, true, NEW_CAPS, false)));
        assertEquals(off + "08", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, LSSConstants.SESSION_FLAG_OFF_PERMISSION, 512, true, NEW_CAPS, false)));
        assertEquals(off + "05", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, LSSConstants.SESSION_FLAG_OFF_WORLD, 512, true, NEW_CAPS, true)),
                "beside the map bit");
        assertEquals(off + "02", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, 0x43, 512, true, NEW_CAPS, false)),
                "only reason bits get through; the map bit comes from the decision alone");
        // An enabled answer never carries a reason.
        assertEquals("14018004019f27" + "00", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, true, LSSConstants.SESSION_FLAG_OFF_SERVER, 512, true, NEW_CAPS, false)));
        // A session without the bit, and every echo: the bytes of before, whatever the reason
        // (a 0.10 to 0.18 client is kicked by a trailing byte).
        for (int reason : new int[]{0, LSSConstants.SESSION_FLAG_OFF_SERVER, LSSConstants.SESSION_FLAG_OFF_WORLD,
                LSSConstants.SESSION_FLAG_OFF_PERMISSION}) {
            assertEquals(off, hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.PROTOCOL_VERSION, false, reason, 512, true, NEW_CAPS & ~LSSConstants.CAPABILITY_MAP_POLICY, true)));
            assertEquals("1300800401", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.V19_COMPAT_PROTOCOL_VERSION, false, reason, 512, true, NEW_CAPS, false)));
            assertEquals("1200800401", hex(PaperPayloadHandler.encodeSessionConfig(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, false, reason, 512, true, NEW_CAPS, false)));
            // The form without a reason is the form with reason 0.
            assertArrayEquals(PaperPayloadHandler.encodeSessionConfig(LSSConstants.PROTOCOL_VERSION, false, 64, false, NEW_CAPS, true),
                    PaperPayloadHandler.encodeSessionConfig(LSSConstants.PROTOCOL_VERSION, false, 0, 64, false, NEW_CAPS, true));
        }
    }

    @Test
    void aDeclaringV20SessionGetsTheFlagsAfterTheDataVersionOnPaper() {
        assertEquals("14018004019f27" + "01", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, NEW_CAPS, true)));
        assertEquals("14018004019f27" + "00", hex(PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, true, 512, true, NEW_CAPS, false)));
        // Identical reference ops to the Fabric twin's flags append.
        byte[] expected = ref(b -> {
            b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
            b.writeBoolean(false);
            b.writeVarInt(2048);
            b.writeBoolean(true);
            b.writeVarInt(SharedConstants.getCurrentVersion().dataVersion().version());
            b.writeVarInt(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION);
        });
        assertArrayEquals(expected, PaperPayloadHandler.encodeSessionConfig(
                LSSConstants.PROTOCOL_VERSION, false, 2048, true, NEW_CAPS, true));
        assertEquals(LSSConstants.SESSION_FLAGS_ABSENT, PaperPayloadHandler.sessionFlagsFor(OLD_CAPS, true));
        assertEquals(0, PaperPayloadHandler.sessionFlagsFor(NEW_CAPS, false));
    }
}
