package xyz.pyxismc.tournament.velocity.testutil;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import xyz.pyxismc.tournament.velocity.provision.ServerRegistry;

/**
 * In-memory {@link ServerRegistry} for tests. Registered servers are stored
 * as addresses; lookups always return empty (no {@link RegisteredServer}
 * instances exist outside a running proxy).
 */
public final class FakeServerRegistry implements ServerRegistry {

    private final Map<String, InetSocketAddress> servers = new HashMap<>();

    @Override
    public Optional<RegisteredServer> get(String serverId) {
        return Optional.empty();
    }

    @Override
    public void register(String serverId, InetSocketAddress address) {
        this.servers.put(serverId, address);
    }

    @Override
    public void unregister(String serverId) {
        this.servers.remove(serverId);
    }

    public InetSocketAddress addressOf(String serverId) {
        return this.servers.get(serverId);
    }

    public boolean isRegistered(String serverId) {
        return this.servers.containsKey(serverId);
    }

    public int size() {
        return this.servers.size();
    }
}
