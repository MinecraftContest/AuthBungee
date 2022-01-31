package ru.leymooo.botfilter;

import com.google.common.hash.Hashing;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.compress.PacketDecompressor;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.Chat;
import net.md_5.bungee.protocol.packet.ClientSettings;
import net.md_5.bungee.protocol.packet.KeepAlive;
import net.md_5.bungee.protocol.packet.PluginMessage;
import ru.leymooo.botfilter.BotFilter.CheckState;
import ru.leymooo.botfilter.caching.CachedCaptcha.CaptchaHolder;
import ru.leymooo.botfilter.caching.PacketUtils;
import ru.leymooo.botfilter.caching.PacketUtils.KickType;
import ru.leymooo.botfilter.caching.PacketsPosition;
import ru.leymooo.botfilter.config.Settings;
import ru.leymooo.botfilter.utils.FailedUtils;
import ru.leymooo.botfilter.utils.IPUtils;
import ru.leymooo.botfilter.utils.ManyChecksUtils;

import static ru.leymooo.botfilter.caching.PacketUtils.getCachedPacket;

/**
 * @author Leymooo
 */
@EqualsAndHashCode(callSuper = false, of =
    {
    "name"
    })
public class Connector extends MoveHandler
{

    private static final Logger LOGGER = BungeeCord.getInstance().getLogger();
    private static final int MAX_PLUGIN_MESSAGES_BYTES = 160000; //160 KB

    public static int TOTAL_TICKS = 100;
    private static long TOTAL_TIME = ( TOTAL_TICKS * 50 ) - 100; //TICKS * 50MS

    private final BotFilter botFilter;
    private final String name;
    private final String ip;
    @Getter
    private final int version;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    @Getter
    private UserConnection userConnection;
    @Getter
    @Setter
    private CheckState state = CheckState.CAPTCHA_ON_POSITION_FAILED;
    @Getter
    private Channel channel;
    private String captchaAnswer;
    private int aticks = 0, sentPings = 0, attemps = 3;
    @Getter
    private long joinTime = System.currentTimeMillis();
    private long lastSend = 0, totalping = 9999;
    private boolean markDisconnected = false;
    private int pluginMessagesBytes = 0;

    public Connector(UserConnection userConnection, BotFilter botFilter)
    {
        this.botFilter = botFilter;
        this.state = this.botFilter.getCurrentCheckState();
        this.name = userConnection.getName();
        this.channel = userConnection.getCh().getHandle();
        this.userConnection = userConnection;
        this.version = userConnection.getPendingConnection().getVersion();
        this.userConnection.setClientEntityId( PacketUtils.CLIENTID );
        this.userConnection.setDimension( 0 );
        this.ip = IPUtils.getAddress( this.userConnection ).getHostAddress();

        if ( Settings.IMP.PROTECTION.SKIP_GEYSER && botFilter.isGeyser( userConnection.getPendingConnection() ) )
        {
            this.state = CheckState.ONLY_CAPTCHA;
        }
    }


    public void spawn()
    {
//        this.botFilter.incrementBotCounter();
//        if ( !Settings.IMP.PROTECTION.ALWAYS_CHECK )
//        {
//            ManyChecksUtils.IncreaseOrAdd( IPUtils.getAddress( this.userConnection ) );
//        }
//        if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED )
//        {
//            PacketUtils.spawnPlayer( channel, userConnection.getPendingConnection().getVersion(), false, false );
//            PacketUtils.titles[0].writeTitle( channel, version );
//        } else
//        {
//            PacketUtils.spawnPlayer( channel, userConnection.getPendingConnection().getVersion(), state == CheckState.ONLY_CAPTCHA, true );
//            sendCaptcha();
//            PacketUtils.titles[1].writeTitle( channel, version );
//        }
        PacketUtils.spawnPlayer( channel, userConnection.getPendingConnection().getVersion(), true, false );
        sendPing();
        LOGGER.log( Level.INFO, toString() + " has connected" );
        PacketUtils.titles[1].writeTitle( channel, version );
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        markDisconnected = true;
        if ( state == CheckState.FAILED )
        {
            channel.close();
        } else
        {
            this.userConnection.disconnect( Util.exception( t ) );
        }
        disconnected();
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception
    {
        //There are no unknown packets which player will send and will be longer than 2048 bytes during check
        if ( packet.packet == null && packet.buf.readableBytes() > 2048 )
        {
            failed( KickType.BIG_PACKET, "Sent packet larger than 2048 bytes (" + packet.buf.readableBytes() + ")" );
        }
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        switch ( state )
        {
            case ONLY_CAPTCHA:
            case ONLY_POSITION:
            case CAPTCHA_POSITION:
                String info = "(BF) [" + name + "|" + ip + "] left from server during check";
                LOGGER.log( Level.INFO, info );
                FailedUtils.addIpToQueue( ip, KickType.LEAVED );
                break;
        }
        botFilter.removeConnection( null, this );
        disconnected();
    }

    @Override
    public void handlerChanged()
    {
        disconnected();
    }

    private void disconnected()
    {
        channel = null;
        userConnection = null;
    }

    public void completeCheck(String message)
    {
        String[] parts = message.trim().split(" ");
        if (parts.length != 2) {
            failed(KickType.FAILED_CAPTCHA, "Invalid login message format");
        }

        String login = parts[0];
        String password = parts[1];

        String passwordHash = Hashing.sha256()
                .hashString(password, StandardCharsets.UTF_8)
                .toString();

        LOGGER.info("Login: " + login + "; Pwd hash: " + passwordHash);

        String auth_query = "SELECT * FROM mc_users WHERE id=? AND password_hash=?";

        int parsedId = Integer.parseInt(login);

        try (Connection con = DataSource.getConnection();
             PreparedStatement pst = con.prepareStatement(auth_query)) {

            pst.setInt(1, parsedId);
            pst.setString(2, passwordHash);

            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    LOGGER.info("Success");
                } else {
                    failed(KickType.FAILED_CAPTCHA, "Invalid credentials");
                    return;
                }
            }
        } catch (SQLException e) {
            failed(KickType.FAILED_CAPTCHA, "Auth process exception");
            e.printStackTrace();
        }
        userConnection.setDisplayName("user" + String.valueOf(parsedId));

        state = CheckState.SUCCESSFULLY;
        PacketUtils.titles[2].writeTitle( channel, version );
        channel.flush();
        botFilter.removeConnection( null, this );
        sendMessage( PacketsPosition.CHECK_SUS );
        botFilter.saveUser( getName(), IPUtils.getAddress( userConnection ), true );
        PacketDecompressor packetDecompressor = channel.pipeline().get( PacketDecompressor.class );
        if ( packetDecompressor != null )
        {
            packetDecompressor.checking = false;
        }
        userConnection.setNeedLogin( false );
        userConnection.getPendingConnection().finishLogin( userConnection, true );
        markDisconnected = true;
        LOGGER.log( Level.INFO, "[BotFilter] Игрок (" + name + "|" + ip + ") успешно прошёл проверку" );
    }

    @Override
    public void onMove()
    {
//        if ( lastY == -1 || state == CheckState.FAILED || state == CheckState.SUCCESSFULLY || onGround )
//        {
//            return;
//        }
//        if ( state == CheckState.ONLY_CAPTCHA )
//        {
//            if ( lastY != y && waitingTeleportId == -1 )
//            {
//                resetPosition( true );
//            }
//            return;
//        }
//        // System.out.println( "lastY=" + lastY + "; y=" + y + "; diff=" + formatDouble( lastY - y ) + "; need=" + getSpeed( ticks ) +"; ticks=" + ticks );
//        if ( formatDouble( lastY - y ) != getSpeed( ticks ) )
//        {
//            if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED )
//            {
//                changeStateToCaptcha();
//            } else
//            {
//                failed( KickType.FAILED_FALLING, "Failed position check" );
//            }
//            return;
//        }
//        if ( y <= 60 && state == CheckState.CAPTCHA_POSITION && waitingTeleportId == -1 )
//        {
//            resetPosition( false );
//        }
//        if ( aticks >= TOTAL_TICKS && state != CheckState.CAPTCHA_POSITION )
//        {
//            completeCheck();
//            return;
//        }
//        if ( state == CheckState.CAPTCHA_ON_POSITION_FAILED || state == CheckState.ONLY_POSITION )
//        {
//            ByteBuf expBuf = PacketUtils.expPackets.get( aticks, version );
//            if ( expBuf != null )
//            {
//                channel.writeAndFlush( expBuf, channel.voidPromise() );
//            }
//        }
//        ticks++;
//        aticks++;
    }

    private void resetPosition(boolean disableFall)
    {
        if ( disableFall )
        {
            channel.write( getCachedPacket( PacketsPosition.PLAYERABILITIES ).get( version ), channel.voidPromise() );
        }
        waitingTeleportId = 9876;
        channel.writeAndFlush( getCachedPacket( PacketsPosition.PLAYERPOSANDLOOK_CAPTCHA ).get( version ), channel.voidPromise() );
    }

    @Override
    public void handle(Chat chat) throws Exception
    {
        completeCheck(chat.getMessage());
    }

    @Override
    public void handle(ClientSettings settings) throws Exception
    {
        this.userConnection.setSettings( settings );
        this.userConnection.setCallSettingsEvent( true );
    }

    @Override
    public void handle(KeepAlive keepAlive) throws Exception
    {
        if ( keepAlive.getRandomId() == PacketUtils.KEEPALIVE_ID )
        {
            if ( lastSend == 0 )
            {
                failed( KickType.PING, "Tried send fake ping" );
                return;
            }
            long ping = System.currentTimeMillis() - lastSend;
            totalping = totalping == 9999 ? ping : totalping + ping;
            lastSend = 0;
        }
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception
    {

        pluginMessagesBytes += ( pluginMessage.getTag().length() * 4 );
        pluginMessagesBytes += ( pluginMessage.getData().length );

        if ( pluginMessagesBytes > MAX_PLUGIN_MESSAGES_BYTES )
        {
            failed( KickType.BIG_PACKET, "Bad PluginMessage's" );
            return;
        }

        if ( !userConnection.getPendingConnection().relayMessage0( pluginMessage ) )
        {
            userConnection.addDelayedPluginMessage( pluginMessage );
        }

    }

    public void sendPing()
    {
        if ( this.lastSend == 0 && !( state == CheckState.FAILED || state == CheckState.SUCCESSFULLY ) )
        {
            lastSend = System.currentTimeMillis();
            sentPings++;
            channel.writeAndFlush( getCachedPacket( PacketsPosition.KEEPALIVE ).get( version ) );
        }
    }

    private void sendCaptcha()
    {
        CaptchaHolder captchaHolder = PacketUtils.captchas.randomCaptcha();
        captchaAnswer = captchaHolder.getAnswer();
        channel.write( getCachedPacket( PacketsPosition.SETSLOT_MAP ).get( version ), channel.voidPromise() );
        captchaHolder.write( channel, version, true );
    }

    private void changeStateToCaptcha()
    {
        state = CheckState.ONLY_CAPTCHA;
        joinTime = System.currentTimeMillis() + 3500;
        channel.write( getCachedPacket( PacketsPosition.SETEXP_RESET ).get( version ), channel.voidPromise() );
        PacketUtils.titles[1].writeTitle( channel, version );
        resetPosition( true );
        sendCaptcha();
    }

    public String getName()
    {
        return name.toLowerCase();
    }

    public boolean isConnected()
    {
        return userConnection != null && channel != null && !markDisconnected && userConnection.isConnected();
    }

    public void failed(KickType type, String kickMessage)
    {
        state = CheckState.FAILED;
        PacketUtils.kickPlayer( type, Protocol.GAME, userConnection.getCh(), version );
        markDisconnected = true;
        LOGGER.log( Level.INFO, "(BF) [" + name + "|" + ip + "] check failed: " + kickMessage );
        if ( type != KickType.BIG_PACKET )
        {
            FailedUtils.addIpToQueue( ip, type );
        }
    }

    public void sendMessage(int index)
    {
        ByteBuf buf = getCachedPacket( index ).get( getVersion() );
        if ( buf != null )
        {
            getChannel().write( buf, getChannel().voidPromise() );
        }
    }


    @Override
    public String toString()
    {
        return "[" + name + "|" + ip + "] <-> BotFilter";
    }
}
