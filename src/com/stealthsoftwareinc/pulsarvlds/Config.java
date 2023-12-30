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

import com.stealthsoftwareinc.sst.CreateFromJson;
import com.stealthsoftwareinc.sst.HostAndPort;
import com.stealthsoftwareinc.sst.JdbcAddress;
import com.stealthsoftwareinc.sst.JdbcSubprotocol;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.JsonException;
import com.stealthsoftwareinc.sst.Types;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Config {

  private static final String DEFAULT_CONNECT_HOST = "127.0.0.1";
  private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
  private static final int FIRST_RAW_PORT = 19501;

  //--------------------------------------------------------------------
  // calculation_scale
  //--------------------------------------------------------------------

  private static final String CALCULATION_SCALE_KEY =
      "calculation_scale";
  private static final int DEFAULT_CALCULATION_SCALE = 10;
  private int calculationScale_;
  private boolean doneCalculationScale_ = false;

  private int calculationScale(final Map<String, ?> src) {
    if (!doneCalculationScale_) {
      calculationScale_ = Json.removeAs(src,
                                        CALCULATION_SCALE_KEY,
                                        calculationScale_,
                                        DEFAULT_CALCULATION_SCALE);
      try {
        if (calculationScale_ < 0) {
          throw new JsonException(
              "value must be a nonnegative integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(CALCULATION_SCALE_KEY);
      }
      doneCalculationScale_ = true;
    }
    return calculationScale_;
  }

  public final int calculationScale() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneCalculationScale_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return calculationScale_;
  }

  //--------------------------------------------------------------------
  // channel_output_buffer_limit
  //--------------------------------------------------------------------

  private static final String CHANNEL_OUTPUT_BUFFER_LIMIT_KEY =
      "channel_output_buffer_limit";
  private ChannelOutputBufferLimit channelOutputBufferLimit_;
  private boolean doneChannelOutputBufferLimit_ = false;

  private ChannelOutputBufferLimit
  channelOutputBufferLimit(final Map<String, ?> src) {
    if (!doneChannelOutputBufferLimit_) {
      channelOutputBufferLimit_ =
          Json.removeAs(src,
                        CHANNEL_OUTPUT_BUFFER_LIMIT_KEY,
                        channelOutputBufferLimit_.fromJson());
      doneChannelOutputBufferLimit_ = true;
    }
    return channelOutputBufferLimit_;
  }

  public final ChannelOutputBufferLimit channelOutputBufferLimit() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneChannelOutputBufferLimit_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return channelOutputBufferLimit_;
  }

  //--------------------------------------------------------------------
  // home
  //--------------------------------------------------------------------

  private static final String homeKey_ = "home";
  private boolean doneHome_ = false;
  private String home_ = "~/.pulsar-vlds";

  private static String translateTilde() {
    final Map<String, String> env = System.getenv();
    {
      final String x = env.get("HOME");
      if (x != null) {
        return x;
      }
    }
    {
      final String x = env.get("LOCALAPPDATA");
      if (x != null) {
        return x;
      }
    }
    throw new JsonException(
        "Unable to determine a \"~\" directory: neither of the"
        + " HOME or LOCALAPPDATA environment variables are set.");
  }

  private String home(final Map src) {
    if (!doneHome_) {
      home_ = Json.removeAs(src, homeKey_, home_, home_);
      try {
        if (home_.startsWith("~")) {
          home_ = translateTilde() + home_.substring(1);
        }
      } catch (final JsonException e) {
        throw e.addKey(homeKey_);
      }
      doneHome_ = true;
    }
    return home_;
  }

  public final String home() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneHome_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return home_;
  }

  //--------------------------------------------------------------------
  // http_listen_host
  //--------------------------------------------------------------------

  private static final String httpListenHostKey_ = "http_listen_host";

  private String httpListenHost_ = "::";

  private void httpListenHost(final Map src) {
    httpListenHost_ = Json.getAs(src,
                                 httpListenHostKey_,
                                 httpListenHost_,
                                 httpListenHost_);
    src.remove(httpListenHostKey_);
  }

  public final String httpListenHost() {
    try {
      return httpListenHost_;
    } catch (final JsonException e) {
      throw e.addKey(httpListenHostKey_);
    }
  }

  //--------------------------------------------------------------------
  // http_listen_port
  //--------------------------------------------------------------------

  private static final String httpListenPortKey_ = "http_listen_port";

  private int httpListenPort_ = 8080;

  private void httpListenPort(final Map src) {
    httpListenPort_ = Json.getAs(src,
                                 httpListenPortKey_,
                                 httpListenPort_,
                                 httpListenPort_);
    src.remove(httpListenPortKey_);
    try {
      if (httpListenPort_ < 1 || httpListenPort_ > 65535) {
        throw new JsonException("value must be between 1 and 65535");
      }
    } catch (final JsonException e) {
      throw e.addKey(httpListenPortKey_);
    }
  }

  public final int httpListenPort() {
    try {
      return httpListenPort_;
    } catch (final JsonException e) {
      throw e.addKey(httpListenPortKey_);
    }
  }

  //--------------------------------------------------------------------
  // interserver_connections
  //--------------------------------------------------------------------

  private Map<String, HostAndPort> interserverConnections_;
  private boolean doneInterserverConnections_ = false;

  private final Map<String, HostAndPort>
  interserverConnections(final Map<String, ?> src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!doneInterserverConnections_) {
      final Object rcObject = src.remove("interserver_connections");
      try {
        final Map<Party, Object> pMap = new HashMap<Party, Object>();
        final Object listen;
        if (rcObject == null) {
          listen = null;
        } else {
          final Map<String, ?> rc = Json.expectObject(rcObject);
          for (final Party party : Party.values()) {
            pMap.put(party, rc.remove(party.toString()));
          }
          listen = rc.remove("listen");
          Json.unknownKey(rc);
        }
        final Map<String, HostAndPort> sMap =
            new HashMap<String, HostAndPort>();
        for (final Party party : Party.values()) {
          try {
            final HostAndPort a = Json.getAs(
                pMap.get(party),
                HostAndPort.fromJson(DEFAULT_CONNECT_HOST,
                                     FIRST_RAW_PORT + party.toInt()));
            if (a.port() == 0) {
              throw new JsonException(
                  "A connect port number must not be zero.");
            }
            sMap.put(party.toString(), a);
          } catch (final JsonException e) {
            throw e.addKey(party.toString());
          }
        }
        try {
          final HostAndPort a =
              Json.getAs(listen,
                         HostAndPort.fromJson(
                             DEFAULT_LISTEN_HOST,
                             FIRST_RAW_PORT + localParty(src).toInt()));
          sMap.put("listen", a);
        } catch (final JsonException e) {
          throw e.addKey("listen");
        }
        interserverConnections_ = Collections.unmodifiableMap(sMap);
      } catch (final JsonException e) {
        throw e.addKey("interserver_connections");
      }
      doneInterserverConnections_ = true;
    }
    return interserverConnections_;
  }

  public final String rawConnectHost(final Party party) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneInterserverConnections_);
        SST_ASSERT(party != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return interserverConnections_.get(party.toString()).host();
  }

  public final int rawConnectPort(final Party party) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneInterserverConnections_);
        SST_ASSERT(party != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return interserverConnections_.get(party.toString()).port();
  }

  public final String rawListenHost() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneInterserverConnections_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return interserverConnections_.get("listen").host();
  }

  public final int rawListenPort() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneInterserverConnections_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return interserverConnections_.get("listen").port();
  }

  //--------------------------------------------------------------------
  // io_thread_count
  //--------------------------------------------------------------------

  private static final String IO_THREAD_COUNT_KEY = "io_thread_count";
  private static final int DEFAULT_IO_THREAD_COUNT = 8;
  private int ioThreadCount_;
  private boolean doneIoThreadCount_ = false;

  private int ioThreadCount(final Map<String, ?> src) {
    if (!doneIoThreadCount_) {
      ioThreadCount_ = Json.removeAs(src,
                                     IO_THREAD_COUNT_KEY,
                                     ioThreadCount_,
                                     DEFAULT_IO_THREAD_COUNT);
      try {
        if (ioThreadCount_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(IO_THREAD_COUNT_KEY);
      }
      doneIoThreadCount_ = true;
    }
    return ioThreadCount_;
  }

  public final int ioThreadCount() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneIoThreadCount_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return ioThreadCount_;
  }

  //--------------------------------------------------------------------
  // lexicon
  //--------------------------------------------------------------------

  private static final String lexiconKey_ = "lexicon";

  private Lexicon lexicon_ = null;

  private void lexicon(final Map src) {
    lexicon_ = Json.getAs(src, lexiconKey_, lexicon_.fromJson());
    src.remove(lexiconKey_);
  }

  public final Lexicon lexicon() {
    try {
      return lexicon_;
    } catch (final JsonException e) {
      throw e.addKey(lexiconKey_);
    }
  }

  //--------------------------------------------------------------------
  // local_party
  //--------------------------------------------------------------------

  private Party localParty_;
  private boolean doneLocalParty_ = false;

  private final Party localParty(final Map<String, ?> src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!doneLocalParty_) {
      localParty_ =
          Json.removeAs(src, "local_party", localParty_.fromJson());
      doneLocalParty_ = true;
    }
    return localParty_;
  }

  public final Party localParty() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneLocalParty_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return localParty_;
  }

  //--------------------------------------------------------------------
  // order_by_override
  //--------------------------------------------------------------------

  private static final String orderByOverrideKey_ = "order_by_override";
  private boolean doneOrderByOverride_ = false;
  private String orderByOverride_ = "";

  private String orderByOverride(final Map<String, ?> src) {
    if (!doneOrderByOverride_) {
      orderByOverride_ = Json.removeAs(src,
                                       orderByOverrideKey_,
                                       orderByOverride_,
                                       orderByOverride_);
      doneOrderByOverride_ = true;
    }
    return orderByOverride_;
  }

  public final String orderByOverride() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneOrderByOverride_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return orderByOverride_;
  }

  //--------------------------------------------------------------------
  // prefix
  //--------------------------------------------------------------------

  private static final String prefixKey_ = "prefix";
  private boolean donePrefix_ = false;
  private String prefix_ = "";

  private String prefix(final Map<String, ?> src) {
    if (!donePrefix_) {
      prefix_ = Json.removeAs(src, prefixKey_, prefix_, prefix_);
      if (!prefix_.isEmpty() && !prefix_.endsWith("/")) {
        prefix_ += '/';
      }
      donePrefix_ = true;
    }
    return prefix_;
  }

  public final String prefix() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(donePrefix_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return prefix_;
  }

  //--------------------------------------------------------------------
  // result_scale
  //--------------------------------------------------------------------

  private static final String RESULT_SCALE_KEY = "result_scale";
  private static final int DEFAULT_RESULT_SCALE = 6;
  private int resultScale_;
  private boolean doneResultScale_ = false;

  private int resultScale(final Map<String, ?> src) {
    if (!doneResultScale_) {
      resultScale_ = Json.removeAs(src,
                                   RESULT_SCALE_KEY,
                                   resultScale_,
                                   DEFAULT_RESULT_SCALE);
      try {
        if (resultScale_ < 0) {
          throw new JsonException(
              "value must be a nonnegative integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(RESULT_SCALE_KEY);
      }
      doneResultScale_ = true;
    }
    return resultScale_;
  }

  public final int resultScale() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneResultScale_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return resultScale_;
  }

  //--------------------------------------------------------------------
  // result_update_cooldown
  //--------------------------------------------------------------------

  private static final String RESULT_UPDATE_COOLDOWN_KEY =
      "result_update_cooldown";
  private static final int DEFAULT_RESULT_UPDATE_COOLDOWN = 1000;
  private int resultUpdateCooldown_;
  private boolean doneResultUpdateCooldown_ = false;

  private int resultUpdateCooldown(final Map<String, ?> src) {
    if (!doneResultUpdateCooldown_) {
      resultUpdateCooldown_ =
          Json.removeAs(src,
                        RESULT_UPDATE_COOLDOWN_KEY,
                        resultUpdateCooldown_,
                        DEFAULT_RESULT_UPDATE_COOLDOWN);
      try {
        if (resultUpdateCooldown_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(RESULT_UPDATE_COOLDOWN_KEY);
      }
      doneResultUpdateCooldown_ = true;
    }
    return resultUpdateCooldown_;
  }

  public final int resultUpdateCooldown() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneResultUpdateCooldown_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return resultUpdateCooldown_;
  }

  //--------------------------------------------------------------------
  // database_connection
  //--------------------------------------------------------------------

  private static final String databaseConnectionKey_ =
      "database_connection";

  private JdbcAddress databaseConnection_ = null;

  private static final List<JdbcSubprotocol>
      SUPPORTED_JDBC_SUBPROTOCOLS = Collections.unmodifiableList(
          Arrays.asList(new JdbcSubprotocol[] {
              JdbcSubprotocol.MYSQL,
              JdbcSubprotocol.POSTGRESQL,
              JdbcSubprotocol.SQLITE,
              JdbcSubprotocol.SQLSERVER,
          }));

  private static final Set<Party> DATABASE_PARTIES =
      Collections.unmodifiableSet(
          new HashSet<Party>(Arrays.asList(new Party[] {
              Party.DB1,
              Party.DB2,
          })));

  private void databaseConnection(final Map src) {
    databaseConnection_ = Json.removeAs(src,
                                        databaseConnectionKey_,
                                        databaseConnection_.fromJson(),
                                        null);
    try {
      if (databaseConnection_ != null) {
        final JdbcSubprotocol x = databaseConnection_.subprotocol();
        if (SUPPORTED_JDBC_SUBPROTOCOLS.indexOf(x) < 0) {
          throw new JsonException("unsupported JDBC subprotocol: " + x);
        }
      }
    } catch (final JsonException e) {
      throw e.addKey(databaseConnectionKey_);
    }
  }

  public final JdbcAddress databaseConnection() {
    try {
      if (databaseConnection_ == null) {
        if (DATABASE_PARTIES.contains(localParty())) {
          Json.expectPresent(null);
        }
      }
      return databaseConnection_;
    } catch (final JsonException e) {
      throw e.addKey(databaseConnectionKey_);
    }
  }

  //--------------------------------------------------------------------
  // worker_thread_count
  //--------------------------------------------------------------------

  private static final String WORKER_THREAD_COUNT_KEY =
      "worker_thread_count";
  private static final int DEFAULT_WORKER_THREAD_COUNT = 8;
  private int workerThreadCount_;
  private boolean doneWorkerThreadCount_ = false;

  private int workerThreadCount(final Map<String, ?> src) {
    if (!doneWorkerThreadCount_) {
      workerThreadCount_ = Json.removeAs(src,
                                         WORKER_THREAD_COUNT_KEY,
                                         workerThreadCount_,
                                         DEFAULT_WORKER_THREAD_COUNT);
      try {
        if (workerThreadCount_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(WORKER_THREAD_COUNT_KEY);
      }
      doneWorkerThreadCount_ = true;
    }
    return workerThreadCount_;
  }

  public final int workerThreadCount() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneWorkerThreadCount_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return workerThreadCount_;
  }

  //--------------------------------------------------------------------
  // zombie_check_cooldown
  //--------------------------------------------------------------------

  private static final String ZOMBIE_CHECK_COOLDOWN_KEY =
      "zombie_check_cooldown";
  private static final int DEFAULT_ZOMBIE_CHECK_COOLDOWN = 300;
  private int zombieCheckCooldown_;
  private boolean doneZombieCheckCooldown_ = false;

  private int zombieCheckCooldown(final Map<String, ?> src) {
    if (!doneZombieCheckCooldown_) {
      zombieCheckCooldown_ =
          Json.removeAs(src,
                        ZOMBIE_CHECK_COOLDOWN_KEY,
                        zombieCheckCooldown_,
                        DEFAULT_ZOMBIE_CHECK_COOLDOWN);
      try {
        if (zombieCheckCooldown_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(ZOMBIE_CHECK_COOLDOWN_KEY);
      }
      doneZombieCheckCooldown_ = true;
    }
    return zombieCheckCooldown_;
  }

  public final int zombieCheckCooldown() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneZombieCheckCooldown_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return zombieCheckCooldown_;
  }

  //--------------------------------------------------------------------
  // zombie_check_threshold
  //--------------------------------------------------------------------

  private static final String ZOMBIE_CHECK_THRESHOLD_KEY =
      "zombie_check_threshold";
  private static final int DEFAULT_ZOMBIE_CHECK_THRESHOLD = 180;
  private int zombieCheckThreshold_;
  private boolean doneZombieCheckThreshold_ = false;

  private int zombieCheckThreshold(final Map<String, ?> src) {
    if (!doneZombieCheckThreshold_) {
      zombieCheckThreshold_ =
          Json.removeAs(src,
                        ZOMBIE_CHECK_THRESHOLD_KEY,
                        zombieCheckThreshold_,
                        DEFAULT_ZOMBIE_CHECK_THRESHOLD);
      try {
        if (zombieCheckThreshold_ < 1) {
          throw new JsonException("value must be a positive integer");
        }
      } catch (final JsonException e) {
        throw e.addKey(ZOMBIE_CHECK_THRESHOLD_KEY);
      }
      doneZombieCheckThreshold_ = true;
    }
    return zombieCheckThreshold_;
  }

  public final int zombieCheckThreshold() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(doneZombieCheckThreshold_);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return zombieCheckThreshold_;
  }

  //--------------------------------------------------------------------

  private Config(final Map<String, ?> src,
                 final CreateFromJson<Config> createFromJsonTag) {
    calculationScale(src);
    channelOutputBufferLimit(src);
    databaseConnection(src);
    home(src);
    httpListenHost(src);
    httpListenPort(src);
    interserverConnections(src);
    ioThreadCount(src);
    lexicon(src);
    localParty(src);
    orderByOverride(src);
    prefix(src);
    resultScale(src);
    resultUpdateCooldown(src);
    workerThreadCount(src);
    zombieCheckCooldown(src);
    zombieCheckThreshold(src);

    Json.unknownKey(src);
  }

  private Config(final Object src,
                 final CreateFromJson<Config> createFromJsonTag) {
    this(Json.copy(Json.expectObject(src)), createFromJsonTag);
  }

  public static final CreateFromJson<Config> fromJson() {
    return new CreateFromJson<Config>() {
      @Override
      public final Config createFromJson(final Object src) {
        return new Config(src, this);
      }
    };
  }

  //--------------------------------------------------------------------
}
