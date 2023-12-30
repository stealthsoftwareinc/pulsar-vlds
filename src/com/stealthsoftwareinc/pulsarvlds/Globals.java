//
// Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
//
// Permission is hereby granted, free of charge, to any person
// obtaining a copy of this software and associated documentation
// files (the "Software"), to deal in the Software without
// restriction, including without limitation the rights to use,
// copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following
// conditions:
//
// The above copyright notice and this permission notice (including
// the next paragraph) shall be included in all copies or
// substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
// OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
// HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
// WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
// OTHER DEALINGS IN THE SOFTWARE.
//
// SPDX-License-Identifier: MIT
//

package com.stealthsoftwareinc.pulsarvlds;

import static com.stealthsoftwareinc.sst.Assert.SST_ASSERT;
import static com.stealthsoftwareinc.sst.Assert.SST_NDEBUG;

import com.stealthsoftwareinc.sst.ConcurrentPool;
import com.stealthsoftwareinc.sst.Guid;
import com.stealthsoftwareinc.sst.JdbcConnection;
import com.stealthsoftwareinc.sst.JdbcConnectionFactory;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.Maps;
import com.stealthsoftwareinc.sst.RandModContext;
import com.stealthsoftwareinc.sst.ThreadedLogFile;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class Globals {

  //--------------------------------------------------------------------
  // Standard streams
  //--------------------------------------------------------------------

  private final InputStream stdin_;
  private final PrintStream stdout_;
  private final PrintStream stderr_;

  //--------------------------------------------------------------------

  private final Config config_;

  //--------------------------------------------------------------------
  // SSL for raw connections
  //--------------------------------------------------------------------

  private final SelfSignedCertificate selfSignedCertificate_ =
      new SelfSignedCertificate("localhost", new SecureRandom(), 2048);

  public final SelfSignedCertificate selfSignedCertificate() {
    return selfSignedCertificate_;
  }

  //--------------------------------------------------------------------
  // Logging
  //--------------------------------------------------------------------

  private final ThreadedLogFile logFile_ = new ThreadedLogFile();
  private final String logPrefix_;

  public final ThreadedLogFile logFile() {
    return logFile_;
  }

  private static final ThreadLocal<StringBuilder> logBuilder_ =
      new ThreadLocal<StringBuilder>() {
        @Override
        protected final StringBuilder initialValue() {
          return new StringBuilder();
        }
      };

  private String makeLogMessage(final CharSequence message) {
    final StringBuilder s = logBuilder_.get();
    s.setLength(0);
    logFile().timestamp(s);
    s.append(logPrefix_);
    s.append(message);
    s.append('\n');
    return s.toString();
  }

  public final void log(final CharSequence message) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(message != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    logFile().write(makeLogMessage(message));
  }

  public final void log(final PrintStream stream,
                        final CharSequence message) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(stream != null);
        SST_ASSERT(message != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    synchronized (stream) {
      stream.print(makeLogMessage(message));
    }
  }

  //--------------------------------------------------------------------

  private final String lexiconString_;

  private final EventLoopGroup ioThreadGroup_;
  private final UnorderedThreadPoolEventExecutor workerThreadGroup_;

  private final ConcurrentHashMap<Party, ConcurrentPool<ChannelFuture>>
      rawChannels_;

  private final ConcurrentPool<Future<JdbcConnection>> sqlChannels_;

  //--------------------------------------------------------------------
  // Shared handler data
  //--------------------------------------------------------------------

  private final ConcurrentHashMap<Guid,
                                  RawChannelHandler.SharedHandlerData>
      shdMap_ =
          new ConcurrentHashMap<Guid,
                                RawChannelHandler.SharedHandlerData>();

  public final RawChannelHandler.SharedHandlerData
  createOrGetSharedHandlerData(final RawChannelHandler handler,
                               final Guid queryId) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(handler != null);
        SST_ASSERT(queryId != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final RawChannelHandler.SharedHandlerData a = shdMap_.get(queryId);
    if (a != null) {
      return a;
    }
    final RawChannelHandler.SharedHandlerData b =
        new RawChannelHandler.SharedHandlerData(handler);
    final RawChannelHandler.SharedHandlerData c =
        shdMap_.putIfAbsent(queryId, b);
    if (c == null) {
      return b;
    }
    return c;
  }

  public final void removeSharedHandlerData(final Guid queryId) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(queryId != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    shdMap_.remove(queryId);
  }

  //--------------------------------------------------------------------

  private final ConcurrentHashMap<Guid, ActiveQuery> activeQueries_ =
      new ConcurrentHashMap<Guid, ActiveQuery>();

  public final ActiveQuery getOrPutActiveQuery(final Guid queryId) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(queryId != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return Maps.getOrPut(activeQueries_, queryId, ActiveQuery.class);
  }

  public final ActiveQuery getActiveQuery(final Guid queryId) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(queryId != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return activeQueries_.get(queryId);
  }

  //--------------------------------------------------------------------

  private final Map<Guid, SharedWebSocketData> swdMap_ =
      new ConcurrentHashMap<Guid, SharedWebSocketData>();

  public final SharedWebSocketData
  createSharedWebSocketData(final Globals globals,
                            final Guid queryId,
                            final Query query) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
        SST_ASSERT(queryId != null);
        SST_ASSERT(query != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final SharedWebSocketData x =
        new SharedWebSocketData(globals, queryId, query);
    final SharedWebSocketData y = swdMap_.putIfAbsent(queryId, x);
    if (y != null) {
      throw new RuntimeException("Query ID collision.");
    }
    return x;
  }

  public final SharedWebSocketData
  getSharedWebSocketData(final Guid queryId) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(queryId != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return swdMap_.get(queryId);
  }

  //--------------------------------------------------------------------

  public Globals(final InputStream stdin,
                 final PrintStream stdout,
                 final PrintStream stderr,
                 final Config config,
                 final EventLoopGroup ioThreadGroup) throws Exception {
    if (!SST_NDEBUG) {
      SST_ASSERT(stdin != null);
      SST_ASSERT(stdout != null);
      SST_ASSERT(stderr != null);
      SST_ASSERT(config != null);
      SST_ASSERT(ioThreadGroup != null);
    }

    stdin_ = stdin;
    stdout_ = stdout;
    stderr_ = stderr;
    config_ = config;

    logFile_.file(config_.home() + File.separator + config_.localParty()
                  + "-%Y-%m.log");
    logPrefix_ = config_.localParty() + ": ";

    lexiconString_ = Json.dump(config_.lexicon().toJson());

    ioThreadGroup_ = ioThreadGroup;
    workerThreadGroup_ = new UnorderedThreadPoolEventExecutor(
        config_.workerThreadCount());

    rawChannels_ =
        new ConcurrentHashMap<Party, ConcurrentPool<ChannelFuture>>();
    for (final Party party : Party.values()) {
      rawChannels_.put(
          party,
          new ConcurrentPool<ChannelFuture>(new RawChannelFactory(
              new Bootstrap()
                  .group(ioThreadGroup_)
                  .channel(NioSocketChannel.class)
                  .handler(new RawChannelInitializer(this, party))
                  .option(ChannelOption.AUTO_READ, false)
                  .option(ChannelOption.SO_KEEPALIVE, true)
                  .option(ChannelOption.TCP_NODELAY, false),
              config_.rawConnectHost(party),
              config_.rawConnectPort(party))));
    }

    if (config_.localParty().isDb()) {
      sqlChannels_ = new ConcurrentPool<Future<JdbcConnection>>(
          new JdbcConnectionFactory<Future<JdbcConnection>>(
              config_.databaseConnection(),
              workerThreadGroup_));
    } else {
      sqlChannels_ = null;
    }

    workerThreadGroup_.scheduleWithFixedDelay(
        new Runnable() {
          @Override
          public final void run() {
            try {
              int nRemoved = 0;
              final Iterator<RawChannelHandler.SharedHandlerData> it =
                  shdMap_.values().iterator();
    next:
              while (it.hasNext()) {
                final RawChannelHandler.SharedHandlerData shd =
                    it.next();
                for (final Party party : Party.values()) {
                  for (final RawChannelHandler handler :
                       shd.handlers.get(party).values()) {
                    if (handler.isFatal()) {
                      ++nRemoved;
                      it.remove();
                      continue next;
                    }
                  }
                }
              }
              log("global zombie check: removed " + nRemoved
                  + " zombie object" + (nRemoved == 1 ? "" : "s"));
            } catch (Throwable e) {
              try {
                log("global zombie check: error: " + e.getMessage());
              } catch (final Throwable e2) {
              }
            }
          }
        },
        config_.zombieCheckCooldown(),
        config_.zombieCheckCooldown(),
        TimeUnit.SECONDS);
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final InputStream stdin() {
    return stdin_;
  }

  public final PrintStream stdout() {
    return stdout_;
  }

  public final PrintStream stderr() {
    return stderr_;
  }

  public final Config config() {
    return config_;
  }

  public final String lexiconString() {
    return lexiconString_;
  }

  public final EventLoopGroup ioThreadGroup() {
    return ioThreadGroup_;
  }

  public final UnorderedThreadPoolEventExecutor workerThreadGroup() {
    return workerThreadGroup_;
  }

  public final ConcurrentPool<ChannelFuture>
  rawChannels(final Party party) {
    if (!SST_NDEBUG) {
      SST_ASSERT(party != null);
    }
    return rawChannels_.get(party);
  }

  public final ConcurrentPool<Future<JdbcConnection>> sqlChannels() {
    return sqlChannels_;
  }

  //--------------------------------------------------------------------
}
