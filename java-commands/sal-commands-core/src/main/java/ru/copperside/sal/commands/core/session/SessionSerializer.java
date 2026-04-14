package ru.copperside.sal.commands.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Packs/unpacks session in .NET-compatible format:
 * <pre>
 *   Serialize:   session → JSON string → BinaryWriter.Write(string) → Deflate → Base64
 *   Deserialize: Base64 → Inflate → BinaryReader.ReadString() → JSON string → ObjectNode
 * </pre>
 *
 * <p>.NET {@code BinaryWriter.Write(string)} writes a length-prefixed UTF-8 string:
 * length as 7-bit encoded integer (variable-length, 1-5 bytes), then raw UTF-8 bytes.
 * See: {@code TCB.SAL.Client.Serializers.SessionSerializer} — uses
 * {@code BinaryWriter(DeflateStream)} wrapping {@code SalSerializer.Serialize(session.GetAllData())}.
 *
 * <p>On deserialization failure, returns empty ObjectNode and logs a warning —
 * mirrors .NET behavior where corrupted session falls back to an empty/fake session.
 */
public class SessionSerializer {

    private static final Logger log = LoggerFactory.getLogger(SessionSerializer.class);

    private final ObjectMapper mapper;

    public SessionSerializer() {
        // Plain ObjectMapper — session JSON keys are whatever .NET wrote (PascalCase).
        // We don't force any naming strategy here because we read/write raw session
        // data as-is. The keys (SessionId, OperationId, etc.) are already PascalCase
        // when built by caller.
        this.mapper = new ObjectMapper();
    }

    /**
     * Serialize session to .NET-compatible Base64 string.
     * Format: JSON → BinaryWriter.Write(string) → Deflate → Base64
     */
    public String serialize(ObjectNode session) {
        try {
            String json = mapper.writeValueAsString(session);
            byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // nowrap=true → raw DEFLATE without zlib header/checksum, matching .NET DeflateStream
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(bos, deflater)) {
                // .NET BinaryWriter string format: 7-bit encoded length prefix + UTF-8 bytes
                write7BitEncodedInt(deflate, utf8.length);
                deflate.write(utf8);
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize session", e);
        }
    }

    /**
     * Deserialize .NET-format Base64 session string.
     * Format: Base64 → Inflate → BinaryReader.ReadString() → JSON → ObjectNode
     */
    public ObjectNode deserialize(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(base64);
            // nowrap=true → raw DEFLATE, matching .NET DeflateStream
            Inflater inflater = new Inflater(true);
            try (InflaterInputStream inflate = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
                // .NET BinaryReader string format: read 7-bit encoded length, then UTF-8 bytes
                int length = read7BitEncodedInt(inflate);
                byte[] utf8 = inflate.readNBytes(length);
                if (utf8.length != length) {
                    log.warn("Session truncated: expected {} bytes, got {}", length, utf8.length);
                    return mapper.createObjectNode();
                }
                String json = new String(utf8, StandardCharsets.UTF_8);
                JsonNode node = mapper.readTree(json);
                if (node instanceof ObjectNode obj) {
                    return obj;
                }
                log.warn("Session JSON root is not an object; returning empty");
                return mapper.createObjectNode();
            }
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to deserialize session ({} chars), returning empty: {}",
                base64.length(), e.getMessage());
            return mapper.createObjectNode();
        }
    }

    /** Access to ObjectMapper — for building ObjectNode sessions in callers. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Write an integer in .NET 7-bit encoded format.
     * Each byte stores 7 data bits; high bit means "more bytes follow".
     * Matches {@code System.IO.BinaryWriter.Write7BitEncodedInt()}.
     */
    private static void write7BitEncodedInt(java.io.OutputStream out, int value) throws IOException {
        int v = value;
        while (v >= 0x80) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /**
     * Read a 7-bit encoded integer from a stream.
     * Matches {@code System.IO.BinaryReader.Read7BitEncodedInt()}.
     */
    private static int read7BitEncodedInt(java.io.InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("Unexpected end of stream reading 7-bit encoded int");
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("Bad 7-bit encoded int (too many bytes)");
        } while ((b & 0x80) != 0);
        return result;
    }
}
