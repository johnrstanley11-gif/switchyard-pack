package classiccrates.vote;

import classiccrates.ClassicCrates;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A small Votifier-compatible listener, so the usual server list websites can report votes to a NeoForge server.
 * Both protocols the sites use are understood:
 * <ul>
 * <li>v1: the site encrypts "VOTE\nservice\nusername\naddress\ntimestamp\n" with the server's RSA public key
 *     (a 256 byte block);</li>
 * <li>v2 (NuVotifier): a JSON message signed with HMAC-SHA256 using a shared token, including the challenge sent
 *     in the greeting.</li>
 * </ul>
 * Keys live in config/classiccrates/votifier: public.key is what a website asks for as "Votifier public key",
 * token.txt is the v2 token.
 * <p>
 * Plain blocking sockets on a few daemon threads: votes are rare and tiny. Every vote is handed to the callback,
 * which has to move to the server thread itself.
 */
public class VoteListener implements AutoCloseable {

    public record Vote(String service, String username, String address, String timestamp) {
    }

    private static final int V2_MAGIC = 0x733A;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    private final ServerSocket serverSocket;
    private final PrivateKey privateKey;
    private final String token;
    private final Consumer<Vote> onVote;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ClassicCrates vote connection");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed = false;

    public VoteListener(Path keyDirectory, String host, int port, Consumer<Vote> onVote) throws Exception {
        Files.createDirectories(keyDirectory);
        this.privateKey = loadOrCreateKeys(keyDirectory);
        this.token = loadOrCreateToken(keyDirectory);
        this.onVote = onVote;

        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getByName(host), port));

        Thread acceptor = new Thread(this::acceptLoop, "ClassicCrates vote listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                workers.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!closed) {
                    ClassicCrates.LOGGER.warn("Vote listener accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            String challenge = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
            OutputStream out = socket.getOutputStream();
            out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] head = new byte[2];
            in.readFully(head);
            int magic = ((head[0] & 0xFF) << 8) | (head[1] & 0xFF);

            Vote vote;
            if (magic == V2_MAGIC) {
                vote = readV2(in, challenge);
                out.write("{\"status\":\"ok\"}\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else {
                vote = readV1(in, head);
            }
            ClassicCrates.LOGGER.info("Vote received from {} ({}) for player '{}'", vote.service(), socket.getInetAddress().getHostAddress(), vote.username());
            onVote.accept(vote);
        } catch (Exception e) {
            // Port scanners and broken senders end up here; one line, no stack trace
            ClassicCrates.LOGGER.warn("Rejected vote connection from {}: {}", socket.getInetAddress().getHostAddress(), e.toString());
            try {
                socket.getOutputStream().write("{\"status\":\"error\",\"cause\":\"invalid vote\"}\r\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        } finally {
            // Closed here (not by try-with-resources) so the error reply above can still be written
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Vote readV2(DataInputStream in, String challenge) throws Exception {
        int length = in.readUnsignedShort();
        if (length <= 0 || length > 4096) {
            throw new IOException("bad v2 message length " + length);
        }
        byte[] body = new byte[length];
        in.readFully(body);

        JsonObject message = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        String payloadText = message.get("payload").getAsString();
        byte[] signature = Base64.getDecoder().decode(message.get("signature").getAsString());

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(mac.doFinal(payloadText.getBytes(StandardCharsets.UTF_8)), signature)) {
            throw new SecurityException("v2 signature does not match the token");
        }

        JsonObject payload = JsonParser.parseString(payloadText).getAsJsonObject();
        if (!payload.has("challenge") || !challenge.equals(payload.get("challenge").getAsString())) {
            throw new SecurityException("v2 challenge does not match");
        }
        return checked(new Vote(
                payload.get("serviceName").getAsString(),
                payload.get("username").getAsString(),
                payload.has("address") ? payload.get("address").getAsString() : "",
                payload.has("timestamp") ? payload.get("timestamp").getAsString() : ""));
    }

    private Vote readV1(DataInputStream in, byte[] head) throws Exception {
        byte[] block = new byte[256];
        block[0] = head[0];
        block[1] = head[1];
        in.readFully(block, 2, 254);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        String[] lines = new String(cipher.doFinal(block), StandardCharsets.UTF_8).split("\n");
        if (lines.length < 5 || !lines[0].equals("VOTE")) {
            throw new IOException("not a v1 vote");
        }
        return checked(new Vote(lines[1], lines[2], lines[3], lines[4]));
    }

    private static Vote checked(Vote vote) throws IOException {
        // Minecraft names: 1-16 of [A-Za-z0-9_]. Anything else is not a player and never reaches a command or chat.
        if (!vote.username().matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("invalid player name in vote");
        }
        String service = vote.service().replaceAll("[^A-Za-z0-9_.\\- ]", "");
        if (service.length() > 40) service = service.substring(0, 40);
        return new Vote(service.isEmpty() ? "a vote site" : service, vote.username(), vote.address(), vote.timestamp());
    }

    private static PrivateKey loadOrCreateKeys(Path dir) throws Exception {
        Path publicFile = dir.resolve("public.key");
        Path privateFile = dir.resolve("private.key");
        if (!Files.exists(privateFile) || !Files.exists(publicFile)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.writeString(publicFile, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            Files.writeString(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            ClassicCrates.LOGGER.info("Created a new Votifier key pair in {}", dir);
        }
        byte[] encoded = Base64.getDecoder().decode(Files.readString(privateFile).trim());
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static String loadOrCreateToken(Path dir) throws IOException {
        Path file = dir.resolve("token.txt");
        if (!Files.exists(file)) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        return Files.readString(file).trim();
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }
}
