// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis;

import static com.google.code.tempusfugit.temporal.Duration.seconds;
import static com.google.code.tempusfugit.temporal.Timeout.timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.code.tempusfugit.temporal.Condition;
import com.google.code.tempusfugit.temporal.WaitFor;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.lambdaworks.redis.pubsub.RedisPubSubAdapter;
import com.lambdaworks.redis.pubsub.RedisPubSubConnection;
import com.lambdaworks.redis.pubsub.RedisPubSubListener;

public class PubSubCommandTest extends AbstractCommandTest implements RedisPubSubListener<String, String> {
    private RedisPubSubConnection<String, String> pubsub;

    private BlockingQueue<String> channels;
    private BlockingQueue<String> patterns;
    private BlockingQueue<String> messages;
    private BlockingQueue<Long> counts;

    private String channel = "channel0";
    private String pattern = "channel*";
    private String message = "msg!";

    @Before
    public void openPubSubConnection() throws Exception {
        pubsub = client.connectPubSub();
        pubsub.addListener(this);
        channels = new LinkedBlockingQueue<String>();
        patterns = new LinkedBlockingQueue<String>();
        messages = new LinkedBlockingQueue<String>();
        counts = new LinkedBlockingQueue<Long>();
    }

    @After
    public void closePubSubConnection() throws Exception {
        pubsub.close();
    }

    @Test
    public void auth() throws Exception {
        new WithPasswordRequired() {
            @Override
            protected void run(RedisClient client) throws Exception {
                RedisPubSubConnection<String, String> connection = client.connectPubSub();
                connection.addListener(PubSubCommandTest.this);
                connection.auth(passwd);

                connection.subscribe(channel);
                assertThat(channels.take()).isEqualTo(channel);
            }
        };
    }

    @Test
    public void authWithReconnect() throws Exception {
        new WithPasswordRequired() {
            @Override
            protected void run(RedisClient client) throws Exception {
                RedisPubSubConnection<String, String> connection = client.connectPubSub();
                connection.addListener(PubSubCommandTest.this);
                connection.auth(passwd);
                connection.quit().get();

                connection.subscribe(channel);
                assertThat(channels.take()).isEqualTo(channel);
            }
        };
    }

    @Test(timeout = 2000)
    public void message() throws Exception {
        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void pipelinedMessage() throws Exception {
        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);
        RedisAsyncConnection<String, String> connection = client.connectAsync();

        connection.setAutoFlushCommands(false);
        connection.publish(channel, message);
        Thread.sleep(100);

        assertThat(channels).isEmpty();
        connection.flushCommands();

        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        connection.close();
    }

    @Test(timeout = 2000)
    public void pmessage() throws Exception {
        pubsub.psubscribe(pattern).await(1, TimeUnit.MINUTES);
        assertThat(patterns.take()).isEqualTo(pattern);

        redis.publish(channel, message);
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        redis.publish("channel2", "msg 2!");
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo("channel2");
        assertThat(messages.take()).isEqualTo("msg 2!");
    }

    @Test(timeout = 2000)
    public void pipelinedSubscribe() throws Exception {

        pubsub.setAutoFlushCommands(false);
        pubsub.subscribe(channel);
        Thread.sleep(100);
        assertThat(channels).isEmpty();
        pubsub.flushCommands();

        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);

        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

    }

    @Test(timeout = 2000)
    public void psubscribe() throws Exception {
        RedisFuture<Void> psubscribe = pubsub.psubscribe(pattern);
        assertThat(psubscribe.get()).isNull();
        assertThat(psubscribe.getError()).isNull();
        assertThat(psubscribe.isCancelled()).isFalse();
        assertThat(psubscribe.isDone()).isTrue();

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);
    }

    @Test(timeout = 2000)
    public void psubscribeWithListener() throws Exception {
        RedisFuture<Void> psubscribe = pubsub.psubscribe(pattern);
        final List<Object> listener = Lists.newArrayList();
        psubscribe.addListener(new Runnable() {
            @Override
            public void run() {
                listener.add("done");
            }
        }, MoreExecutors.sameThreadExecutor());
        psubscribe.await(1, TimeUnit.MINUTES);

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);
        assertThat(listener).hasSize(1);
    }

    @Test
    public void pubsubEmptyChannels() throws Exception {
        RedisFuture<Void> future = pubsub.subscribe();
        future.get();
        assertThat(future.getError()).isEqualTo("ERR wrong number of arguments for 'subscribe' command");

    }

    @Test
    public void pubsubChannels() throws Exception {
        RedisFuture<Void> future = pubsub.subscribe(channel);
        future.get(1, TimeUnit.MINUTES);
        List<String> result = redis.pubsubChannels();
        assertThat(result).contains(channel);

    }

    @Test
    public void pubsubMultipleChannels() throws Exception {
        RedisFuture<Void> future = pubsub.subscribe(channel, "channel1", "channel3");
        future.get();

        List<String> result = redis.pubsubChannels();
        assertThat(result).contains(channel, "channel1", "channel3");

    }

    @Test
    public void pubsubChannelsWithArg() throws Exception {
        pubsub.subscribe(channel).get();
        List<String> result = redis.pubsubChannels(pattern);
        assertThat(result, hasItem(channel));
    }

    @Test
    public void pubsubNumsub() throws Exception {

        pubsub.subscribe(channel);
        Thread.sleep(100);

        Map<String, Long> result = redis.pubsubNumsub(channel);
        assertThat(result).hasSize(1);
        assertThat(result.get(channel)).isEqualTo(1L);
    }

    @Test
    public void pubsubNumpat() throws Exception {

        pubsub.psubscribe(pattern).get();
        Long result = redis.pubsubNumpat();
        assertThat(result.longValue()).isEqualTo(1L);
    }

    @Test
    public void punsubscribe() throws Exception {
        pubsub.punsubscribe(pattern).get();
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(0);

    }

    @Test(timeout = 2000)
    public void subscribe() throws Exception {
        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);
    }

    @Test(timeout = 2000)
    public void unsubscribe() throws Exception {
        pubsub.unsubscribe(channel).get();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(0);

        RedisFuture<Void> future = pubsub.unsubscribe();

        assertThat(future.get()).isNull();
        assertThat(future.getError()).isNull();

        assertThat(channels).isEmpty();
        assertThat(patterns).isEmpty();

    }

    @Test
    public void pubsubCloseOnClientShutdown() throws Exception {

        RedisClient redisClient = new RedisClient(host, port);

        RedisPubSubConnection<String, String> connection = redisClient.connectPubSub();

        FastShutdown.shutdown(redisClient);

        assertThat(connection.isOpen()).isFalse();
    }

    @Test(timeout = 2000)
    public void utf8Channel() throws Exception {
        String channel = "channelλ";
        String message = "αβγ";

        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribeChannelsOnReconnect() throws Exception {
        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        pubsub.quit();

        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                return pubsub.isOpen();
            }
        }, timeout(seconds(5)));

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribePatternsOnReconnect() throws Exception {
        pubsub.psubscribe(pattern);
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        pubsub.quit();

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        WaitFor.waitOrTimeout(new Condition() {
            @Override
            public boolean isSatisfied() {
                return pubsub.isOpen();
            }
        }, timeout(seconds(5)));

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void adapter() throws Exception {
        final BlockingQueue<Long> localCounts = new LinkedBlockingQueue<Long>();

        RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<String, String>() {
            @Override
            public void subscribed(String channel, long count) {
                super.subscribed(channel, count);
                localCounts.add(count);
            }

            @Override
            public void unsubscribed(String channel, long count) {
                super.unsubscribed(channel, count);
                localCounts.add(count);
            }
        };

        pubsub.addListener(adapter);
        pubsub.subscribe(channel);
        pubsub.psubscribe(pattern);

        assertThat((long) localCounts.take()).isEqualTo(1L);

        redis.publish(channel, message);
        pubsub.punsubscribe(pattern);
        pubsub.unsubscribe(channel);

        assertThat((long) localCounts.take()).isEqualTo(0L);
    }

    @Test(timeout = 2000)
    public void removeListener() throws Exception {
        pubsub.subscribe(channel);
        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        pubsub.removeListener(this);

        redis.publish(channel, message);
        assertThat(channels.poll(10, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messages.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    // RedisPubSubListener implementation

    @Override
    public void message(String channel, String message) {
        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void message(String pattern, String channel, String message) {
        patterns.add(pattern);
        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void subscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void psubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }

    @Override
    public void unsubscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void punsubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }
}
