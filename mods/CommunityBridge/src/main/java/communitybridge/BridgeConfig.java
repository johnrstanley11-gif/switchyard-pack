package communitybridge;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

record BridgeConfig(boolean enabled, URI websiteUrl, String serverId, String token,
                    int pollSeconds, int maxOutbox, int maxJournal, Set<String> commandRoots) {
    static BridgeConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "# Configure this file and restart. See server-mod/README.md.\n"
                    + "enabled=false\nwebsiteUrl=https://community.example.com\nserverId=main\napiToken=\n"
                    + "allowInsecureHttp=false\ncommandPollSeconds=10\nmaxOutboxEvents=5000\n"
                    + "maxJournalEntries=50000\nallowedCommandRoots=crates,give,lp\n", StandardCharsets.UTF_8);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        boolean enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        URI website = URI.create(properties.getProperty("websiteUrl", "https://community.example.com").replaceAll("/+$", ""));
        String id = properties.getProperty("serverId", "main").trim();
        String token = properties.getProperty("apiToken", "").trim();
        if (enabled) {
            if (website.getHost() == null || website.getUserInfo() != null || website.getQuery() != null
                    || website.getFragment() != null) throw new IOException("Invalid websiteUrl");
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(website.getHost());
            boolean httpAllowed = loopback || Boolean.parseBoolean(properties.getProperty("allowInsecureHttp", "false"));
            if (!("https".equals(website.getScheme()) || ("http".equals(website.getScheme()) && httpAllowed)))
                throw new IOException("Use HTTPS; insecure remote HTTP is disabled");
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || token.length() < 32)
                throw new IOException("Configure a valid serverId and apiToken of at least 32 characters");
        }
        Set<String> roots = Arrays.stream(properties.getProperty("allowedCommandRoots", "crates,give,lp").split(","))
                .map(String::trim).filter(s -> s.matches("[a-z0-9_:]+" )).collect(Collectors.toUnmodifiableSet());
        return new BridgeConfig(enabled, website, id, token,
                bounded(properties, "commandPollSeconds", 10, 5, 300),
                bounded(properties, "maxOutboxEvents", 5000, 100, 50000),
                bounded(properties, "maxJournalEntries", 50000, 100, 1000000), roots);
    }

    private static int bounded(Properties p, String name, int fallback, int min, int max) {
        return Math.clamp(Integer.parseInt(p.getProperty(name, Integer.toString(fallback))), min, max);
    }
}
