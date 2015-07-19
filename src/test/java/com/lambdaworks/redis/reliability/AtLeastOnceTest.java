package com.lambdaworks.redis.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.lambdaworks.redis.RedisAsyncConnection;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.lambdaworks.redis.AbstractCommandTest;
import com.lambdaworks.redis.ClientOptions;
import com.lambdaworks.redis.RedisChannelHandler;
import com.lambdaworks.redis.RedisChannelWriter;
import com.lambdaworks.redis.RedisCommandTimeoutException;
import com.lambdaworks.redis.RedisConnection;
import com.lambdaworks.redis.RedisException;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.output.IntegerOutput;
import com.lambdaworks.redis.output.StatusOutput;
import com.lambdaworks.redis.protocol.Command;
import com.lambdaworks.redis.protocol.CommandArgs;
import com.lambdaworks.redis.protocol.CommandOutput;
import com.lambdaworks.redis.protocol.CommandType;
import com.lambdaworks.redis.protocol.ConnectionWatchdog;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 * @since 03.07.15 13:40
 */
public class AtLeastOnceTest extends AbstractCommandTest {

    protected final Utf8StringCodec CODEC = new Utf8StringCodec();
    protected String key = "key";

    @Before
    public void before() throws Exception {
        client.setOptions(new ClientOptions.Builder().autoReconnect(true).build());

        // needs to be increased on slow systems...perhaps...
        client.setDefaultTimeout(3, TimeUnit.SECONDS);

        RedisConnection<String, String> connection = client.connect();
        connection.flushall();
        connection.flushdb();
        connection.close();
    }

    @Test
    public void connectionIsConnectedAfterConnect() throws Exception {

        RedisConnection<String, String> connection = client.connect();

        assertThat(getConnectionState(getRedisChannelHandler(connection)));

        connection.close();
    }

    @Test
    public void reconnectIsActiveHandler() throws Exception {

        RedisConnection<String, String> connection = client.connect();

        ConnectionWatchdog connectionWatchdog = getHandler(ConnectionWatchdog.class, getRedisChannelHandler(connection));
        assertThat(connectionWatchdog).isNotNull();
        assertThat(connectionWatchdog.isListenOnChannelInactive()).isTrue();
        assertThat(connectionWatchdog.isReconnectSuspended()).isFalse();

        connection.close();
    }

    @Test
    public void basicOperations() throws Exception {

        RedisConnection<String, String> connection = client.connect();

        connection.set(key, "1");
        assertThat(connection.get("key")).isEqualTo("1");

        connection.close();
    }

    @Test
    public void noBufferedCommandsAfterExecute() throws Exception {

        RedisConnection<String, String> connection = client.connect();

        connection.set(key, "1");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
    }

    @Test
    public void commandIsExecutedOnce() throws Exception {

        RedisConnection<String, String> connection = client.connect();

        connection.set(key, "1");
        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("2");

        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("3");

        connection.incr(key);
        assertThat(connection.get(key)).isEqualTo("4");

        connection.close();
    }

    @Test
    public void commandFailsWhenFailOnEncode() throws Exception {

        RedisConnection<String, String> connection = client.connect();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();
        RedisConnection<String, String> verificationConnection = client.connect();

        connection.set(key, "1");
        Command<String, String, String> working = new Command<String, String, String>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<String, String>(CODEC).addKey(key));
        channelWriter.write(working);
        assertThat(working.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(connection.get(key)).isEqualTo("2");

        Command<String, String, Object> command = new Command<String, String, Object>(CommandType.INCR,
                new IntegerOutput(CODEC), new CommandArgs<String, String>(CODEC).addKey(key)) {

            @Override
            public void encode(ByteBuf buf) {
                throw new IllegalStateException("I want to break free");
            }
        };

        channelWriter.write(command);

        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();

        assertThat(verificationConnection.get(key)).isEqualTo("2");

        assertThat(getQueue(getRedisChannelHandler(connection))).isNotEmpty();

        connection.close();
    }

    @Test
    public void commandNotFailedChannelClosesWhileFlush() throws Exception {

        RedisConnection<String, String> connection = client.connect();
        RedisConnection<String, String> verificationConnection = client.connect();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();

        connection.set(key, "1");
        assertThat(verificationConnection.get(key)).isEqualTo("1");

        final CountDownLatch block = new CountDownLatch(1);

        ConnectionWatchdog connectionWatchdog = getHandler(ConnectionWatchdog.class, getRedisChannelHandler(connection));

        Command<String, String, Object> command = getBlockOnEncodeCommand(block);

        channelWriter.write(command);

        connectionWatchdog.setReconnectSuspended(true);

        Channel channel = getChannel(getRedisChannelHandler(connection));
        channel.unsafe().disconnect(channel.newPromise());

        assertThat(channel.isOpen()).isFalse();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();
        block.countDown();
        assertThat(command.await(2, TimeUnit.SECONDS)).isFalse();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();

        assertThat(verificationConnection.get(key)).isEqualTo("1");

        assertThat(getQueue(getRedisChannelHandler(connection))).isNotEmpty().contains(command);
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
    }

    @Test
    public void commandRetriedChannelClosesWhileFlush() throws Exception {

        RedisConnection<String, String> connection = client.connect();
        RedisConnection<String, String> verificationConnection = client.connect();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();

        connection.set(key, "1");
        assertThat(verificationConnection.get(key)).isEqualTo("1");

        final CountDownLatch block = new CountDownLatch(1);

        ConnectionWatchdog connectionWatchdog = getHandler(ConnectionWatchdog.class, getRedisChannelHandler(connection));

        Command<String, String, Object> command = getBlockOnEncodeCommand(block);

        channelWriter.write(command);

        connectionWatchdog.setReconnectSuspended(true);

        Channel channel = getChannel(getRedisChannelHandler(connection));
        channel.unsafe().disconnect(channel.newPromise());

        assertThat(channel.isOpen()).isFalse();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isFalse();
        block.countDown();
        assertThat(command.await(2, TimeUnit.SECONDS)).isFalse();

        connectionWatchdog.scheduleReconnect();

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isFalse();
        assertThat(command.isDone()).isTrue();

        assertThat(verificationConnection.get(key)).isEqualTo("2");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).isEmpty();

        connection.close();
        verificationConnection.close();
    }

    protected Command<String, String, Object> getBlockOnEncodeCommand(final CountDownLatch block) {
        return new Command<String, String, Object>(CommandType.INCR, new IntegerOutput(CODEC), new CommandArgs<String, String>(
                CODEC).addKey(key)) {

            @Override
            public void encode(ByteBuf buf) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                }
                super.encode(buf);
            }
        };
    }

    @Test
    public void commandFailsDuringDecode() throws Exception {

        RedisConnection<String, String> connection = client.connect();
        RedisChannelWriter<String, String> channelWriter = getRedisChannelHandler(connection).getChannelWriter();
        RedisConnection<String, String> verificationConnection = client.connect();

        connection.set(key, "1");

        Command<String, String, String> command = new Command<String, String, String>(CommandType.INCR,
                new StatusOutput<String, String>(CODEC), new CommandArgs<String, String>(CODEC).addKey(key));

        channelWriter.write(command);

        assertThat(command.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(command.isCancelled()).isTrue();
        assertThat(command.isDone()).isTrue();
        assertThat(command.getException()).isInstanceOf(IllegalStateException.class);

        assertThat(verificationConnection.get(key)).isEqualTo("2");
        assertThat(connection.get(key)).isEqualTo("2");

        connection.close();
        verificationConnection.close();
    }

    @Test
    public void commandCancelledOverSyncAPIAfterConnectionIsDisconnected() throws Exception {

        RedisConnection<String, String> connection = client.connect();
        RedisConnection<String, String> verificationConnection = client.connect();

        connection.set(key, "1");

        ConnectionWatchdog connectionWatchdog = getHandler(ConnectionWatchdog.class, getRedisChannelHandler(connection));
        connectionWatchdog.setListenOnChannelInactive(false);

        connection.quit();
        while (connection.isOpen()) {
            Thread.sleep(100);
        }

        try {
            connection.incr(key);
        } catch (RedisException e) {
            assertThat(e).isExactlyInstanceOf(RedisCommandTimeoutException.class);
        }

        assertThat(verificationConnection.get("key")).isEqualTo("1");

        assertThat(getQueue(getRedisChannelHandler(connection))).isEmpty();
        assertThat(getCommandBuffer(getRedisChannelHandler(connection))).hasSize(1);

        connectionWatchdog.setListenOnChannelInactive(true);
        connectionWatchdog.scheduleReconnect();

        while (!getCommandBuffer(getRedisChannelHandler(connection)).isEmpty()
                || !getQueue(getRedisChannelHandler(connection)).isEmpty()) {
            Thread.sleep(10);
        }

        assertThat(connection.get(key)).isEqualTo("1");

        connection.close();
        verificationConnection.close();
    }

    @Test
    public void retryAfterConnectionIsDisconnected() throws Exception {

        RedisAsyncConnection<String, String> connection = client.connectAsync();
        RedisChannelHandler<String, String> redisChannelHandler = (RedisChannelHandler) connection;
        RedisConnection<String, String> verificationConnection = client.connect();

        connection.set(key, "1").get();

        ConnectionWatchdog connectionWatchdog = getHandler(ConnectionWatchdog.class, redisChannelHandler);
        connectionWatchdog.setListenOnChannelInactive(false);

        connection.quit();
        while (connection.isOpen()) {
            Thread.sleep(100);
        }

        assertThat(connection.incr(key).await(1, TimeUnit.SECONDS)).isFalse();

        assertThat(verificationConnection.get("key")).isEqualTo("1");

        assertThat(getQueue((RedisChannelHandler) connection)).isEmpty();
        assertThat(getCommandBuffer((RedisChannelHandler) connection).size()).isGreaterThan(0);

        connectionWatchdog.setListenOnChannelInactive(true);
        connectionWatchdog.scheduleReconnect();

        while (!getCommandBuffer(redisChannelHandler).isEmpty() || !getQueue(redisChannelHandler).isEmpty()) {
            Thread.sleep(10);
        }

        assertThat(connection.get(key).get()).isEqualTo("2");
        assertThat(verificationConnection.get(key)).isEqualTo("2");

        connection.close();
        verificationConnection.close();
    }

    private <K, V> RedisChannelHandler<K, V> getRedisChannelHandler(RedisConnection<K, V> sync) {

        InvocationHandler invocationHandler = Proxy.getInvocationHandler(sync);
        return (RedisChannelHandler<K, V>) ReflectionTestUtils.getField(invocationHandler, "connection");
    }

    private <T> T getHandler(Class<T> handlerType, RedisChannelHandler<?, ?> channelHandler) {
        Channel channel = getChannel(channelHandler);
        return (T) channel.pipeline().get((Class) handlerType);
    }

    private Channel getChannel(RedisChannelHandler<?, ?> channelHandler) {
        return (Channel) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "channel");
    }

    private Queue<Object> getQueue(RedisChannelHandler<?, ?> channelHandler) {
        return (Queue<Object>) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "queue");
    }

    private Queue<Object> getCommandBuffer(RedisChannelHandler<?, ?> channelHandler) {
        return (Queue<Object>) ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "commandBuffer");
    }

    private String getConnectionState(RedisChannelHandler<?, ?> channelHandler) {
        return ReflectionTestUtils.getField(channelHandler.getChannelWriter(), "lifecycleState").toString();
    }
}
