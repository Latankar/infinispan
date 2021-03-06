package org.infinispan.client.hotrod.counter.operation;

import java.net.SocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.impl.operations.RetryOnFailureOperation;
import org.infinispan.client.hotrod.impl.protocol.Codec;
import org.infinispan.client.hotrod.impl.protocol.HeaderParams;
import org.infinispan.client.hotrod.impl.protocol.HotRodConstants;
import org.infinispan.client.hotrod.impl.transport.netty.ByteBufUtil;
import org.infinispan.client.hotrod.impl.transport.netty.ChannelFactory;
import org.infinispan.client.hotrod.logging.LogFactory;
import org.infinispan.commons.logging.Log;
import org.infinispan.commons.util.Util;
import org.infinispan.counter.exception.CounterException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * A base operation class for the counter's operation.
 *
 * @author Pedro Ruivo
 * @since 9.2
 */
abstract class BaseCounterOperation<T> extends RetryOnFailureOperation<T> {

   private static final Log commonsLog = LogFactory.getLog(BaseCounterOperation.class, Log.class);
   private static final byte[] EMPTY_CACHE_NAME = Util.EMPTY_BYTE_ARRAY;
   private static final byte[] COUNTER_CACHE_NAME = RemoteCacheManager.cacheNameBytes("org.infinispan.counter");
   private final String counterName;

   BaseCounterOperation(short requestCode, short responseCode, Codec codec, ChannelFactory channelFactory, AtomicInteger topologyId, Configuration cfg,
                        String counterName) {
      super(requestCode, responseCode, codec, channelFactory, EMPTY_CACHE_NAME, topologyId, 0, cfg, null);
      this.counterName = counterName;
   }

   /**
    * Writes the operation header followed by the counter's name.
    *
    * @return the {@link HeaderParams}.
    */
   void sendHeaderAndCounterNameAndRead(Channel channel, short opCode) {
      ByteBuf buf = getHeaderAndCounterNameBufferAndRead(channel, 0);
      channel.writeAndFlush(buf);
   }

   ByteBuf getHeaderAndCounterNameBufferAndRead(Channel channel, int extraBytes) {
      scheduleRead(channel);

      // counterName should never be null/empty
      byte[] counterBytes = counterName.getBytes(HotRodConstants.HOTROD_STRING_CHARSET);
      ByteBuf buf = channel.alloc().buffer(codec.estimateHeaderSize(header) + ByteBufUtil.estimateArraySize(counterBytes) + extraBytes);
      codec.writeHeader(buf, header);
      ByteBufUtil.writeString(buf, counterName);

      setCacheName();
      return buf;
   }

   /**
    * If the status is {@link #KEY_DOES_NOT_EXIST_STATUS}, the counter is undefined and a {@link CounterException} is
    * thrown.
    *
    * @return the operation's status.
    */
   void checkStatus(short status) {
      if (status == KEY_DOES_NOT_EXIST_STATUS) {
         throw commonsLog.undefinedCounter(counterName);
      }
   }

   void setCacheName() {
      header.cacheName(COUNTER_CACHE_NAME);
   }

   @Override
   protected void fetchChannelAndInvoke(int retryCount, Set<SocketAddress> failedServers) {
      channelFactory.fetchChannelAndInvoke(failedServers, COUNTER_CACHE_NAME, this);
   }

   @Override
   protected Throwable handleException(Throwable cause, ChannelHandlerContext ctx, SocketAddress address) {
      cause =  super.handleException(cause, ctx, address);
      if (cause instanceof CounterException) {
         completeExceptionally(cause);
         return null;
      }
      return cause;
   }

   @Override
   protected void addParams(StringBuilder sb) {
      sb.append("counter=").append(counterName);
   }
}
