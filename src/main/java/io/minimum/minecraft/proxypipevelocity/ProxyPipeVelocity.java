package io.minimum.minecraft.proxypipevelocity;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.minimum.minecraft.proxypipevelocity.netty.ProxyPipeChannelInitializer;
import io.netty.channel.ChannelInitializer;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "proxypipe",
        name = "ProxyPipe for Velocity",
        authors = {"tuxed", "ProxyPipe"},
        version = "1.0"
)
public class ProxyPipeVelocity {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public ProxyPipeVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) throws Exception {
        if (!shouldEnable()) {
            logger.info("Not initializing ProxyPipe; plugin is disabled!");
            return;
        }

        // In order for ProxyPipe to work correctly on Velocity, we must hijack the Velocity channel pipeline and
        // insert our own handler. To do this, we use a custom ChannelInitializer.
        Object cm = Reflection.getField(server, "cm");
        Object initializerHolder = Reflection.getField(cm, "serverChannelInitializer");
        ChannelInitializer<?> ci = (ChannelInitializer<?>) Reflection.getField(initializerHolder, "initializer");

        ProxyPipeChannelInitializer initializer = new ProxyPipeChannelInitializer(ci, logger);
        Reflection.setField(initializerHolder, "initializer", initializer);

        logger.info("ProxyPipe plugin enabled");
    }

    private boolean shouldEnable() {
        try {
            if (!Files.isDirectory(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
        } catch (IOException e) {
            throw new RuntimeException("Can't read config file", e);
        }

        Path ppConfigPath = dataDirectory.resolve("config.toml");

        Toml toml = new Toml();
        try (Reader reader = new FileReader(ppConfigPath.toFile())) {
            toml.read(reader);
        } catch (FileNotFoundException e) {
            // The file doesn't exist, so create it
            try (FileWriter writer = new FileWriter(ppConfigPath.toFile())) {
                new TomlWriter().write(ImmutableMap.of("enabled", true), writer);
            } catch (IOException e1) {
                throw new RuntimeException("Can't create " + ppConfigPath, e1);
            }
        } catch (IOException e) {
            throw new RuntimeException("Can't read " + ppConfigPath, e);
        }

        return toml.getBoolean("enabled", true);
    }
}
