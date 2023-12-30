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

import com.stealthsoftwareinc.sst.Args;
import com.stealthsoftwareinc.sst.JdbcType;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.JsonException;
import com.stealthsoftwareinc.sst.NullInputStream;
import com.stealthsoftwareinc.sst.NullOutputStream;
import com.stealthsoftwareinc.sst.OptArg;
import com.stealthsoftwareinc.sst.ThreadedLogFile;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.NetUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class Server implements Callable<Integer> {

  private static int main(final InputStream stdin,
                          final PrintStream stdout,
                          final PrintStream stderr,
                          final LinkedList<String> args)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        if (args != null) {
          for (final String arg : args) {
            SST_ASSERT(arg != null);
          }
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    if (stdin == null) {
      try (final NullInputStream stdin2 = new NullInputStream()) {
        return main(stdin2, stdout, stderr, args);
      }
    }

    if (stdout == null) {
      try (final NullOutputStream stdout2 = new NullOutputStream();
           final PrintStream stdout3 = new PrintStream(stdout2)) {
        return main(stdin, stdout3, stderr, args);
      }
    }

    if (stderr == null) {
      try (final NullOutputStream stderr2 = new NullOutputStream();
           final PrintStream stderr3 = new PrintStream(stderr2)) {
        return main(stdin, stdout, stderr3, args);
      }
    }

    if (args == null) {
      return main(stdin, stdout, stderr, new LinkedList<String>());
    }

    final Config config;
    boolean haveConfig = false;
    final Map<String, Object> configObject =
        new HashMap<String, Object>();
    final StringBuilder configLabel = new StringBuilder();
    configLabel.append("config(");
    boolean parseOptions = true;
    while (!args.isEmpty()) {
      if (parseOptions) {

        //--------------------------------------------------------------
        // Options terminator
        //--------------------------------------------------------------

        if (Args.parseOpt(args, "--", OptArg.FORBIDDEN)) {
          parseOptions = false;
          continue;
        }

        //--------------------------------------------------------------
        // --config
        //--------------------------------------------------------------

        if (Args.parseOpt(args, "--config")) {
          final String arg = args.remove(0);
          if (arg.startsWith("{")) {
            try {
              final Object x;
              try {
                x = new JSONObject(arg).toMap();
              } catch (final JSONException e) {
                throw new JsonException(e);
              }
              Json.merge(configObject, Json.expectObject(x), true);
            } catch (final JsonException e) {
              throw e.add("<inline JSON>: ");
            }
            if (haveConfig) {
              configLabel.append(" + ");
            }
            configLabel.append("<inline JSON>");
          } else {
            final String file = arg;
            try {
              try (
                  final BufferedReader reader =
                      Files.newBufferedReader(Paths.get(file),
                                              StandardCharsets.UTF_8)) {
                final Object x;
                try {
                  x = new JSONObject(new JSONTokener(reader)).toMap();
                } catch (final JSONException e) {
                  throw new JsonException(e);
                }
                Json.merge(configObject, Json.expectObject(x), true);
              }
            } catch (final JsonException e) {
              throw e.addFile(file);
            }
            if (haveConfig) {
              configLabel.append(" + ");
            }
            configLabel.append(Json.smartQuote(file));
          }
          haveConfig = true;
          continue;
        }

        //--------------------------------------------------------------
        // --home
        //--------------------------------------------------------------

        if (Args.parseOpt(args, "--home")) {
          final String arg = args.remove(0);
          configObject.put("home", arg);
          continue;
        }

        //--------------------------------------------------------------
        // --prefix
        //--------------------------------------------------------------

        if (Args.parseOpt(args, "--prefix")) {
          final String arg = args.remove(0);
          configObject.put("prefix", arg);
          continue;
        }

        //--------------------------------------------------------------
        // Unknown options
        //--------------------------------------------------------------

        Args.unknownOpt(args);

        //--------------------------------------------------------------
      }

      //----------------------------------------------------------------
      // Unknown operands
      //----------------------------------------------------------------

      throw new RuntimeException("operands are forbidden: "
                                 + Json.smartQuote(args.get(0)));

      //----------------------------------------------------------------
    }
    Args.requireOpt("--config", haveConfig);
    configLabel.append(")");
    try {
      config = Json.getAs(configObject, Config.fromJson());
    } catch (final JsonException e) {
      throw e.add(configLabel + ": ");
    }

    final EventLoopGroup ioThreadGroup =
        new NioEventLoopGroup(config.ioThreadCount());
    try {
      final Globals globals =
          new Globals(stdin, stdout, stderr, config, ioThreadGroup);
      try {

        //--------------------------------------------------------------
        // Database verification
        //--------------------------------------------------------------

        if (config.localParty().isDb()) {
          final Table table = config.lexicon()
                                  .dbInfos()
                                  .get(config.localParty())
                                  .table();
          final List<Column> columns =
              new ArrayList<Column>(table.columns().values());
          try (final Connection connection =
                   DriverManager.getConnection(
                       config.databaseConnection().url());
               final Statement statement =
                   connection.createStatement()) {
            final String query;
            {
              final StringBuilder q = new StringBuilder();
              String comma = " ";
              q.append("SELECT");
              for (final Column column : columns) {
                q.append(comma);
                q.append(column.underlyingName());
                comma = ", ";
              }
              q.append(" FROM ");
              q.append(table.underlyingName());
              q.append(" WHERE 0 = 1");
              query = q.toString();
            }
            globals.log("Verifying database: " + query);
            try (final ResultSet result =
                     statement.executeQuery(query)) {
              final ResultSetMetaData metadata = result.getMetaData();
              for (int i = 0; i < columns.size(); ++i) {
                columns.get(i).jdbcType(
                    JdbcType.fromInt(metadata.getColumnType(i + 1)));
              }
            }
          }
          switch (table.linkingColumn().jdbcType()) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case BIT:
            case BOOLEAN:
            case CHAR:
            case VARCHAR:
            case NCHAR:
            case NVARCHAR:
              break;
            default: {
              final StringBuilder s = new StringBuilder();
              s.append("The linking column has JDBC type ");
              s.append(table.linkingColumn().jdbcType());
              s.append(", but only the following JDBC types ");
              s.append("are supported for the linking column: ");
              s.append("TINYINT, ");
              s.append("SMALLINT, ");
              s.append("INTEGER, ");
              s.append("BIGINT, ");
              s.append("BIT, ");
              s.append("BOOLEAN, ");
              s.append("CHAR, ");
              s.append("VARCHAR, ");
              s.append("NCHAR, ");
              s.append("and ");
              s.append("NVARCHAR.");
              throw new RuntimeException(s.toString());
            } // break;
          }
        }

        //--------------------------------------------------------------

        final ServerBootstrap rawBootstrap =
            new ServerBootstrap()
                .group(globals.ioThreadGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new RawChannelInitializer(globals, null))
                .option(ChannelOption.SO_BACKLOG,
                        Math.min(NetUtil.SOMAXCONN, 128))
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.AUTO_READ, false)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, false);

        final ServerBootstrap httpBootstrap;
        if (config.localParty() == Party.PH) {
          httpBootstrap =
              new ServerBootstrap()
                  .group(globals.ioThreadGroup())
                  .channel(NioServerSocketChannel.class)
                  .childHandler(new HttpChannelInitializer(globals))
                  .option(ChannelOption.SO_BACKLOG,
                          Math.min(NetUtil.SOMAXCONN, 128))
                  .option(ChannelOption.SO_REUSEADDR, true)
                  .childOption(ChannelOption.AUTO_READ, false)
                  .childOption(ChannelOption.SO_KEEPALIVE, true)
                  .childOption(ChannelOption.TCP_NODELAY, true);
        } else {
          httpBootstrap = null;
        }

        final Channel rawListener =
            rawBootstrap
                .bind(config.rawListenHost(), config.rawListenPort())
                .sync()
                .channel();

        try {

          final Channel httpListener;
          if (httpBootstrap != null) {
            httpListener = httpBootstrap
                               .bind(config.httpListenHost(),
                                     config.httpListenPort())
                               .sync()
                               .channel();
          } else {
            httpListener = null;
          }

          try {

            //----------------------------------------------------------
            // Shutdown
            //----------------------------------------------------------

            Runtime.getRuntime().addShutdownHook(new Thread() {
              @Override
              public final void run() {
                globals.log(globals.stdout(), "Shutting down.");
                globals.log("Shutting down.");

                try {
                  if (httpListener != null) {
                    httpListener.close().sync();
                  }
                } catch (final Exception e) {
                }

                try {
                  rawListener.close().sync();
                } catch (final Exception e) {
                }

                try {
                  globals.ioThreadGroup()
                      .shutdownGracefully(0, 0, TimeUnit.NANOSECONDS)
                      .sync();
                } catch (final Throwable e) {
                }

                try {
                  globals.workerThreadGroup()
                      .shutdownGracefully(0, 0, TimeUnit.NANOSECONDS)
                      .sync();
                } catch (final Throwable e) {
                }

                try {
                  globals.selfSignedCertificate().delete();
                } catch (final Throwable e) {
                }
              }
            });

            //----------------------------------------------------------

            {
              final String f = globals.logFile().file();
              globals.log(globals.stdout(),
                          "Node started. "
                              + (f != null ?
                                     "Logging to '"
                                         + f.replace("'", "'\\''")
                                         + "'." :
                                     "Logging is disabled."));
            }

            globals.log("Listening for raw connections on "
                        + config.rawListenHost() + ":"
                        + config.rawListenPort() + ".");
            if (httpBootstrap != null) {
              globals.log("Listening for HTTP connections on "
                          + config.httpListenHost() + ":"
                          + config.httpListenPort() + ".");
            }

            stdout.println("Press Ctrl+C to stop the server.");

            while (true) {
              Thread.sleep(1000);
            }

          } finally {
            try {
              if (httpListener != null) {
                httpListener.close().sync();
              }
            } catch (final Throwable e) {
            }
          }
        } finally {
          try {
            rawListener.close().sync();
          } catch (final Throwable e) {
          }
        }
      } finally {
        try {
          globals.workerThreadGroup()
              .shutdownGracefully(0, 0, TimeUnit.NANOSECONDS)
              .sync();
        } catch (final Throwable e) {
        }
      }
    } finally {
      try {
        ioThreadGroup.shutdownGracefully(0, 0, TimeUnit.NANOSECONDS)
            .sync();
      } catch (final Throwable e) {
      }
    }
  }

  public static final int main(final InputStream stdin,
                               final PrintStream stdout,
                               final PrintStream stderr,
                               final List<String> args)
      throws Exception {
    return main(stdin,
                stdout,
                stderr,
                args == null ? null : new LinkedList<String>(args));
  }

  public static final void main(final String... args) throws Exception {
    System.exit(main(System.in,
                     System.out,
                     System.err,
                     args == null ? null : Arrays.asList(args)));
  }

  private boolean called_ = false;
  private final InputStream stdin_;
  private final PrintStream stdout_;
  private final PrintStream stderr_;
  private final LinkedList<String> args_;

  public Server(final InputStream stdin,
                final PrintStream stdout,
                final PrintStream stderr,
                final List<String> args) {
    stdin_ = stdin;
    stdout_ = stdout;
    stderr_ = stderr;
    args_ = args == null ? null : new LinkedList<String>(args);
  }

  public Server(final InputStream stdin,
                final PrintStream stdout,
                final PrintStream stderr,
                final String... args) {
    this(stdin,
         stdout,
         stderr,
         args == null ? null : Arrays.asList(args));
  }

  @Override
  public final Integer call() throws Exception {
    SST_ASSERT(!called_);
    called_ = true;
    return main(stdin_, stdout_, stderr_, args_);
  }
}
