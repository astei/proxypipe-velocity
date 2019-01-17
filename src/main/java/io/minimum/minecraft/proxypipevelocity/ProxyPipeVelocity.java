package io.minimum.minecraft.proxypipevelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import io.minimum.minecraft.proxypipevelocity.netty.ProxyPipeChannelInitializer;
import io.netty.channel.ChannelInitializer;
import org.slf4j.Logger;

@Plugin(
        id = "proxypipe",
        name = "ProxyPipe for Velocity",
        authors = {"tuxed", "ProxyPipe"},
        version = "1.0-SNAPSHOT"
)
public class ProxyPipeVelocity {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public ProxyPipeVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws Exception {
        // In order for ProxyPipe to work correctly on Velocity, we must hijack the Velocity channel pipeline and
        // insert our own handler. To do this, we use a custom ChannelInitializer.
        Object cm = Reflection.getField(server, "cm");
        Object initializerHolder = Reflection.getField(cm, "serverChannelInitializer");
        ChannelInitializer<?> ci = (ChannelInitializer<?>) Reflection.getField(initializerHolder, "initializer");

        ProxyPipeChannelInitializer initializer = new ProxyPipeChannelInitializer(ci, logger);
        Reflection.setField(initializerHolder, "initializer", initializer);

        logger.info("ProxyPipe plugin enabled");
    }
}
