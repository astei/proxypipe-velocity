package io.minimum.minecraft.proxypipevelocity.netty;

import com.google.common.net.InetAddresses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public class ProxyPipeHandler extends ChannelInboundHandlerAdapter {
    private static final PublicKey PROXYPIPE_PUBLIC_KEY;

    private static final Class<?> HANDSHAKE;
    private static final Field HANDSHAKE_ADDRESS_FIELD;
    private static final Class<?> MINECRAFTCONNECTION;
    private static final Field MC_CONNECTION_REMOTE_ADDRESS;

    static {
        try {
            PROXYPIPE_PUBLIC_KEY = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                    .decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAErI/XQrUaWgwG/gAPa08O20ZL/is94JlRKfgZXRlUt6/YXoN17AdlDjILrk3QVmA7w5VVYKWhcHXN4JFXCAR6Zw==")));

            HANDSHAKE = Class.forName("com.velocitypowered.proxy.protocol.packet.Handshake");
            HANDSHAKE_ADDRESS_FIELD = HANDSHAKE.getDeclaredField("serverAddress");
            HANDSHAKE_ADDRESS_FIELD.setAccessible(true);
            MINECRAFTCONNECTION = Class.forName("com.velocitypowered.proxy.connection.MinecraftConnection");
            MC_CONNECTION_REMOTE_ADDRESS = MINECRAFTCONNECTION.getDeclaredField("remoteAddress");
            MC_CONNECTION_REMOTE_ADDRESS.setAccessible(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Logger logger;

    public ProxyPipeHandler(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (!msg.getClass().isAssignableFrom(HANDSHAKE)) {
                return;
            }

            String rawHostname = (String) HANDSHAKE_ADDRESS_FIELD.get(msg);
            ProxyPipeInfo info = validateHandshake(ctx, rawHostname);

            if (info == null) {
                ctx.close();
                return;
            }

            // Set the MinecraftConnection's remote address.
            Object mcConn = ctx.pipeline().get("handler");
            MC_CONNECTION_REMOTE_ADDRESS.set(mcConn, info.userIp);

            // Rewrite the handshake so that Velocity is none the wiser.
            HANDSHAKE_ADDRESS_FIELD.set(msg, info.correctHostname);
        } catch (Exception e) {
            logger.error("Unable to handle handshake from {}", ctx.channel().remoteAddress(), e);
            ctx.close();
        } finally {
            ctx.fireChannelRead(msg);
        }
    }

    private ProxyPipeInfo validateHandshake(ChannelHandlerContext ctx, String rawHostname) throws Exception {
        String[] split = rawHostname.split("\u0000");
        if (split.length < 6) {
            logger.warn("Unauthorized client {} attempted to connect (no appended data found)", ctx.channel().remoteAddress());
            return null;
        }

        // The split data from ProxyPipe consists of:
        //
        // 0: The timestamp, as a seconds since the Unix epoch.
        // 1: ProxyPipe's remote IP address.
        // 2: The user's real remote IP address.
        // 3: The real hostname.
        // 4: The ECDSA "r" curve
        // 5: The ECDSA "s" curve
        //
        // The first four will be validated against an ECDSA signature, which ProxyPipe sends as a encoded big integers.

        // First, make sure that this request isn't too old.
        long timestamp = Long.parseLong(split[0]);
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime - timestamp >= 3600) { // 60 minutes, 1 hour
            logger.warn("Client {} attempted to connect with old timestamp (%sms)", ctx.channel().remoteAddress(),
                    currentTime - timestamp - 3600);
            return null;
        }

        // Validate the ECDSA signature of the remaining components.
        byte[] verify = String.join("\0", Arrays.asList(split).subList(0, 4)).getBytes(StandardCharsets.UTF_8);
        byte[] rCurve = Base64.getDecoder().decode(split[4]);
        byte[] sCurve = Base64.getDecoder().decode(split[5]);

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(PROXYPIPE_PUBLIC_KEY);
        signature.update(verify);
        if (!signature.verify(encodeSignature(new BigInteger(1, rCurve), new BigInteger(1, sCurve)))) {
            logger.warn("Client {} attempted to connect with invalid ECDSA signature!", ctx.channel().remoteAddress());
            return null;
        }

        // Everything is in order. Let's return the info.
        InetAddress userIp = InetAddresses.forString(split[2]);
        String restoredHostname = split[3].replace("\n", "\0");
        return new ProxyPipeInfo(new InetSocketAddress(userIp, 25565), restoredHostname);
    }

    private static byte[] encodeSignature(BigInteger r, BigInteger s) throws IOException {
        ASN1EncodableVector vector = new ASN1EncodableVector();
        vector.add(new ASN1Integer(r));
        vector.add(new ASN1Integer(s));
        return new DERSequence(vector).getEncoded("DER");
    }

    private static class ProxyPipeInfo {
        private final InetSocketAddress userIp;
        private final String correctHostname;

        private ProxyPipeInfo(InetSocketAddress userIp, String correctHostname) {
            this.userIp = userIp;
            this.correctHostname = correctHostname;
        }
    }
}
