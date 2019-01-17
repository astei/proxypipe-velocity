package io.minimum.minecraft.proxypipevelocity.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.slf4j.Logger;

public class ProxyPipeChannelInitializer extends ChannelInitializer<Channel> {
    private final ChannelInitializer<?> original;
    private final Logger logger;

    public ProxyPipeChannelInitializer(ChannelInitializer<?> original, Logger logger) {
        this.original = original;
        this.logger = logger;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        ch.pipeline().addFirst("original-velocity", this.original);
        ch.pipeline().addAfter("minecraft-decoder", "proxypipe", new ProxyPipeHandler(logger));
    }
}
