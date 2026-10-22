package io.github.fabutnm.homepanel;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal RFC 6455 client: text messages only, which is all the Home Assistant WebSocket API uses. No library,
 * so the add-on stays small. Always connects directly (no proxy): the server is on the home network.
 */
final class WebSocket implements Closeable {
    static final int OP_CONTINUATION = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9,
            OP_PONG = 0xA;
    /** Larger messages are refused: a state list for a few dozen entities is a few tens of KB. */
    static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    private WebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Opens ws:// or wss:// and completes the handshake. readTimeoutMs bounds every later read too. */
    static WebSocket connect(URI uri, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        Socket socket = new Socket(Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(uri.getHost(), port), connectTimeoutMs);
            if (tls) socket = ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, uri.getHost(), port, true);
            socket.setSoTimeout(readTimeoutMs);
            socket.setTcpNoDelay(true);
            WebSocket ws = new WebSocket(socket);
            ws.handshake(uri, port);
            return ws;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw e;
        }
    }

    private void handshake(URI uri, int port) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + ":" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String head = readHead(in);
        String first = head.substring(0, Math.max(0, head.indexOf("\r\n")));
        if (!first.matches("HTTP/1\\.[01] 101.*")) throw new IOException("handshake refused: " + first);
        String accept = header(head, "Sec-WebSocket-Accept");
        if (!acceptFor(key).equals(accept)) throw new IOException("handshake: bad Sec-WebSocket-Accept");
    }

    static String acceptFor(String key) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The response head up to the blank line, byte by byte so nothing of the first frame is consumed. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed during handshake");
            head.write(b);
            if (head.size() > 16 * 1024) throw new IOException("handshake response too long");
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** The next text message; answers pings on the way. Throws EOFException when the server closes. */
    String readText() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inText = false;
        while (true) {
            Frame f = Frame.read(in);
            switch (f.opcode) {
                case OP_PING:
                    send(OP_PONG, f.payload);
                    break;
                case OP_PONG:
                    break;
                case OP_CLOSE:
                    throw new EOFException("closed by server");
                case OP_TEXT:
                case OP_BINARY:
                    message.reset();
                    inText = f.opcode == OP_TEXT;
                    // fall through: the first fragment carries payload too
                case OP_CONTINUATION:
                    if (message.size() + f.payload.length > MAX_MESSAGE) throw new IOException("message too large");
                    message.write(f.payload);
                    if (f.fin && inText) return message.toString(StandardCharsets.UTF_8.name());
                    break;
                default:
                    throw new IOException("unknown opcode " + f.opcode);
            }
        }
    }

    void sendText(String text) throws IOException {
        send(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        byte[] frame = Frame.encode(opcode, payload, mask);
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    @Override
    public void close() {
        try {
            send(OP_CLOSE, new byte[0]);
        } catch (IOException ignored) {
            // the socket may already be gone
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing left to do
        }
    }

    /** One frame on the wire. Client frames are always masked, server frames never are (RFC 6455 5.1). */
    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }

        static byte[] encode(int opcode, byte[] payload, byte[] mask) {
            int n = payload.length;
            int header = n < 126 ? 2 : n <= 0xFFFF ? 4 : 10;
            byte[] frame = new byte[header + 4 + n];
            frame[0] = (byte) (0x80 | opcode);
            if (n < 126) {
                frame[1] = (byte) (0x80 | n);
            } else if (n <= 0xFFFF) {
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) (n >>> 8);
                frame[3] = (byte) n;
            } else {
                frame[1] = (byte) (0x80 | 127);
                for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((long) n >>> (56 - 8 * i));
            }
            System.arraycopy(mask, 0, frame, header, 4);
            for (int i = 0; i < n; i++) frame[header + 4 + i] = (byte) (payload[i] ^ mask[i % 4]);
            return frame;
        }

        static Frame read(InputStream in) throws IOException {
            int b0 = readByte(in), b1 = readByte(in);
            long n = b1 & 0x7F;
            if (n == 126) {
                n = ((long) readByte(in) << 8) | readByte(in);
            } else if (n == 127) {
                n = 0;
                for (int i = 0; i < 8; i++) n = (n << 8) | readByte(in);
            }
            if (n < 0 || n > MAX_MESSAGE) throw new IOException("frame too large: " + n);
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) n];
            readFully(in, payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            return new Frame((b0 & 0x80) != 0, b0 & 0x0F, payload);
        }

        private static int readByte(InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed");
            return b;
        }

        private static void readFully(InputStream in, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) throw new EOFException("connection closed");
                off += r;
            }
        }
    }
}
