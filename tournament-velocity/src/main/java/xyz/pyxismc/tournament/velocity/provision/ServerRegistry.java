package xyz.pyxismc.tournament.velocity.provision;

import java.net.InetSocketAddress;
import java.util.Optional;

import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * The subset of the Velocity proxy the provisioning stack needs: registering,
 * looking up and unregistering dynamic match servers. Adapters wire this to
 * {@link com.velocitypowered.api.proxy.ProxyServer}, fakes to test maps.
 */
public interface ServerRegistry {

    Optional<RegisteredServer> get(String serverId);

    void register(String serverId, InetSocketAddress address);

    void unregister(String serverId);
}
