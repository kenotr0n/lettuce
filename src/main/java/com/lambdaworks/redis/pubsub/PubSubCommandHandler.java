// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.pubsub;

import java.util.Queue;

import com.lambdaworks.redis.ClientOptions;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.protocol.CommandHandler;
import com.lambdaworks.redis.protocol.CommandOutput;
import com.lambdaworks.redis.protocol.RedisCommand;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * A netty {@link ChannelHandler} responsible for writing redis pub/sub commands and reading the response stream from the
 * server.
 * 
 * @param <K> Key type.
 * @param <V> Value type.
 * 
 * @author Will Glozer
 */
public class PubSubCommandHandler<K, V> extends CommandHandler<K, V> {
    private RedisCodec<K, V> codec;
    private PubSubOutput<K, V, V> output;

    /**
     * Initialize a new instance.
     * 
     * @param queue Command queue.
     * @param codec Codec.
     */
    public PubSubCommandHandler(ClientOptions clientOptions, Queue<RedisCommand<K, V, ?>> queue, RedisCodec<K, V> codec) {
        super(clientOptions, queue);
        this.codec = codec;
        this.output = new PubSubOutput<K, V, V>(codec);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer) throws InterruptedException {
        while (output.type() == null && !queue.isEmpty()) {
            CommandOutput<K, V, ?> currentOutput = queue.peek().getOutput();
            if (!rsm.decode(buffer, currentOutput)) {
                return;
            }
            queue.poll().complete();
            buffer.discardReadBytes();
            if (currentOutput instanceof PubSubOutput) {
                ctx.fireChannelRead(currentOutput);
            }
        }

        while (rsm.decode(buffer, output)) {
            ctx.fireChannelRead(output);
            output = new PubSubOutput<K, V, V>(codec);
            buffer.discardReadBytes();
        }
    }

}
