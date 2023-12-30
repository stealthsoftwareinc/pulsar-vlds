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

import com.stealthsoftwareinc.sst.Arith;
import com.stealthsoftwareinc.sst.ConcurrentPool;
import com.stealthsoftwareinc.sst.Consumer;
import com.stealthsoftwareinc.sst.FixedPointModContext;
import com.stealthsoftwareinc.sst.Guid;
import com.stealthsoftwareinc.sst.ImpossibleException;
import com.stealthsoftwareinc.sst.IntegerRep;
import com.stealthsoftwareinc.sst.Jdbc;
import com.stealthsoftwareinc.sst.JdbcConnection;
import com.stealthsoftwareinc.sst.JdbcType;
import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.Memory;
import com.stealthsoftwareinc.sst.Modulus;
import com.stealthsoftwareinc.sst.PoolEntry;
import com.stealthsoftwareinc.sst.Rand;
import com.stealthsoftwareinc.sst.RandModContext;
import com.stealthsoftwareinc.sst.Rep;
import com.stealthsoftwareinc.sst.Supplier;
import com.stealthsoftwareinc.sst.netty.JdbcRunner;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

//
// TODO: Use IntegerRep.PURE_UNSIGNED for all toBytes calls once SST has
//       it implemented. Using checked = false makes TWOS_COMPLEMENT the
//       same as PURE_UNSIGNED (I think?), but using PURE_UNSIGNED
//       everywhere will be more consistent.
//

//
// The RawChannelHandler class is the back end of the VLDS system. It
// implements all three parties: DB1, DB2, and the PH. A query always
// begins on the PH, which connects outward to each DB three times, and
// DB1 connects outward to DB2 one time. The three PH-DB connections are
// called S1, S2, and S3, and the single DB-DB connection is typically
// split into two halves called the sender half (SH) and the receiver
// half (RH).
//
//                          Connection diagram
//
//            +-----------+                    +-----------+
//            |           |       SH/RH        |           |
//            |    DB1    |<------------------>|    DB2    |
//            |           |                    |           |
//            +-----------+                    +-----------+
//              ^   ^   ^                        ^   ^   ^
//              |   |   |                        |   |   |
//              |S1 |S2 |S3                    S3| S2| S1|
//              |   |   |      +----------+      |   |   |
//              |   |   +----->|          |<-----+   |   |
//              |   +--------->|    PH    |<---------+   |
//              +------------->|          |<-------------+
//                             +----------+
//
// Each side of each connection has its own RawChannelHandler. For one
// query, the PH uses 6 handlers and each DB uses 4 handlers, giving a
// total of 14 handlers.
//
// Connections between parties are pooled so that queries will reuse
// idle connections when possible instead of always establishing new
// connections. Each DB party also pools its database connections in
// this way.
//
// Each handler is said to be either outgoing or incoming, where
// "outgoing" means it connected outward to the remote party, and
// "incoming" means it accepted an incoming connection from the remote
// party. The PH always connects outward to DB1 and DB2, and DB1 always
// connects outward to DB2.
//
// All handlers go through the following handshake states at the
// beginning of a connection:
//
//       Outgoing: SEND_PARTY -> SEND_LEXICON -> RECV_LEXICON_*
//       Incoming: RECV_PARTY -> SEND_LEXICON -> RECV_LEXICON_*
//
// After completing these states, the local and remote lexicons are
// compared, and the connection is aborted if the lexicons don't match.
// Otherwise, all handlers wait for a query to begin, going through the
// following initial states:
//
//       Outgoing: SEND_QUERY
//       Incoming: RECV_QUERY_*
//
// The outgoing handler always sends the query to the remote incoming
// handler. When the query completes, the handlers go back to these
// states, waiting to be reused for another query.
//
// Note that the handshake only occurs once per connection, i.e., once
// per handler, not per query. After a handler runs the handshake, it
// will never run it again. In other words, a handler wakes up when a
// new query arrives, and the handler runs the handshake only just
// before the first query it ever processes.
//

final class RawChannelHandler
    extends SimpleChannelInboundHandler<ByteBuf> {

  //--------------------------------------------------------------------
  // The BothRowCounts class
  //--------------------------------------------------------------------

  private static final class BothRowCounts {
    public final long localRowCount;
    public final long otherRowCount;
    public BothRowCounts(final long localRowCount,
                         final long otherRowCount) {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(localRowCount >= 0);
          SST_ASSERT(otherRowCount >= 0);
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      this.localRowCount = localRowCount;
      this.otherRowCount = otherRowCount;
    }
  }

  //--------------------------------------------------------------------

  private final Globals globals_;
  private final Config config_;
  private final ChannelPipeline pipeline_;
  private final boolean outgoing_;

  //--------------------------------------------------------------------

  private long totalRx_ = 0;
  private long totalRxAtQueryStart_;
  private long totalRxAtQueryDone_;

  //--------------------------------------------------------------------

  private static final class XIntBatch {
    public final byte[] id;
    public final int[] xs;
    public XIntBatch(final int rows,
                     final int cols,
                     final int linkingColumnSize) {
      id = new byte[rows * linkingColumnSize];
      xs = new int[rows * cols];
    }
  }

  private static final class XLongBatch {
    public final byte[] id;
    public final long[] xs;
    public XLongBatch(final int rows,
                      final int cols,
                      final int linkingColumnSize) {
      id = new byte[rows * linkingColumnSize];
      xs = new long[rows * cols];
    }
  }

  private static final class XBigBatch {
    public final byte[] id;
    public final BigInteger[] xs;
    public XBigBatch(final int rows,
                     final int cols,
                     final int linkingColumnSize) {
      id = new byte[rows * linkingColumnSize];
      xs = new BigInteger[rows * cols];
    }
  }

  //--------------------------------------------------------------------

  private static final class MergeMachine {
    // TODO: Some stuff in here is naively duplicated for DB1 and DB2.
    //       Maybe there's a way to cut that code in half by storing
    //       foo{1,2} things in two-element arrays, although there's
    //       worries about performance when doing that in Java since we
    //       don't have stack arrays and we have to use ArrayList for
    //       arrays with generic element types.

    private final Globals globals_;
    private final Config config_;
    private final Lexicon lexicon_;
    private final int linkingColumnSize_;
    private final Query query_;
    private final DomainIterator domainIterator_;
    private final int tupleCount_;
    private final int columnCount_;
    private int tupleIndex_ = -1;
    private final SharedHandlerData shd_;
    private final SharedWebSocketData swd_;
    private SharedWebSocketData.Progress progress_;
    private final int modulusInt_;
    private final long modulusLong_;
    private final BigInteger modulusBig_;
    private final int valueSize_;
    private final boolean valuesFitInt_;
    private final boolean valuesFitLong_;
    private final int aggCount1_;
    private final int aggCount2_;
    private final int xaBytesSize1_;
    private final int xaBytesSize2_;

    private enum State {
      MM_NEXT_DOMAIN_TUPLE,
      MM_RECV_ROW_COUNT_1,
      MM_RECV_ROW_COUNT_2,
      MM_NEXT,
      MM_RECV_XA_BATCH_1,
      MM_RECV_XA_BATCH_2,
      MM_COMPARE,
      MM_RECV_B_BATCH_1,
      MM_RECV_B_BATCH_2,
      MM_EAT_1,
      MM_EAT_2,
      MM_SEND_YB_TO_PH_DB_S3_1,
      MM_SEND_YB_TO_PH_DB_S3_2,
      MM_RECV_Z_FROM_PH_DB_S1_1,
      MM_RECV_Z_FROM_PH_DB_S1_2,
      MM_RECV_S_FROM_PH_DB_S3_1,
      MM_RECV_S_FROM_PH_DB_S3_2,
      MM_FINISH_DOMAIN_TUPLE,
      MM_FINISH_QUERY,
      MM_NOOP;
    }

    private State state_ = State.MM_NEXT_DOMAIN_TUPLE;

    private void log(final CharSequence message) {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(message != null);
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      final StringBuilder s = new StringBuilder();
      globals_.logFile().timestamp(s);
      s.append("mergeMachine: ");
      s.append(message);
      s.append('\n');
      globals_.logFile().write(s.toString());
    }

    private final void setState(final State state) {
      //log("state: " + state.name());
      state_ = state;
    }

    final AtomicLong tickDepth = new AtomicLong(0);

    final Queue<Long> rowCountQueue1 =
        new LinkedBlockingQueue<Long>(10000);
    final Queue<Long> rowCountQueue2 =
        new LinkedBlockingQueue<Long>(10000);

    private long rowCount1_ = -1;
    private long rowCount2_ = -1;
    private long rowIndex1_ = -1;
    private long rowIndex2_ = -1;
    private boolean want1_;
    private boolean want2_;
    private boolean eat1_;
    private boolean eat2_;

    private boolean xaBytesBatchHave1_ = false;
    private boolean xaBytesBatchHave2_ = false;
    private final Queue<PoolEntry<byte[]>> xaBytesBatchQueue1_;
    private final Queue<PoolEntry<byte[]>> xaBytesBatchQueue2_;
    private PoolEntry<byte[]> xaBytesBatchEntry1_ = null;
    private PoolEntry<byte[]> xaBytesBatchEntry2_ = null;
    private byte[] xaBytesBatch1_ = null;
    private byte[] xaBytesBatch2_ = null;
    private int xaBytesBatchIndex1_ = -1;
    private int xaBytesBatchIndex2_ = -1;

    private boolean bBytesBatchHave1_ = false;
    private boolean bBytesBatchHave2_ = false;
    private final Queue<PoolEntry<byte[]>> bBytesBatchQueue1_;
    private final Queue<PoolEntry<byte[]>> bBytesBatchQueue2_;
    private PoolEntry<byte[]> bBytesBatchEntry1_ = null;
    private PoolEntry<byte[]> bBytesBatchEntry2_ = null;
    private byte[] bBytesBatch1_ = null;
    private byte[] bBytesBatch2_ = null;
    private int bBytesBatchIndex1_ = -1;
    private int bBytesBatchIndex2_ = -1;

    private PoolEntry<int[]> zIntEntry1_;
    private PoolEntry<long[]> zLongEntry1_;
    private PoolEntry<BigInteger[]> zBigEntry1_;
    private int[] zInt1_;
    private long[] zLong1_;
    private BigInteger[] zBig1_;

    private PoolEntry<int[]> zIntEntry2_;
    private PoolEntry<long[]> zLongEntry2_;
    private PoolEntry<BigInteger[]> zBigEntry2_;
    private int[] zInt2_;
    private long[] zLong2_;
    private BigInteger[] zBig2_;

    private PoolEntry<byte[]> sBytesEntry1_;
    private byte[] sBytes1_;

    private PoolEntry<byte[]> sBytesEntry2_;
    private byte[] sBytes2_;

    private int yInt_;
    private BigInteger yBig_;
    private final ConcurrentPool<byte[]> ybBytesBatchPool1_;
    private final ConcurrentPool<byte[]> ybBytesBatchPool2_;
    private PoolEntry<byte[]> ybBytesBatchEntry1_ = null;
    private PoolEntry<byte[]> ybBytesBatchEntry2_ = null;
    private byte[] ybBytesBatch1_ = null;
    private byte[] ybBytesBatch2_ = null;
    private int ybBytesBatchIndex1_ = -1;
    private int ybBytesBatchIndex2_ = -1;
    private final Queue<PoolEntry<byte[]>> ybBytesBatchQueue1_;
    private final Queue<PoolEntry<byte[]>> ybBytesBatchQueue2_;
    private final AtomicReference<ChannelPipeline> phDbS1Pipeline1_;
    private final AtomicReference<ChannelPipeline> phDbS1Pipeline2_;
    private final AtomicReference<ChannelPipeline> phDbS2Pipeline1_;
    private final AtomicReference<ChannelPipeline> phDbS2Pipeline2_;
    private final AtomicReference<ChannelPipeline> phDbS3Pipeline1_;
    private final AtomicReference<ChannelPipeline> phDbS3Pipeline2_;

    private int[] rowInt1_;
    private int[] rowInt2_;
    private long[] rowLong1_;
    private long[] rowLong2_;
    private BigInteger[] rowBig1_;
    private BigInteger[] rowBig2_;

    private FixedPointModContext zeroScaleFpmContext_;
    private FixedPointModContext[] fixedPointModContexts_;
    private BigDecimal[] rowDec_;

    private final List<List<Object>> tuples_;
    private final List<BigDecimal[]> result_;

    MergeMachine(
        final Globals globals,
        final SharedHandlerData shd,
        final int modulusInt,
        final long modulusLong,
        final BigInteger modulusBig,
        final int valueSize,
        final boolean valuesFitInt,
        final boolean valuesFitLong,
        final int aggCount1,
        final int aggCount2,
        final Queue<PoolEntry<byte[]>> xaBytesBatchQueue1,
        final Queue<PoolEntry<byte[]>> xaBytesBatchQueue2,
        final Queue<PoolEntry<byte[]>> bBytesBatchQueue1,
        final Queue<PoolEntry<byte[]>> bBytesBatchQueue2,
        final Queue<PoolEntry<byte[]>> ybBytesBatchQueue1,
        final Queue<PoolEntry<byte[]>> ybBytesBatchQueue2,
        final AtomicReference<ChannelPipeline> phDbS1Pipeline1,
        final AtomicReference<ChannelPipeline> phDbS1Pipeline2,
        final AtomicReference<ChannelPipeline> phDbS2Pipeline1,
        final AtomicReference<ChannelPipeline> phDbS2Pipeline2,
        final AtomicReference<ChannelPipeline> phDbS3Pipeline1,
        final AtomicReference<ChannelPipeline> phDbS3Pipeline2) {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(globals != null);
          SST_ASSERT(shd != null);
          SST_ASSERT(valueSize > 0);
          SST_ASSERT(aggCount1 >= 0);
          SST_ASSERT(aggCount2 >= 0);
          SST_ASSERT(xaBytesBatchQueue1 != null);
          SST_ASSERT(xaBytesBatchQueue2 != null);
          SST_ASSERT(xaBytesBatchQueue1 != xaBytesBatchQueue2);
          SST_ASSERT(bBytesBatchQueue1 != null);
          SST_ASSERT(bBytesBatchQueue2 != null);
          SST_ASSERT(bBytesBatchQueue1 != bBytesBatchQueue2);
          SST_ASSERT(ybBytesBatchQueue1 != null);
          SST_ASSERT(ybBytesBatchQueue2 != null);
          SST_ASSERT(ybBytesBatchQueue1 != ybBytesBatchQueue2);
          SST_ASSERT(phDbS1Pipeline1 != null);
          SST_ASSERT(phDbS1Pipeline2 != null);
          SST_ASSERT(phDbS1Pipeline1 != phDbS1Pipeline2);
          SST_ASSERT(phDbS2Pipeline1 != null);
          SST_ASSERT(phDbS2Pipeline2 != null);
          SST_ASSERT(phDbS2Pipeline1 != phDbS2Pipeline2);
          SST_ASSERT(phDbS3Pipeline1 != null);
          SST_ASSERT(phDbS3Pipeline2 != null);
          SST_ASSERT(phDbS3Pipeline1 != phDbS3Pipeline2);
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      globals_ = globals;
      config_ = globals_.config();
      lexicon_ = config_.lexicon();
      linkingColumnSize_ = lexicon_.common().linkingColumnSize();
      query_ = shd.query;
      domainIterator_ = new DomainIterator(config_, query_);
      tupleCount_ = domainIterator_.count();
      columnCount_ = query_.aggregates().size();
      shd_ = shd;
      swd_ = globals_.getSharedWebSocketData(shd_.queryId);
      modulusInt_ = modulusInt;
      modulusLong_ = modulusLong;
      modulusBig_ = modulusBig;
      valueSize_ = valueSize;
      valuesFitInt_ = valuesFitInt;
      valuesFitLong_ = valuesFitLong;
      aggCount1_ = aggCount1;
      aggCount2_ = aggCount2;
      xaBytesSize1_ = linkingColumnSize_ + aggCount1 * valueSize;
      xaBytesSize2_ = linkingColumnSize_ + aggCount2 * valueSize;
      xaBytesBatchQueue1_ = xaBytesBatchQueue1;
      xaBytesBatchQueue2_ = xaBytesBatchQueue2;
      bBytesBatchQueue1_ = bBytesBatchQueue1;
      bBytesBatchQueue2_ = bBytesBatchQueue2;
      ybBytesBatchPool1_ =
          new ConcurrentPool<byte[]>(new Supplier<byte[]>() {
            @Override
            public final byte[] get() {
              return new byte[100 * valueSize];
            }
          });
      ybBytesBatchPool2_ =
          new ConcurrentPool<byte[]>(new Supplier<byte[]>() {
            @Override
            public final byte[] get() {
              return new byte[100 * valueSize];
            }
          });
      ybBytesBatchQueue1_ = ybBytesBatchQueue1;
      ybBytesBatchQueue2_ = ybBytesBatchQueue2;
      phDbS1Pipeline1_ = phDbS1Pipeline1;
      phDbS1Pipeline2_ = phDbS1Pipeline2;
      phDbS2Pipeline1_ = phDbS2Pipeline1;
      phDbS2Pipeline2_ = phDbS2Pipeline2;
      phDbS3Pipeline1_ = phDbS3Pipeline1;
      phDbS3Pipeline2_ = phDbS3Pipeline2;
      if (valuesFitInt_) {
        rowInt1_ = new int[aggCount1];
        rowInt2_ = new int[aggCount2];
        rowLong1_ = null;
        rowLong2_ = null;
        rowBig1_ = null;
        rowBig2_ = null;
      } else if (valuesFitLong_) {
        rowInt1_ = null;
        rowInt2_ = null;
        rowLong1_ = new long[aggCount1];
        rowLong2_ = new long[aggCount2];
        rowBig1_ = null;
        rowBig2_ = null;
      } else {
        rowInt1_ = null;
        rowInt2_ = null;
        rowLong1_ = null;
        rowLong2_ = null;
        rowBig1_ = new BigInteger[aggCount1];
        rowBig2_ = new BigInteger[aggCount2];
      }

      zeroScaleFpmContext_ = new FixedPointModContext(shd_.modulus, 0);
      fixedPointModContexts_ =
          new FixedPointModContext[query_.aggregates().size()];
      for (int i = 0; i < fixedPointModContexts_.length; ++i) {
        fixedPointModContexts_[i] = new FixedPointModContext(
            shd_.modulus,
            query_.aggregates().get(i).column().scale());
      }

      rowDec_ = new BigDecimal[aggCount1 + aggCount2];

      tuples_ = new ArrayList<List<Object>>(tupleCount_);
      result_ = swd_.result;
      for (int i = 0; i < tupleCount_; ++i) {
        tuples_.add(new ArrayList<Object>(query_.groupBys().size()));
      }
    }

    private final void tick2() {
      while (true) {
        switch (state_) {

          case MM_NEXT_DOMAIN_TUPLE: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(globals_ != null);
                SST_ASSERT(valueSize_ > 0);
                SST_ASSERT(xaBytesSize1_ > 0);
                SST_ASSERT(xaBytesSize2_ > 0);
                SST_ASSERT(rowCount1_ == -1);
                SST_ASSERT(rowCount2_ == -1);
                SST_ASSERT(rowIndex1_ == -1);
                SST_ASSERT(rowIndex2_ == -1);
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            if (valuesFitInt_) {
              for (int i = 0; i < aggCount1_; ++i) {
                rowInt1_[i] = 0;
              }
              for (int i = 0; i < aggCount2_; ++i) {
                rowInt2_[i] = 0;
              }
            } else if (valuesFitLong_) {
              for (int i = 0; i < aggCount1_; ++i) {
                rowLong1_[i] = 0;
              }
              for (int i = 0; i < aggCount2_; ++i) {
                rowLong2_[i] = 0;
              }
            } else {
              for (int i = 0; i < aggCount1_; ++i) {
                rowBig1_[i] = BigInteger.ZERO;
              }
              for (int i = 0; i < aggCount2_; ++i) {
                rowBig2_[i] = BigInteger.ZERO;
              }
            }
            if (xaBytesBatch1_ != null) {
              xaBytesBatchEntry1_.release();
              xaBytesBatchEntry1_ = null;
              xaBytesBatch1_ = null;
              xaBytesBatchIndex1_ = -1;
            }
            if (xaBytesBatch2_ != null) {
              xaBytesBatchEntry2_.release();
              xaBytesBatchEntry2_ = null;
              xaBytesBatch2_ = null;
              xaBytesBatchIndex2_ = -1;
            }
            if (bBytesBatch1_ != null) {
              bBytesBatchEntry1_.release();
              bBytesBatchEntry1_ = null;
              bBytesBatch1_ = null;
              bBytesBatchIndex1_ = -1;
            }
            if (bBytesBatch2_ != null) {
              bBytesBatchEntry2_.release();
              bBytesBatchEntry2_ = null;
              bBytesBatch2_ = null;
              bBytesBatchIndex2_ = -1;
            }
            if (++tupleIndex_ < tupleCount_) {
              domainIterator_.nextAll(tuples_.get(tupleIndex_));
              progress_ = swd_.progress.get(tupleIndex_);
              setState(State.MM_RECV_ROW_COUNT_1);
            } else {
              setState(State.MM_FINISH_QUERY);
            }
          } break;

          case MM_RECV_ROW_COUNT_1: {
            final Long x = rowCountQueue1.poll();
            if (x == null) {
              return;
            }
            fireTick(phDbS1Pipeline1_.get());
            rowCount1_ = x;
            rowIndex1_ = 0;
            progress_.rowCount1.set(rowCount1_);
            setState(State.MM_RECV_ROW_COUNT_2);
          } break;

          case MM_RECV_ROW_COUNT_2: {
            final Long x = rowCountQueue2.poll();
            if (x == null) {
              return;
            }
            fireTick(phDbS1Pipeline2_.get());
            rowCount2_ = x;
            rowIndex2_ = 0;
            progress_.rowCount2.set(rowCount2_);
            if (rowCount1_ == 0 || rowCount2_ == 0) {
              progress_.rowIndex1.set(rowCount1_);
              progress_.rowIndex2.set(rowCount2_);
              setState(State.MM_FINISH_DOMAIN_TUPLE);
            } else {
              setState(State.MM_NEXT);
            }
          } break;

          case MM_NEXT: {
            if (rowIndex1_ == rowCount1_ && rowIndex2_ == rowCount2_) {
              setState(State.MM_RECV_Z_FROM_PH_DB_S1_1);
            } else {
              if (rowIndex1_ == rowCount1_) {
                want1_ = false;
                want2_ = true;
              } else if (rowIndex2_ == rowCount2_) {
                want1_ = true;
                want2_ = false;
              } else {
                want1_ = true;
                want2_ = true;
              }
              setState(State.MM_RECV_XA_BATCH_1);
            }
          } break;

          case MM_RECV_XA_BATCH_1: {
            if (want1_ && !xaBytesBatchHave1_) {
              if (xaBytesBatch1_ == null
                  || xaBytesBatchIndex1_
                         >= xaBytesBatch1_.length - xaBytesSize1_) {
                if (!SST_NDEBUG) {
                  SST_ASSERT(xaBytesBatch1_ == null
                             || xaBytesBatchIndex1_
                                    == xaBytesBatch1_.length
                                           - xaBytesSize1_);
                }
                if (xaBytesBatchEntry1_ != null) {
                  xaBytesBatchEntry1_.release();
                  xaBytesBatchEntry1_ = null;
                  xaBytesBatch1_ = null;
                }
                xaBytesBatchEntry1_ = xaBytesBatchQueue1_.poll();
                if (xaBytesBatchEntry1_ == null) {
                  return;
                }
                fireTick(phDbS1Pipeline1_.get());
                xaBytesBatch1_ = xaBytesBatchEntry1_.object();
                xaBytesBatchIndex1_ = 0;
              } else {
                xaBytesBatchIndex1_ += xaBytesSize1_;
              }
              xaBytesBatchHave1_ = true;
            }
            setState(State.MM_RECV_XA_BATCH_2);
          } break;

          case MM_RECV_XA_BATCH_2: {
            if (want2_ && !xaBytesBatchHave2_) {
              if (xaBytesBatch2_ == null
                  || xaBytesBatchIndex2_
                         >= xaBytesBatch2_.length - xaBytesSize2_) {
                if (!SST_NDEBUG) {
                  SST_ASSERT(xaBytesBatch2_ == null
                             || xaBytesBatchIndex2_
                                    == xaBytesBatch2_.length
                                           - xaBytesSize2_);
                }
                if (xaBytesBatchEntry2_ != null) {
                  xaBytesBatchEntry2_.release();
                  xaBytesBatchEntry2_ = null;
                  xaBytesBatch2_ = null;
                }
                xaBytesBatchEntry2_ = xaBytesBatchQueue2_.poll();
                if (xaBytesBatchEntry2_ == null) {
                  return;
                }
                fireTick(phDbS1Pipeline2_.get());
                xaBytesBatch2_ = xaBytesBatchEntry2_.object();
                xaBytesBatchIndex2_ = 0;
              } else {
                xaBytesBatchIndex2_ += xaBytesSize2_;
              }
              xaBytesBatchHave2_ = true;
            }
            setState(State.MM_COMPARE);
          } break;

          case MM_COMPARE: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(want1_ || want2_);
                if (want1_) {
                  SST_ASSERT(xaBytesBatchHave1_);
                }
                if (want2_) {
                  SST_ASSERT(xaBytesBatchHave2_);
                }
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            yInt_ = 0;
            yBig_ = BigInteger.ZERO;
            if (want1_ && want2_) {
              final int cmp = Memory.cmp(xaBytesBatch1_,
                                         xaBytesBatchIndex1_,
                                         xaBytesBatch2_,
                                         xaBytesBatchIndex2_,
                                         linkingColumnSize_,
                                         ByteOrder.BIG_ENDIAN);
              if (cmp < 0) {
                eat1_ = true;
                eat2_ = false;
              } else if (cmp > 0) {
                eat1_ = false;
                eat2_ = true;
              } else {
                eat1_ = true;
                eat2_ = true;
                yInt_ = 1;
                yBig_ = BigInteger.ONE;
              }
            } else if (want1_) {
              eat1_ = true;
              eat2_ = false;
            } else if (want2_) {
              eat1_ = false;
              eat2_ = true;
            } else if (!SST_NDEBUG) {
              throw new ImpossibleException();
            }
            setState(State.MM_RECV_B_BATCH_1);
          } break;

          case MM_RECV_B_BATCH_1: {
            if (eat1_ && !bBytesBatchHave1_) {
              if (bBytesBatch1_ == null
                  || bBytesBatchIndex1_
                         >= bBytesBatch1_.length - valueSize_) {
                SST_ASSERT(bBytesBatch1_ == null
                           || bBytesBatchIndex1_
                                  == bBytesBatch1_.length - valueSize_);
                if (bBytesBatchEntry1_ != null) {
                  bBytesBatchEntry1_.release();
                  bBytesBatchEntry1_ = null;
                  bBytesBatch1_ = null;
                }
                bBytesBatchEntry1_ = bBytesBatchQueue1_.poll();
                if (bBytesBatchEntry1_ == null) {
                  return;
                }
                // NB: This tick goes to the PH-DB-S2 handler for the
                // other DB, not this DB.
                fireTick(phDbS2Pipeline2_.get());
                bBytesBatch1_ = bBytesBatchEntry1_.object();
                bBytesBatchIndex1_ = 0;
              } else {
                bBytesBatchIndex1_ += valueSize_;
              }
              bBytesBatchHave1_ = true;
            }
            setState(State.MM_RECV_B_BATCH_2);
          } break;

          case MM_RECV_B_BATCH_2: {
            if (eat2_ && !bBytesBatchHave2_) {
              if (bBytesBatch2_ == null
                  || bBytesBatchIndex2_
                         >= bBytesBatch2_.length - valueSize_) {
                SST_ASSERT(bBytesBatch2_ == null
                           || bBytesBatchIndex2_
                                  == bBytesBatch2_.length - valueSize_);
                if (bBytesBatchEntry2_ != null) {
                  bBytesBatchEntry2_.release();
                  bBytesBatchEntry2_ = null;
                  bBytesBatch2_ = null;
                }
                bBytesBatchEntry2_ = bBytesBatchQueue2_.poll();
                if (bBytesBatchEntry2_ == null) {
                  return;
                }
                // NB: This tick goes to the PH-DB-S2 handler for the
                // other DB, not this DB.
                fireTick(phDbS2Pipeline1_.get());
                bBytesBatch2_ = bBytesBatchEntry2_.object();
                bBytesBatchIndex2_ = 0;
              } else {
                bBytesBatchIndex2_ += valueSize_;
              }
              bBytesBatchHave2_ = true;
            }
            setState(State.MM_EAT_1);
          } break;

          case MM_EAT_1: {
            if (eat1_) {
              if (ybBytesBatch1_ == null) {
                ybBytesBatchEntry1_ = ybBytesBatchPool1_.acquire();
                ybBytesBatch1_ = ybBytesBatchEntry1_.object();
                ybBytesBatchIndex1_ = 0;
              }
              if (valuesFitInt_) {
                final int m = modulusInt_;
                final int b = Rep.fromBytes(bBytesBatch1_,
                                            bBytesBatchIndex1_,
                                            valueSize_,
                                            (Integer)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                if (m == 0 || m == Integer.MIN_VALUE
                    || Arith.isPowerOfTwo(m)) {
                  // m is a power of two in [1, 2^32]
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt1_[i] += xai * b;
                  }
                  final int yb = yInt_ - b;
                  Rep.toBytes(yb,
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else if (m > 0) {
                  // m is a non-power-of-two in [1, 2^31]
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt1_[i] =
                        (int)(((long)rowInt1_[i] + (long)xai * (long)b)
                              % (long)m);
                  }
                  int yb = yInt_ - b;
                  if (yb < 0) {
                    yb += m;
                  }
                  Rep.toBytes(yb,
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else {
                  // m is a non-power-of-two in [2^31, 2^32]
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt1_[i] = (int)Arith.unsignedMod(
                        Arith.toUnsignedLong(rowInt1_[i])
                            + Arith.toUnsignedLong(xai)
                                  * Arith.toUnsignedLong(b),
                        Arith.toUnsignedLong(m));
                  }
                  long yb = (long)yInt_ - Arith.toUnsignedLong(b);
                  if (yb < 0) {
                    yb += Arith.toUnsignedLong(m);
                  }
                  Rep.toBytes((int)yb,
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                }
              } else if (valuesFitLong_) {
                final long m = modulusLong_;
                final long b = Rep.fromBytes(bBytesBatch1_,
                                             bBytesBatchIndex1_,
                                             valueSize_,
                                             (Long)null,
                                             IntegerRep.PURE_UNSIGNED,
                                             ByteOrder.BIG_ENDIAN,
                                             false);
                if (m == 0 || m == Long.MIN_VALUE
                    || Arith.isPowerOfTwo(m)) {
                  // m is a power of two in [2^33, 2^64]
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final long xai =
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong1_[i] += xai * b;
                  }
                  final long yb = (long)yInt_ - b;
                  Rep.toBytes(yb,
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else if (m > 0) {
                  // m is a non-power-of-two in [2^32, 2^63]
                  final BigInteger bBig = BigInteger.valueOf(b);
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final BigInteger xai =
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (BigInteger)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong1_[i] = BigInteger.valueOf(rowLong1_[i])
                                       .add(xai.multiply(bBig))
                                       .remainder(modulusBig_)
                                       .longValue();
                  }
                  long yb = (long)yInt_ - b;
                  if (yb < 0) {
                    yb += m;
                  }
                  Rep.toBytes(yb,
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else {
                  // m is a non-power-of-two in [2^63, 2^64]
                  final BigInteger bBig = Arith.toUnsignedBig(b);
                  for (int i = 0,
                           j = xaBytesBatchIndex1_ + linkingColumnSize_;
                       i < aggCount1_;
                       ++i, j += valueSize_) {
                    final BigInteger xai = Arith.toUnsignedBig(
                        Rep.fromBytes(xaBytesBatch1_,
                                      j,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false));
                    rowLong1_[i] = Arith.toUnsignedBig(rowLong1_[i])
                                       .add(xai.multiply(bBig))
                                       .remainder(modulusBig_)
                                       .longValue();
                  }
                  BigInteger yb = yBig_.subtract(bBig);
                  if (yb.signum() < 0) {
                    yb = yb.add(modulusBig_);
                  }
                  Rep.toBytes(yb.longValue(),
                              ybBytesBatch1_,
                              ybBytesBatchIndex1_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                }
              } else {
                final BigInteger m = modulusBig_;
                final BigInteger b =
                    Rep.fromBytes(bBytesBatch1_,
                                  bBytesBatchIndex1_,
                                  valueSize_,
                                  (BigInteger)null,
                                  IntegerRep.PURE_UNSIGNED,
                                  ByteOrder.BIG_ENDIAN,
                                  false);
                for (int i = 0,
                         j = xaBytesBatchIndex1_ + linkingColumnSize_;
                     i < aggCount1_;
                     ++i, j += valueSize_) {
                  final BigInteger xai =
                      Rep.fromBytes(xaBytesBatch1_,
                                    j,
                                    valueSize_,
                                    (BigInteger)null,
                                    IntegerRep.PURE_UNSIGNED,
                                    ByteOrder.BIG_ENDIAN,
                                    false);
                  rowBig1_[i] =
                      rowBig1_[i].add(xai.multiply(b)).remainder(m);
                }
                BigInteger yb = yBig_.subtract(b);
                if (yb.signum() < 0) {
                  yb = yb.add(m);
                }
                Rep.toBytes(yb,
                            ybBytesBatch1_,
                            ybBytesBatchIndex1_,
                            valueSize_,
                            IntegerRep.TWOS_COMPLEMENT,
                            ByteOrder.BIG_ENDIAN,
                            false);
              }
              ++rowIndex1_;
              if (rowIndex1_ % 8192 == 0 || rowIndex1_ == rowCount1_) {
                progress_.rowIndex1.set(rowIndex1_);
              }
              ybBytesBatchIndex1_ += valueSize_;
              xaBytesBatchHave1_ = false;
              bBytesBatchHave1_ = false;
            }
            setState(State.MM_EAT_2);
          } break;

          case MM_EAT_2: {
            if (eat2_) {
              if (ybBytesBatch2_ == null) {
                ybBytesBatchEntry2_ = ybBytesBatchPool2_.acquire();
                ybBytesBatch2_ = ybBytesBatchEntry2_.object();
                ybBytesBatchIndex2_ = 0;
              }
              if (valuesFitInt_) {
                final int m = modulusInt_;
                final int b = Rep.fromBytes(bBytesBatch2_,
                                            bBytesBatchIndex2_,
                                            valueSize_,
                                            (Integer)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                if (m == 0 || m == Integer.MIN_VALUE
                    || Arith.isPowerOfTwo(m)) {
                  // m is a power of two in [1, 2^32]
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt2_[i] += xai * b;
                  }
                  final int yb = yInt_ - b;
                  Rep.toBytes(yb,
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else if (m > 0) {
                  // m is a non-power-of-two in [1, 2^31]
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt2_[i] =
                        (int)(((long)rowInt2_[i] + (long)xai * (long)b)
                              % (long)m);
                  }
                  int yb = yInt_ - b;
                  if (yb < 0) {
                    yb += m;
                  }
                  Rep.toBytes(yb,
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else {
                  // m is a non-power-of-two in [2^31, 2^32]
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final int xai =
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt2_[i] = (int)Arith.unsignedMod(
                        Arith.toUnsignedLong(rowInt2_[i])
                            + Arith.toUnsignedLong(xai)
                                  * Arith.toUnsignedLong(b),
                        Arith.toUnsignedLong(m));
                  }
                  long yb = (long)yInt_ - Arith.toUnsignedLong(b);
                  if (yb < 0) {
                    yb += Arith.toUnsignedLong(m);
                  }
                  Rep.toBytes((int)yb,
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                }
              } else if (valuesFitLong_) {
                final long m = modulusLong_;
                final long b = Rep.fromBytes(bBytesBatch2_,
                                             bBytesBatchIndex2_,
                                             valueSize_,
                                             (Long)null,
                                             IntegerRep.PURE_UNSIGNED,
                                             ByteOrder.BIG_ENDIAN,
                                             false);
                if (m == 0 || m == Long.MIN_VALUE
                    || Arith.isPowerOfTwo(m)) {
                  // m is a power of two in [2^33, 2^64]
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final long xai =
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong2_[i] += xai * b;
                  }
                  final long yb = (long)yInt_ - b;
                  Rep.toBytes(yb,
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else if (m > 0) {
                  // m is a non-power-of-two in [2^32, 2^63]
                  final BigInteger bBig = BigInteger.valueOf(b);
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final BigInteger xai =
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (BigInteger)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong2_[i] = BigInteger.valueOf(rowLong2_[i])
                                       .add(xai.multiply(bBig))
                                       .remainder(modulusBig_)
                                       .longValue();
                  }
                  long yb = (long)yInt_ - b;
                  if (yb < 0) {
                    yb += m;
                  }
                  Rep.toBytes(yb,
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                } else {
                  // m is a non-power-of-two in [2^63, 2^64]
                  final BigInteger bBig = Arith.toUnsignedBig(b);
                  for (int i = 0,
                           j = xaBytesBatchIndex2_ + linkingColumnSize_;
                       i < aggCount2_;
                       ++i, j += valueSize_) {
                    final BigInteger xai = Arith.toUnsignedBig(
                        Rep.fromBytes(xaBytesBatch2_,
                                      j,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false));
                    rowLong2_[i] = Arith.toUnsignedBig(rowLong2_[i])
                                       .add(xai.multiply(bBig))
                                       .remainder(modulusBig_)
                                       .longValue();
                  }
                  BigInteger yb = yBig_.subtract(bBig);
                  if (yb.signum() < 0) {
                    yb = yb.add(modulusBig_);
                  }
                  Rep.toBytes(yb.longValue(),
                              ybBytesBatch2_,
                              ybBytesBatchIndex2_,
                              valueSize_,
                              IntegerRep.TWOS_COMPLEMENT,
                              ByteOrder.BIG_ENDIAN,
                              false);
                }
              } else {
                final BigInteger m = modulusBig_;
                final BigInteger b =
                    Rep.fromBytes(bBytesBatch2_,
                                  bBytesBatchIndex2_,
                                  valueSize_,
                                  (BigInteger)null,
                                  IntegerRep.PURE_UNSIGNED,
                                  ByteOrder.BIG_ENDIAN,
                                  false);
                for (int i = 0,
                         j = xaBytesBatchIndex2_ + linkingColumnSize_;
                     i < aggCount2_;
                     ++i, j += valueSize_) {
                  final BigInteger xai =
                      Rep.fromBytes(xaBytesBatch2_,
                                    j,
                                    valueSize_,
                                    (BigInteger)null,
                                    IntegerRep.PURE_UNSIGNED,
                                    ByteOrder.BIG_ENDIAN,
                                    false);
                  rowBig2_[i] =
                      rowBig2_[i].add(xai.multiply(b)).remainder(m);
                }
                BigInteger yb = yBig_.subtract(b);
                if (yb.signum() < 0) {
                  yb = yb.add(m);
                }
                Rep.toBytes(yb,
                            ybBytesBatch2_,
                            ybBytesBatchIndex2_,
                            valueSize_,
                            IntegerRep.TWOS_COMPLEMENT,
                            ByteOrder.BIG_ENDIAN,
                            false);
              }
              ++rowIndex2_;
              if (rowIndex2_ % 8192 == 0 || rowIndex2_ == rowCount2_) {
                progress_.rowIndex2.set(rowIndex2_);
              }
              ybBytesBatchIndex2_ += valueSize_;
              xaBytesBatchHave2_ = false;
              bBytesBatchHave2_ = false;
            }
            setState(State.MM_SEND_YB_TO_PH_DB_S3_1);
          } break;

          case MM_SEND_YB_TO_PH_DB_S3_1: {
            if (ybBytesBatch1_ != null
                && (ybBytesBatchIndex1_ == ybBytesBatch1_.length
                    || rowIndex1_ == rowCount1_)) {
              if (!ybBytesBatchQueue1_.offer(ybBytesBatchEntry1_)) {
                return;
              }
              fireTick(phDbS3Pipeline1_.get());
              ybBytesBatch1_ = null;
              ybBytesBatchIndex1_ = -1;
            }
            setState(State.MM_SEND_YB_TO_PH_DB_S3_2);
          } break;

          case MM_SEND_YB_TO_PH_DB_S3_2: {
            if (ybBytesBatch2_ != null
                && (ybBytesBatchIndex2_ == ybBytesBatch2_.length
                    || rowIndex2_ == rowCount2_)) {
              if (!ybBytesBatchQueue2_.offer(ybBytesBatchEntry2_)) {
                return;
              }
              fireTick(phDbS3Pipeline2_.get());
              ybBytesBatch2_ = null;
              ybBytesBatchIndex2_ = -1;
            }
            setState(State.MM_NEXT);
          } break;

          case MM_RECV_Z_FROM_PH_DB_S1_1: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(zIntEntry1_ == null);
                SST_ASSERT(zLongEntry1_ == null);
                SST_ASSERT(zBigEntry1_ == null);
                SST_ASSERT(zInt1_ == null);
                SST_ASSERT(zLong1_ == null);
                SST_ASSERT(zBig1_ == null);
                SST_ASSERT(phDbS1Pipeline1_ != null);
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            if (valuesFitInt_) {
              zIntEntry1_ = shd_.zIntQueue1.poll();
              if (zIntEntry1_ == null) {
                return;
              }
              zInt1_ = zIntEntry1_.object();
              //log("zInt1_ = " + Rep.toString(zInt1_));
            } else if (valuesFitLong_) {
              zLongEntry1_ = shd_.zLongQueue1.poll();
              if (zLongEntry1_ == null) {
                return;
              }
              zLong1_ = zLongEntry1_.object();
              //log("zLong1_ = " + Rep.toString(zLong1_));
            } else {
              zBigEntry1_ = shd_.zBigQueue1.poll();
              if (zBigEntry1_ == null) {
                return;
              }
              zBig1_ = zBigEntry1_.object();
              //log("zBig1_ = " + Rep.toString(zBig1_));
            }
            fireTick(phDbS1Pipeline1_.get());
            setState(State.MM_RECV_Z_FROM_PH_DB_S1_2);
          } break;

          case MM_RECV_Z_FROM_PH_DB_S1_2: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(zIntEntry2_ == null);
                SST_ASSERT(zLongEntry2_ == null);
                SST_ASSERT(zBigEntry2_ == null);
                SST_ASSERT(zInt2_ == null);
                SST_ASSERT(zLong2_ == null);
                SST_ASSERT(zBig2_ == null);
                SST_ASSERT(phDbS1Pipeline2_ != null);
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            if (valuesFitInt_) {
              zIntEntry2_ = shd_.zIntQueue2.poll();
              if (zIntEntry2_ == null) {
                return;
              }
              zInt2_ = zIntEntry2_.object();
              //log("zInt2_ = " + Rep.toString(zInt2_));
            } else if (valuesFitLong_) {
              zLongEntry2_ = shd_.zLongQueue2.poll();
              if (zLongEntry2_ == null) {
                return;
              }
              zLong2_ = zLongEntry2_.object();
              //log("zLong2_ = " + Rep.toString(zLong2_));
            } else {
              zBigEntry2_ = shd_.zBigQueue2.poll();
              if (zBigEntry2_ == null) {
                return;
              }
              zBig2_ = zBigEntry2_.object();
              //log("zBig2_ = " + Rep.toString(zBig2_));
            }
            fireTick(phDbS1Pipeline2_.get());
            setState(State.MM_RECV_S_FROM_PH_DB_S3_1);
          } break;

          case MM_RECV_S_FROM_PH_DB_S3_1: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(sBytesEntry1_ == null);
                SST_ASSERT(sBytes1_ == null);
                SST_ASSERT(sBytesEntry2_ == null);
                SST_ASSERT(sBytes2_ == null);
                SST_ASSERT(phDbS3Pipeline1_ != null);
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            sBytesEntry1_ = shd_.sBytesQueue1.poll();
            if (sBytesEntry1_ == null) {
              return;
            }
            fireTick(phDbS3Pipeline1_.get());
            sBytes1_ = sBytesEntry1_.object();
            setState(State.MM_RECV_S_FROM_PH_DB_S3_2);
          } break;

          case MM_RECV_S_FROM_PH_DB_S3_2: {
            if (!SST_NDEBUG) {
              try {
                SST_ASSERT(sBytesEntry1_ != null);
                SST_ASSERT(sBytes1_ != null);
                SST_ASSERT(sBytesEntry2_ == null);
                SST_ASSERT(sBytes2_ == null);
                SST_ASSERT(phDbS3Pipeline2_ != null);
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            sBytesEntry2_ = shd_.sBytesQueue2.poll();
            if (sBytesEntry2_ == null) {
              return;
            }
            fireTick(phDbS3Pipeline2_.get());
            sBytes2_ = sBytesEntry2_.object();
            setState(State.MM_FINISH_DOMAIN_TUPLE);
          } break;

          case MM_FINISH_DOMAIN_TUPLE: {
            if (!SST_NDEBUG) {
              try {
                if (rowCount1_ == 0 || rowCount2_ == 0) {
                  SST_ASSERT(sBytesEntry1_ == null);
                  SST_ASSERT(sBytes1_ == null);
                  SST_ASSERT(sBytesEntry2_ == null);
                  SST_ASSERT(sBytes2_ == null);
                } else {
                  SST_ASSERT(sBytesEntry1_ != null);
                  SST_ASSERT(sBytes1_ != null);
                  SST_ASSERT(sBytesEntry2_ != null);
                  SST_ASSERT(sBytes2_ != null);
                }
              } catch (final Throwable e) {
                SST_ASSERT(e);
              }
            }
            if (rowCount1_ == 0 || rowCount2_ == 0) {
              int i = 0;
              final int an = query_.aggregates().size();
              for (int ai = 0; ai < an; ++ai) {
                final Aggregate agg = query_.aggregates().get(ai);
                final int n = agg.aggCount();
                for (int j = 0; j < n; ++j) {
                  rowDec_[i++] = BigDecimal.ZERO;
                }
              }
            } else if (valuesFitInt_) {
              if (modulusInt_ == 0 || modulusInt_ == Integer.MIN_VALUE
                  || Arith.isPowerOfTwo(modulusInt_)) {
                // m is a power of two in [1, 2^32]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt1_[i] -= zInt1_[i];
                    rowInt1_[i] += s;
                    rowInt1_[i] &= modulusInt_ - 1;
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt2_[i] -= zInt2_[i];
                    rowInt2_[i] += s;
                    rowInt2_[i] &= modulusInt_ - 1;
                    sIndex += valueSize_;
                  }
                }
              } else if (modulusInt_ > 0) {
                // m is a non-power-of-two in [1, 2^31]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt1_[i] -= zInt1_[i];
                    if (rowInt1_[i] < 0) {
                      rowInt1_[i] += modulusInt_;
                    }
                    if (rowInt1_[i] >= modulusInt_ - s) {
                      rowInt1_[i] -= modulusInt_;
                    }
                    rowInt1_[i] += s;
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowInt2_[i] -= zInt2_[i];
                    if (rowInt2_[i] < 0) {
                      rowInt2_[i] += modulusInt_;
                    }
                    if (rowInt2_[i] >= modulusInt_ - s) {
                      rowInt2_[i] -= modulusInt_;
                    }
                    rowInt2_[i] += s;
                    sIndex += valueSize_;
                  }
                }
              } else {
                // m is a non-power-of-two in [2^31, 2^32]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    final int zMinusS;
                    if (Arith.unsignedCmp(zInt1_[i], s) >= 0) {
                      zMinusS = zInt1_[i] - s;
                    } else {
                      zMinusS = modulusInt_ - (s - zInt1_[i]);
                    }
                    if (Arith.unsignedCmp(rowInt1_[i], zMinusS) >= 0) {
                      rowInt1_[i] -= zMinusS;
                    } else {
                      rowInt1_[i] =
                          modulusInt_ - (zMinusS - rowInt1_[i]);
                    }
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final int s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Integer)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    final int zMinusS;
                    if (Arith.unsignedCmp(zInt2_[i], s) >= 0) {
                      zMinusS = zInt2_[i] - s;
                    } else {
                      zMinusS = modulusInt_ - (s - zInt2_[i]);
                    }
                    if (Arith.unsignedCmp(rowInt2_[i], zMinusS) >= 0) {
                      rowInt2_[i] -= zMinusS;
                    } else {
                      rowInt2_[i] =
                          modulusInt_ - (zMinusS - rowInt2_[i]);
                    }
                    sIndex += valueSize_;
                  }
                }
              }
              {
                int i = 0;
                int i1 = 0;
                int i2 = 0;
                final int an = query_.aggregates().size();
                for (int ai = 0; ai < an; ++ai) {
                  final Aggregate agg = query_.aggregates().get(ai);
                  final int n = agg.aggCount();
                  for (int j = 0; j < n; ++j) {
                    final FixedPointModContext fp =
                        agg.shouldScale(j) ?
                            fixedPointModContexts_[ai] :
                            zeroScaleFpmContext_;
                    if (agg.db() == Party.DB1) {
                      rowDec_[i++] = fp.decode(rowInt1_[i1++],
                                               (BigDecimal)null,
                                               true);
                    } else {
                      rowDec_[i++] = fp.decode(rowInt2_[i2++],
                                               (BigDecimal)null,
                                               true);
                    }
                  }
                }
              }
            } else if (valuesFitLong_) {
              if (modulusLong_ == 0 || modulusLong_ == Long.MIN_VALUE
                  || Arith.isPowerOfTwo(modulusLong_)) {
                // m is a power of two in [2^33, 2^64]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong1_[i] -= zLong1_[i];
                    rowLong1_[i] += s;
                    rowLong1_[i] &= modulusLong_ - 1;
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong2_[i] -= zLong2_[i];
                    rowLong2_[i] += s;
                    rowLong2_[i] &= modulusLong_ - 1;
                    sIndex += valueSize_;
                  }
                }
              } else if (modulusLong_ > 0) {
                // m is a non-power-of-two in [2^32, 2^63]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong1_[i] -= zLong1_[i];
                    if (rowLong1_[i] < 0) {
                      rowLong1_[i] += modulusLong_;
                    }
                    if (rowLong1_[i] >= modulusLong_ - s) {
                      rowLong1_[i] -= modulusLong_;
                    }
                    rowLong1_[i] += s;
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    rowLong2_[i] -= zLong2_[i];
                    if (rowLong2_[i] < 0) {
                      rowLong2_[i] += modulusLong_;
                    }
                    if (rowLong2_[i] >= modulusLong_ - s) {
                      rowLong2_[i] -= modulusLong_;
                    }
                    rowLong2_[i] += s;
                    sIndex += valueSize_;
                  }
                }
              } else {
                // m is a non-power-of-two in [2^63, 2^64]
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount1_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes1_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    final long zMinusS;
                    if (Arith.unsignedCmp(zLong1_[i], s) >= 0) {
                      zMinusS = zLong1_[i] - s;
                    } else {
                      zMinusS = modulusLong_ - (s - zLong1_[i]);
                    }
                    if (Arith.unsignedCmp(rowLong1_[i], zMinusS) >= 0) {
                      rowLong1_[i] -= zMinusS;
                    } else {
                      rowLong1_[i] =
                          modulusLong_ - (zMinusS - rowLong1_[i]);
                    }
                    sIndex += valueSize_;
                  }
                }
                {
                  int sIndex = 0;
                  for (int i = 0; i < aggCount2_; ++i) {
                    final long s =
                        Rep.fromBytes(sBytes2_,
                                      sIndex,
                                      valueSize_,
                                      (Long)null,
                                      IntegerRep.PURE_UNSIGNED,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                    final long zMinusS;
                    if (Arith.unsignedCmp(zLong2_[i], s) >= 0) {
                      zMinusS = zLong2_[i] - s;
                    } else {
                      zMinusS = modulusLong_ - (s - zLong2_[i]);
                    }
                    if (Arith.unsignedCmp(rowLong2_[i], zMinusS) >= 0) {
                      rowLong2_[i] -= zMinusS;
                    } else {
                      rowLong2_[i] =
                          modulusLong_ - (zMinusS - rowLong2_[i]);
                    }
                    sIndex += valueSize_;
                  }
                }
              }
              {
                int i = 0;
                int i1 = 0;
                int i2 = 0;
                final int an = query_.aggregates().size();
                for (int ai = 0; ai < an; ++ai) {
                  final Aggregate agg = query_.aggregates().get(ai);
                  final int n = agg.aggCount();
                  for (int j = 0; j < n; ++j) {
                    final FixedPointModContext fp =
                        agg.shouldScale(j) ?
                            fixedPointModContexts_[ai] :
                            zeroScaleFpmContext_;
                    if (agg.db() == Party.DB1) {
                      rowDec_[i++] = fp.decode(rowLong1_[i1++],
                                               (BigDecimal)null,
                                               true);
                    } else {
                      rowDec_[i++] = fp.decode(rowLong2_[i2++],
                                               (BigDecimal)null,
                                               true);
                    }
                  }
                }
              }
            } else {
              // m is anything in (2^64, inf)
              {
                int sIndex = 0;
                for (int i = 0; i < aggCount1_; ++i) {
                  final BigInteger s =
                      Rep.fromBytes(sBytes1_,
                                    sIndex,
                                    valueSize_,
                                    (BigInteger)null,
                                    IntegerRep.PURE_UNSIGNED,
                                    ByteOrder.BIG_ENDIAN,
                                    false);
                  if (rowBig1_[i].compareTo(zBig1_[i]) >= 0) {
                    rowBig1_[i] = rowBig1_[i].subtract(zBig1_[i]);
                  } else {
                    rowBig1_[i] = rowBig1_[i].subtract(zBig1_[i]).add(
                        modulusBig_);
                  }
                  rowBig1_[i] = rowBig1_[i].add(s);
                  if (rowBig1_[i].compareTo(modulusBig_) >= 0) {
                    rowBig1_[i] = rowBig1_[i].subtract(modulusBig_);
                  }
                  sIndex += valueSize_;
                }
              }
              {
                int sIndex = 0;
                for (int i = 0; i < aggCount2_; ++i) {
                  final BigInteger s =
                      Rep.fromBytes(sBytes2_,
                                    sIndex,
                                    valueSize_,
                                    (BigInteger)null,
                                    IntegerRep.PURE_UNSIGNED,
                                    ByteOrder.BIG_ENDIAN,
                                    false);
                  if (rowBig2_[i].compareTo(zBig2_[i]) >= 0) {
                    rowBig2_[i] = rowBig2_[i].subtract(zBig2_[i]);
                  } else {
                    rowBig2_[i] = rowBig2_[i].subtract(zBig2_[i]).add(
                        modulusBig_);
                  }
                  rowBig2_[i] = rowBig2_[i].add(s);
                  if (rowBig2_[i].compareTo(modulusBig_) >= 0) {
                    rowBig2_[i] = rowBig2_[i].subtract(modulusBig_);
                  }
                  sIndex += valueSize_;
                }
              }
              {
                int i = 0;
                int i1 = 0;
                int i2 = 0;
                final int an = query_.aggregates().size();
                for (int ai = 0; ai < an; ++ai) {
                  final Aggregate agg = query_.aggregates().get(ai);
                  final int n = agg.aggCount();
                  for (int j = 0; j < n; ++j) {
                    final FixedPointModContext fp =
                        agg.shouldScale(j) ?
                            fixedPointModContexts_[ai] :
                            zeroScaleFpmContext_;
                    if (agg.db() == Party.DB1) {
                      rowDec_[i++] = fp.decode(rowBig1_[i1++],
                                               (BigDecimal)null,
                                               true);
                    } else {
                      rowDec_[i++] = fp.decode(rowBig2_[i2++],
                                               (BigDecimal)null,
                                               true);
                    }
                  }
                }
              }
            }
            {
              final int calculationScale = config_.calculationScale();
              final int resultScale = config_.resultScale();
              final BigDecimal[] row = result_.get(tupleIndex_);
              int i = 0;
              for (int col = 0; col < columnCount_; ++col) {
                final Aggregate agg = query_.aggregates().get(col);
                switch (agg.function()) {
                  case COUNT: {
                    row[col] = rowDec_[i];
                  } break;
                  case SUM: {
                    row[col] = rowDec_[i];
                  } break;
                  case AVG: {
                    if (rowDec_[i].signum() == 0) {
                      row[col] = null;
                    } else {
                      row[col] =
                          rowDec_[i + 1].divide(rowDec_[i],
                                                calculationScale,
                                                RoundingMode.HALF_UP);
                    }
                  } break;
                  case STDEV: {
                    if (rowDec_[i].signum() == 0
                        || rowDec_[i].compareTo(BigDecimal.ONE) == 0) {
                      row[col] = null;
                    } else {
                      final BigDecimal mean =
                          rowDec_[i + 1].divide(rowDec_[i],
                                                calculationScale,
                                                RoundingMode.HALF_UP);
                      row[col] = Arith.newtonSqrt(
                          rowDec_[i + 2]
                              .divide(rowDec_[i],
                                      calculationScale,
                                      RoundingMode.HALF_UP)
                              .subtract(mean.multiply(mean))
                              .multiply(rowDec_[i])
                              .divide(
                                  rowDec_[i].subtract(BigDecimal.ONE),
                                  calculationScale,
                                  RoundingMode.HALF_UP),
                          calculationScale);
                    }
                  } break;
                  case STDEVP: {
                    if (rowDec_[i].signum() == 0) {
                      row[col] = null;
                    } else {
                      final BigDecimal mean =
                          rowDec_[i + 1].divide(rowDec_[i],
                                                calculationScale,
                                                RoundingMode.HALF_UP);
                      row[col] = Arith.newtonSqrt(
                          rowDec_[i + 2]
                              .divide(rowDec_[i],
                                      calculationScale,
                                      RoundingMode.HALF_UP)
                              .subtract(mean.multiply(mean)),
                          calculationScale);
                    }
                  } break;
                  case VAR: {
                    if (rowDec_[i].signum() == 0
                        || rowDec_[i].compareTo(BigDecimal.ONE) == 0) {
                      row[col] = null;
                    } else {
                      final BigDecimal mean =
                          rowDec_[i + 1].divide(rowDec_[i],
                                                calculationScale,
                                                RoundingMode.HALF_UP);
                      row[col] = rowDec_[i + 2]
                                     .divide(rowDec_[i],
                                             calculationScale,
                                             RoundingMode.HALF_UP)
                                     .subtract(mean.multiply(mean))
                                     .multiply(rowDec_[i])
                                     .divide(rowDec_[i].subtract(
                                                 BigDecimal.ONE),
                                             calculationScale,
                                             RoundingMode.HALF_UP);
                    }
                  } break;
                  case VARP: {
                    if (rowDec_[i].signum() == 0) {
                      row[col] = null;
                    } else {
                      final BigDecimal mean =
                          rowDec_[i + 1].divide(rowDec_[i],
                                                calculationScale,
                                                RoundingMode.HALF_UP);
                      row[col] = rowDec_[i + 2]
                                     .divide(rowDec_[i],
                                             calculationScale,
                                             RoundingMode.HALF_UP)
                                     .subtract(mean.multiply(mean));
                    }
                  } break;
                }
                if (row[col] != null) {
                  row[col] = row[col].setScale(resultScale,
                                               RoundingMode.HALF_UP);
                }
                i += agg.aggCount();
              }
            }
            // TODO: This is only for debugging.
            {
              final BigDecimal[] row = result_.get(tupleIndex_);
              String s = "";
              for (int i = 0; i < row.length; ++i) {
                s += ((i > 0) ? ", " : "") + row[i];
              }
              System.out.println(s);
            }
            // TODO: Convert the rows into the proper data type, then
            //       send them to the HTTP handler.
            if (rowCount1_ == 0 || rowCount2_ == 0) {
            } else {
              if (valuesFitInt_) {
                zIntEntry1_.release();
                zIntEntry2_.release();
                zIntEntry1_ = null;
                zIntEntry2_ = null;
                zInt1_ = null;
                zInt2_ = null;
              } else if (valuesFitLong_) {
                zLongEntry1_.release();
                zLongEntry2_.release();
                zLongEntry1_ = null;
                zLongEntry2_ = null;
                zLong1_ = null;
                zLong2_ = null;
              } else {
                zBigEntry1_.release();
                zBigEntry2_.release();
                zBigEntry1_ = null;
                zBigEntry2_ = null;
                zBig1_ = null;
                zBig2_ = null;
              }
              sBytesEntry1_.release();
              sBytesEntry1_ = null;
              sBytes1_ = null;
              sBytesEntry2_.release();
              sBytesEntry2_ = null;
              sBytes2_ = null;
            }
            rowCount1_ = -1;
            rowCount2_ = -1;
            rowIndex1_ = -1;
            rowIndex2_ = -1;
            swd_.tupleIndex.incrementAndGet();
            setState(State.MM_NEXT_DOMAIN_TUPLE);
          } break;

          case MM_FINISH_QUERY: {
            // TODO: Output the JSON result file.
            final Map<String, Object> json =
                new HashMap<String, Object>();
            final List<Column> gb = query_.groupBys();
            {
              final List<String> xs =
                  new ArrayList<String>(gb.size() + columnCount_);
              for (int i = 0; i < gb.size(); ++i) {
                final Column c = gb.get(i);
                xs.add(c.table().underlyingName() + "."
                       + c.underlyingName());
              }
              for (int i = 0; i < columnCount_; ++i) {
                final Aggregate agg = query_.aggregates().get(i);
                xs.add(agg.function().name() + "("
                       + agg.table().underlyingName() + "."
                       + agg.column().underlyingName() + ")");
              }
              json.put("result_table_header", xs);
            }
            {
              final List<List<String>> table =
                  new ArrayList<List<String>>();
              for (int r = 0; r < tupleCount_; ++r) {
                final List<Object> tuple = tuples_.get(r);
                final BigDecimal[] resultRow = result_.get(r);
                final List<String> xs =
                    new ArrayList<String>(gb.size() + columnCount_);
                for (int i = 0; i < gb.size(); ++i) {
                  xs.add(tuple.get(i).toString());
                }
                for (int i = 0; i < columnCount_; ++i) {
                  if (resultRow[i] == null) {
                    xs.add(null);
                  } else {
                    xs.add(resultRow[i].toString());
                  }
                }
                table.add(xs);
              }
              json.put("result_table", table);
            }
            System.out.println(
                Json.dump(json, Json.DumpOptions.INDENT_2));
            setState(State.MM_NOOP);
          } break;

          case MM_NOOP:
            return;

          default:
            throw new ImpossibleException();
        }
      }
    }

    final void tick() {
      long d = tickDepth.get();
      while (true) {
        tick2();
        if (tickDepth.compareAndSet(d, 0)) {
          break;
        }
        d = tickDepth.decrementAndGet();
        if (d == 0) {
          break;
        }
      }
    }
  }

  //--------------------------------------------------------------------
  // SharedHandlerData
  //--------------------------------------------------------------------
  //
  // SharedHandlerData holds the data shared between all handlers
  // running on the same node for the same query.
  //
  // All SharedHandlerData instances are stored in the Globals class in
  // a concurrent map that is keyed by query ID. Handlers for the same
  // query compete to create the SharedHandlerData instance for that
  // query by calling globals_.createOrGetSharedHandlerData().
  //

  static final class SharedHandlerData {
    public final Globals globals;
    public final Guid queryId;
    public final Query query;
    public final Modulus modulus;
    private final AtomicInteger doneCountdown = new AtomicInteger();

    //
    // handlers keeps track of all handlers running on this node for the
    // same query. Each handler adds itself to the map at the beginning
    // of the query. The map is keyed by the remote party.
    //

    public final ConcurrentHashMap<
        Party,
        ConcurrentHashMap<StateStream, RawChannelHandler>> handlers =
        new ConcurrentHashMap<
            Party,
            ConcurrentHashMap<StateStream, RawChannelHandler>>();

    //------------------------------------------------------------------

    public final
        Map<Party, Map<StateStream, AtomicReference<ChannelPipeline>>>
            pipelines = new HashMap<
                Party,
                Map<StateStream, AtomicReference<ChannelPipeline>>>();

    //------------------------------------------------------------------
    // Row count queues
    //------------------------------------------------------------------

    public final Map<Party, Queue<BothRowCounts>>
        phDbS1ToPhDbS3BothRowCountsQueues;
    public final Queue<BothRowCounts> dbPhS1ToDbPhS3BothRowCountsQueue;
    public final Queue<Long> dbPhS1ToDbDbSsLocalRowCountQueue;
    public final Queue<Long> dbPhS1ToDbDbRsLocalRowCountQueue;
    public final Queue<Long> dbDbRhToDbPhS1OtherRowCountQueue;
    public final Map<Party, Queue<BothRowCounts>> bothRowCountsQueues =
        new HashMap<Party, Queue<BothRowCounts>>();

    //------------------------------------------------------------------

    public final LinkedBlockingQueue<PoolEntry<int[]>> rIntQueue;
    public final LinkedBlockingQueue<PoolEntry<long[]>> rLongQueue;
    public final LinkedBlockingQueue<PoolEntry<BigInteger[]>> rBigQueue;

    //------------------------------------------------------------------

    public final Queue<PoolEntry<int[]>> zIntQueue1;
    public final Queue<PoolEntry<long[]>> zLongQueue1;
    public final Queue<PoolEntry<BigInteger[]>> zBigQueue1;
    public final Queue<PoolEntry<int[]>> zIntQueue2;
    public final Queue<PoolEntry<long[]>> zLongQueue2;
    public final Queue<PoolEntry<BigInteger[]>> zBigQueue2;

    //------------------------------------------------------------------

    public final Queue<PoolEntry<XIntBatch>> xIntBatchQueue;
    public final Queue<PoolEntry<XLongBatch>> xLongBatchQueue;
    public final Queue<PoolEntry<XBigBatch>> xBigBatchQueue;

    //------------------------------------------------------------------

    public final LinkedBlockingQueue<PoolEntry<int[]>> aIntBatchQueue;
    public final LinkedBlockingQueue<PoolEntry<long[]>> aLongBatchQueue;
    public final LinkedBlockingQueue<PoolEntry<BigInteger[]>>
        aBigBatchQueue;

    //------------------------------------------------------------------

    public final HashMap<Party, LinkedBlockingQueue<PoolEntry<byte[]>>>
        bBytesBatchQueues;

    //------------------------------------------------------------------

    public final HashMap<Party, LinkedBlockingQueue<PoolEntry<byte[]>>>
        xaBytesBatchQueues;

    //------------------------------------------------------------------

    public final Map<Party, Queue<PoolEntry<byte[]>>>
        ybBytesBatchQueues;

    //------------------------------------------------------------------

    public final Queue<PoolEntry<byte[]>> sBytesQueue1;
    public final Queue<PoolEntry<byte[]>> sBytesQueue2;

    //------------------------------------------------------------------

    final MergeMachine mergeMachine;

    //------------------------------------------------------------------

    public SharedHandlerData(final RawChannelHandler handler) {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(handler != null);
          SST_ASSERT(handler.localAggCount_ >= 0);
          SST_ASSERT(handler.otherAggCount_ >= 0);
          SST_ASSERT(handler.stateStream_ != null);
          if (handler.localPartyIsDb_) {
            SST_ASSERT(handler.otherMaxBatch_ > 0);
          }
          SST_ASSERT(handler.valueSize_ > 0);
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }

      globals = handler.globals_;
      queryId = handler.queryId_;
      query = handler.query_;
      modulus = handler.modulus_;

      if (handler.localPartyIsPh_) {
        doneCountdown.set(6);
      } else {
        doneCountdown.set(4);
      }

      for (final Party party : Party.values()) {
        handlers.put(
            party,
            new ConcurrentHashMap<StateStream, RawChannelHandler>());
        final Map<StateStream, AtomicReference<ChannelPipeline>> m =
            new HashMap<StateStream,
                        AtomicReference<ChannelPipeline>>();
        pipelines.put(party, m);
        for (final StateStream stateStream : StateStream.values()) {
          m.put(stateStream,
                new AtomicReference<ChannelPipeline>(null));
        }
      }

      // TODO: max capacities for queues

      //----------------------------------------------------------------
      // Row count queues
      //----------------------------------------------------------------

      if (handler.localPartyIsPh_) {
        phDbS1ToPhDbS3BothRowCountsQueues =
            new HashMap<Party, Queue<BothRowCounts>>();
        for (final Party db : Party.dbValues()) {
          phDbS1ToPhDbS3BothRowCountsQueues.put(
              db,
              new LinkedBlockingQueue<BothRowCounts>(10000));
        }
        dbPhS1ToDbPhS3BothRowCountsQueue = null;
        dbPhS1ToDbDbSsLocalRowCountQueue = null;
        dbPhS1ToDbDbRsLocalRowCountQueue = null;
        dbDbRhToDbPhS1OtherRowCountQueue = null;
        for (final Party db : Party.dbValues()) {
          bothRowCountsQueues.put(
              db,
              new LinkedBlockingQueue<BothRowCounts>(10000));
        }
      } else {
        phDbS1ToPhDbS3BothRowCountsQueues = null;
        dbPhS1ToDbPhS3BothRowCountsQueue =
            new LinkedBlockingQueue<BothRowCounts>(10000);
        dbPhS1ToDbDbSsLocalRowCountQueue =
            new LinkedBlockingQueue<Long>(10000);
        dbPhS1ToDbDbRsLocalRowCountQueue =
            new LinkedBlockingQueue<Long>(10000);
        dbDbRhToDbPhS1OtherRowCountQueue =
            new LinkedBlockingQueue<Long>(10000);
        bothRowCountsQueues.put(
            handler.otherDb_,
            new LinkedBlockingQueue<BothRowCounts>(10000));
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsDb_) {
        if (handler.valuesFitInt_) {
          rIntQueue = new LinkedBlockingQueue<PoolEntry<int[]>>(10000);
          rLongQueue = null;
          rBigQueue = null;
        } else if (handler.valuesFitLong_) {
          rIntQueue = null;
          rLongQueue =
              new LinkedBlockingQueue<PoolEntry<long[]>>(10000);
          rBigQueue = null;
        } else {
          rIntQueue = null;
          rLongQueue = null;
          rBigQueue =
              new LinkedBlockingQueue<PoolEntry<BigInteger[]>>(10000);
        }
      } else {
        rIntQueue = null;
        rLongQueue = null;
        rBigQueue = null;
      }

      //----------------------------------------------------------------

      if (handler.valuesFitInt_) {
        zIntQueue1 = new LinkedBlockingQueue<PoolEntry<int[]>>(10000);
        zLongQueue1 = null;
        zBigQueue1 = null;
      } else if (handler.valuesFitLong_) {
        zIntQueue1 = null;
        zLongQueue1 = new LinkedBlockingQueue<PoolEntry<long[]>>(10000);
        zBigQueue1 = null;
      } else {
        zIntQueue1 = null;
        zLongQueue1 = null;
        zBigQueue1 =
            new LinkedBlockingQueue<PoolEntry<BigInteger[]>>(10000);
      }
      if (handler.localPartyIsPh_) {
        if (handler.valuesFitInt_) {
          zIntQueue2 = new LinkedBlockingQueue<PoolEntry<int[]>>(10000);
          zLongQueue2 = null;
          zBigQueue2 = null;
        } else if (handler.valuesFitLong_) {
          zIntQueue2 = null;
          zLongQueue2 =
              new LinkedBlockingQueue<PoolEntry<long[]>>(10000);
          zBigQueue2 = null;
        } else {
          zIntQueue2 = null;
          zLongQueue2 = null;
          zBigQueue2 =
              new LinkedBlockingQueue<PoolEntry<BigInteger[]>>(10000);
        }
      } else {
        zIntQueue2 = null;
        zLongQueue2 = null;
        zBigQueue2 = null;
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsDb_) {
        if (handler.valuesFitInt_) {
          xIntBatchQueue =
              new LinkedBlockingQueue<PoolEntry<XIntBatch>>(10000);
          xLongBatchQueue = null;
          xBigBatchQueue = null;
        } else if (handler.valuesFitLong_) {
          xIntBatchQueue = null;
          xLongBatchQueue =
              new LinkedBlockingQueue<PoolEntry<XLongBatch>>(10000);
          xBigBatchQueue = null;
        } else {
          xIntBatchQueue = null;
          xLongBatchQueue = null;
          xBigBatchQueue =
              new LinkedBlockingQueue<PoolEntry<XBigBatch>>(10000);
        }
      } else {
        xIntBatchQueue = null;
        xLongBatchQueue = null;
        xBigBatchQueue = null;
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsDb_) {
        if (handler.valuesFitInt_) {
          aIntBatchQueue =
              new LinkedBlockingQueue<PoolEntry<int[]>>(10000);
          aLongBatchQueue = null;
          aBigBatchQueue = null;
        } else if (handler.valuesFitLong_) {
          aIntBatchQueue = null;
          aLongBatchQueue =
              new LinkedBlockingQueue<PoolEntry<long[]>>(10000);
          aBigBatchQueue = null;
        } else {
          aIntBatchQueue = null;
          aLongBatchQueue = null;
          aBigBatchQueue =
              new LinkedBlockingQueue<PoolEntry<BigInteger[]>>(10000);
        }
      } else {
        aIntBatchQueue = null;
        aLongBatchQueue = null;
        aBigBatchQueue = null;
      }

      //----------------------------------------------------------------

      bBytesBatchQueues =
          new HashMap<Party, LinkedBlockingQueue<PoolEntry<byte[]>>>();
      if (handler.localPartyIsDb_) {
        bBytesBatchQueues.put(
            Party.PH,
            new LinkedBlockingQueue<PoolEntry<byte[]>>(10000));
      } else {
        bBytesBatchQueues.put(
            Party.DB1,
            new LinkedBlockingQueue<PoolEntry<byte[]>>(10000));
        bBytesBatchQueues.put(
            Party.DB2,
            new LinkedBlockingQueue<PoolEntry<byte[]>>(10000));
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsPh_) {
        xaBytesBatchQueues =
            new HashMap<Party,
                        LinkedBlockingQueue<PoolEntry<byte[]>>>();
        for (final Party db : Party.dbValues()) {
          xaBytesBatchQueues.put(
              db,
              new LinkedBlockingQueue<PoolEntry<byte[]>>(10000));
        }
      } else {
        xaBytesBatchQueues = null;
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsPh_) {
        ybBytesBatchQueues =
            new HashMap<Party, Queue<PoolEntry<byte[]>>>();
        for (final Party db : Party.dbValues()) {
          ybBytesBatchQueues.put(
              db,
              new LinkedBlockingQueue<PoolEntry<byte[]>>(10000));
        }
      } else {
        ybBytesBatchQueues = null;
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsPh_) {
        sBytesQueue1 =
            new LinkedBlockingQueue<PoolEntry<byte[]>>(10000);
        sBytesQueue2 =
            new LinkedBlockingQueue<PoolEntry<byte[]>>(10000);
      } else {
        sBytesQueue1 = null;
        sBytesQueue2 = null;
      }

      //----------------------------------------------------------------

      if (handler.localPartyIsPh_) {
        mergeMachine = new MergeMachine(
            handler.globals_,
            this,
            handler.modulusInt_,
            handler.modulusLong_,
            handler.modulusBig_,
            handler.valueSize_,
            handler.valuesFitInt_,
            handler.valuesFitLong_,
            handler.remoteParty_ == Party.DB1 ? handler.localAggCount_ :
                                                handler.otherAggCount_,
            handler.remoteParty_ == Party.DB2 ? handler.localAggCount_ :
                                                handler.otherAggCount_,
            xaBytesBatchQueues.get(Party.DB1),
            xaBytesBatchQueues.get(Party.DB2),
            bBytesBatchQueues.get(Party.DB1),
            bBytesBatchQueues.get(Party.DB2),
            ybBytesBatchQueues.get(Party.DB1),
            ybBytesBatchQueues.get(Party.DB2),
            pipelines.get(Party.DB1).get(StateStream.S1),
            pipelines.get(Party.DB2).get(StateStream.S1),
            pipelines.get(Party.DB1).get(StateStream.S2),
            pipelines.get(Party.DB2).get(StateStream.S2),
            pipelines.get(Party.DB1).get(StateStream.S3),
            pipelines.get(Party.DB2).get(StateStream.S3));
      } else {
        mergeMachine = null;
      }

      //----------------------------------------------------------------
    }

    public final void done() {
      if (doneCountdown.decrementAndGet() == 0) {
        globals.removeSharedHandlerData(queryId);
      }
    }
  }

  private Party remoteParty_;
  private boolean remotePartyIsDb_;
  private boolean remotePartyIsPh_;
  private final Lexicon lexicon_;
  private final int guidSize_;
  private final int linkingColumnSize_;
  private final Party localParty_;
  private final boolean localPartyIsDb_;
  private final boolean localPartyIsPh_;
  private final JdbcType linkingColumnJdbcType_;
  private final boolean linkingColumnIsString_;
  private final boolean linkingColumnUnicode_;
  private final boolean linkingColumnForceString_;
  private final byte[] previousId_;
  private final Party localDb_;
  private final Party otherDb_;
  private final Table localTable_;
  private final Table otherTable_;
  private final JdbcRunner jdbcRunner_ = new JdbcRunner();

  //--------------------------------------------------------------------
  // getLinkingColumn
  //--------------------------------------------------------------------
  //
  // Converts the linking column of the current row to a byte sequence
  // suitable to be compared with memcmp. As the caller iterates through
  // the rows of a result doing this conversion, they should ensure that
  // the memcmp ordering agrees with the iterative order produced by the
  // ORDER BY clause. Otherwise, the algorithm will be broken.
  //

  private final void getLinkingColumn(final ResultSet fullResult,
                                      final int columnIndex,
                                      final byte[] dst,
                                      final int off) throws Exception {
    final JdbcType jdbcType;
    if (linkingColumnForceString_) {
      jdbcType = JdbcType.CHAR;
    } else {
      jdbcType = linkingColumnJdbcType_;
    }
    switch (jdbcType) {
      case TINYINT:
      case SMALLINT:
      case INTEGER:
      case BIGINT:
      case BIT:
      case BOOLEAN: {
        final long x = fullResult.getLong(columnIndex);
        final int k = Math.min(linkingColumnSize_, 8);
        final long m = 1L << (k * 8 - 1);
        if (k < 8 && (x >= m || x < -m)) {
          throw new RuntimeException(
              "linking_column_size is too small.");
        }
        Rep.toBytes(x + m,
                    dst,
                    off,
                    linkingColumnSize_,
                    IntegerRep.TWOS_COMPLEMENT,
                    ByteOrder.BIG_ENDIAN,
                    false);
      } break;
      case CHAR:
      case VARCHAR:
      case NCHAR:
      case NVARCHAR: {
        final String x;
        if (jdbcType == JdbcType.CHAR || jdbcType == JdbcType.VARCHAR) {
          x = fullResult.getString(columnIndex);
        } else {
          x = fullResult.getNString(columnIndex);
        }
        final int n = x.length();
        int j = off;
        if (linkingColumnUnicode_) {
          if (n > linkingColumnSize_ / 2) {
            throw new RuntimeException(
                "linking_column_size is too small.");
          }
          for (int i = 0; i < n; ++i) {
            final char c = x.charAt(i);
            if (c == 0) {
              throw new RuntimeException(
                  "The linking column contains a U+0000 code point.");
            }
            dst[j++] = (byte)((c >>> 8) & 0xFF);
            dst[j++] = (byte)((c >>> 0) & 0xFF);
          }
        } else {
          if (n > linkingColumnSize_) {
            throw new RuntimeException(
                "linking_column_size is too small.");
          }
          for (int i = 0; i < n; ++i) {
            final char c = x.charAt(i);
            if (c == 0) {
              throw new RuntimeException(
                  "The linking column contains a U+0000 code point.");
            }
            if (c > 0xFF) {
              throw new RuntimeException(
                  "The linking column contains data that "
                  + "requires linking_column_unicode to be enabled.");
            }
            dst[j++] = (byte)c;
          }
        }
        while (j < off + linkingColumnSize_) {
          dst[j++] = 0;
        }
      } break;
      default:
        throw new ImpossibleException();
    }
  }

  //--------------------------------------------------------------------
  // orderMismatch
  //--------------------------------------------------------------------

  private final RuntimeException orderMismatch() {
    throw new RuntimeException(
        "The ordering produced by the ORDER BY "
        + "clause does not match the internal ordering.");
  }

  //--------------------------------------------------------------------
  // randMod_
  //--------------------------------------------------------------------

  private final RandModContext randMod_;
  private final RandModContext randMod2_;
  private final int valueSize_;
  private final boolean valuesFitInt_;
  private final boolean valuesFitLong_;
  private final Modulus modulus_;
  private final int modulusInt_;
  private final long modulusLong_;
  private final BigInteger modulusBig_;
  private final byte[] randModDstBuf_;
  private final byte[] randModDstBuf2_;

  private final void
  randMod(final byte[] dst, final int n, final RandModContext ctx) {
    ctx.gen(Rand.cryptoRng(),
            dst,
            0,
            n * valueSize_,
            ByteOrder.BIG_ENDIAN);
  }

  private final void
  randMod(final byte[] src, final int[] dst, final int n) {
    for (int i = 0; i != n; ++i) {
      dst[i] = Rep.fromBytes(src,
                             i * valueSize_,
                             valueSize_,
                             (Integer)null,
                             IntegerRep.PURE_UNSIGNED,
                             ByteOrder.BIG_ENDIAN,
                             false);
    }
  }

  private final void
  randMod(final byte[] src, final long[] dst, final int n) {
    for (int i = 0; i != n; ++i) {
      dst[i] = Rep.fromBytes(src,
                             i * valueSize_,
                             valueSize_,
                             (Long)null,
                             IntegerRep.PURE_UNSIGNED,
                             ByteOrder.BIG_ENDIAN,
                             false);
    }
  }

  private final void randMod(final byte[] src,
                             final BigInteger[] dst,
                             final int n,
                             final byte[] dstBuf) {
    for (int i = 0; i != n; ++i) {
      dst[i] = Rep.fromBytes(src,
                             i * valueSize_,
                             valueSize_,
                             (BigInteger)null,
                             IntegerRep.PURE_UNSIGNED,
                             ByteOrder.BIG_ENDIAN,
                             false);
    }
  }

  //--------------------------------------------------------------------

  //
  // outgoing_ indicates whether this handler was established via an
  // outgoing connection to the remote party vs. an incoming connection.
  // Outgoing vs. incoming is used to resolve various "who sends first?"
  // decisions in the protocol.
  //
  // The "who connects to who?" decision is resolved with the convention
  // that, given two Party IDs x and y, x will connect to y if and only
  // if x.toInt() < y.toInt().
  //
  // Note that outgoing_ is known at construction time: if remoteParty
  // is initialized to null then we're accepting a connection from any
  // party, and if not then we're connecting outward to remoteParty.
  //

  //--------------------------------------------------------------------

  private final AtomicLong lastActivityTime_ =
      new AtomicLong(System.nanoTime());

  public final long lastActivityTime() {
    return lastActivityTime_.get();
  }

  private void updateLastActivityTime() {
    lastActivityTime_.set(System.nanoTime());
  }

  //--------------------------------------------------------------------

  private int incomingLexiconStringLength_;
  private int incomingQueryStringLength_;

  //--------------------------------------------------------------------

  private StateStream stateStream_;

  private PoolEntry<ChannelFuture> channelPoolEntry_;
  private String queryString_;
  private Guid queryId_;
  private Query query_;

  private ActiveQuery activeQuery_;

  private SharedHandlerData shd_;

  private DomainIterator domainIterator_;
  private DomainIterator domainIteratorSh_;
  private DomainIterator domainIteratorRh_;

  private int localAggCount_;
  private int otherAggCount_;

  private FixedPointModContext zeroScaleFpmContext_;
  private FixedPointModContext[] fixedPointModContexts_;

  private int otherMaxBatch_;
  private int localMaxBatch_;

  //--------------------------------------------------------------------

  private Queue<BothRowCounts> phDbS1ToPhDbS3BothRowCountsQueue_;

  //--------------------------------------------------------------------

  private byte[] rBytesSh_;
  private int[] rInt_;
  private long[] rLong_;
  private BigInteger[] rBig_;

  //--------------------------------------------------------------------

  private byte[] rBytesRh_;
  private ConcurrentPool<int[]> rIntPool_;
  private ConcurrentPool<long[]> rLongPool_;
  private ConcurrentPool<BigInteger[]> rBigPool_;
  private PoolEntry<int[]> rIntEntry_;
  private PoolEntry<long[]> rLongEntry_;
  private PoolEntry<BigInteger[]> rBigEntry_;

  //--------------------------------------------------------------------

  private ConcurrentPool<int[]> zIntPool_;
  private ConcurrentPool<long[]> zLongPool_;
  private ConcurrentPool<BigInteger[]> zBigPool_;
  private PoolEntry<int[]> zIntEntry_;
  private PoolEntry<long[]> zLongEntry_;
  private PoolEntry<BigInteger[]> zBigEntry_;
  private int[] zInt_;
  private long[] zLong_;
  private BigInteger[] zBig_;
  private byte[] zBytes_;
  private Queue<PoolEntry<int[]>> zIntQueue_;
  private Queue<PoolEntry<long[]>> zLongQueue_;
  private Queue<PoolEntry<BigInteger[]>> zBigQueue_;

  //--------------------------------------------------------------------

  private byte[] aBytesBatchSh_;
  private int[] aIntBatch_;
  private long[] aLongBatch_;
  private BigInteger[] aBigBatch_;
  private byte[] aBytesBatchRh_;
  private ConcurrentPool<int[]> aIntBatchPool_;
  private ConcurrentPool<long[]> aLongBatchPool_;
  private ConcurrentPool<BigInteger[]> aBigBatchPool_;
  private PoolEntry<int[]> aIntBatchEntry_;
  private PoolEntry<long[]> aLongBatchEntry_;
  private PoolEntry<BigInteger[]> aBigBatchEntry_;

  //--------------------------------------------------------------------

  private ConcurrentPool<byte[]> bBytesBatchPool_;
  private PoolEntry<byte[]> bBytesBatchEntry_;
  private int[] bIntBatch_;
  private long[] bLongBatch_;
  private BigInteger[] bBigBatch_;
  private LinkedBlockingQueue<PoolEntry<byte[]>> bBytesBatchQueue_;

  //--------------------------------------------------------------------

  private ConcurrentPool<XIntBatch> xIntBatchPool_;
  private ConcurrentPool<XLongBatch> xLongBatchPool_;
  private ConcurrentPool<XBigBatch> xBigBatchPool_;
  private PoolEntry<XIntBatch> xIntBatchEntry_;
  private PoolEntry<XLongBatch> xLongBatchEntry_;
  private PoolEntry<XBigBatch> xBigBatchEntry_;
  private XIntBatch xIntBatch_;
  private XLongBatch xLongBatch_;
  private XBigBatch xBigBatch_;
  private Future<?> xBatchFuture_;

  //--------------------------------------------------------------------

  private Future<?> generateABBatchFuture_;

  //--------------------------------------------------------------------

  private long localRowCount_;
  private long localRowCountSh_;
  private long localRowCountRh_;
  private long localRowIndex_;
  private int localRowBatch_;

  private long otherRowCount_;
  private long otherRowCountSh_;
  private long otherRowCountRh_;
  private long otherRowIndex_;
  private int otherRowBatch_;

  private final LinkedList<Long> otherRowCountQueue_;
  // TODO: Control otherRowCountQueueLimit_ somehow?
  private final static int otherRowCountQueueLimit_ = 100000;

  //--------------------------------------------------------------------

  private int xaBytesSize_;
  private byte[] xaBytesBatch_;
  private Future<?> xaFuture_;
  private ConcurrentPool<byte[]> xaBytesBatchPool_;
  private PoolEntry<byte[]> xaBytesBatchEntry_;
  private Queue<PoolEntry<byte[]>> xaBytesBatchQueue_;

  //--------------------------------------------------------------------

  private Queue<PoolEntry<byte[]>> ybBytesBatchQueue_;
  private PoolEntry<byte[]> ybBytesBatchEntry_;
  private byte[] ybBytesBatch_;
  private int[] ybIntBatch_;
  private long[] ybLongBatch_;
  private BigInteger[] ybBigBatch_;

  //--------------------------------------------------------------------

  private int[] sInt_;
  private long[] sLong_;
  private BigInteger[] sBig_;
  private ConcurrentPool<byte[]> sBytesPool_;
  private PoolEntry<byte[]> sBytesEntry_;
  private byte[] sBytes_;
  private Future<?> sFuture_;
  private Queue<PoolEntry<byte[]>> sBytesQueue_;

  //--------------------------------------------------------------------

  private void resetForNextQuery() {
    jdbcRunner_.close();

    stateStream_ = null;
    updateLogPrefix();

    if (outgoing_) {
      // channelPoolEntry_ will be null at construction time, which is
      // when the first call to resetForNextQuery is made.
      if (channelPoolEntry_ != null) {
        channelPoolEntry_.release();
        channelPoolEntry_ = null;
      }
    }
    queryString_ = null;
    queryId_ = null;
    query_ = null;

    shd_ = null;

    domainIterator_ = null;
    domainIteratorSh_ = null;
    domainIteratorRh_ = null;

    localAggCount_ = -1;
    otherAggCount_ = -1;

    zeroScaleFpmContext_ = null;
    fixedPointModContexts_ = null;

    otherMaxBatch_ = -1;
    localMaxBatch_ = -1;

    //------------------------------------------------------------------

    phDbS1ToPhDbS3BothRowCountsQueue_ = null;

    //------------------------------------------------------------------

    rBytesSh_ = null;
    rInt_ = null;
    rLong_ = null;
    rBig_ = null;

    //------------------------------------------------------------------

    rBytesRh_ = null;
    rIntPool_ = null;
    rLongPool_ = null;
    rBigPool_ = null;
    rIntEntry_ = null;
    rLongEntry_ = null;
    rBigEntry_ = null;

    //------------------------------------------------------------------

    zIntPool_ = null;
    zLongPool_ = null;
    zBigPool_ = null;
    zIntEntry_ = null;
    zLongEntry_ = null;
    zBigEntry_ = null;
    zInt_ = null;
    zLong_ = null;
    zBig_ = null;
    zBytes_ = null;
    zIntQueue_ = null;
    zLongQueue_ = null;
    zBigQueue_ = null;

    //------------------------------------------------------------------

    aBytesBatchSh_ = null;
    aIntBatch_ = null;
    aLongBatch_ = null;
    aBigBatch_ = null;
    aBytesBatchRh_ = null;
    aIntBatchPool_ = null;
    aLongBatchPool_ = null;
    aBigBatchPool_ = null;
    aIntBatchEntry_ = null;
    aLongBatchEntry_ = null;
    aBigBatchEntry_ = null;

    //------------------------------------------------------------------

    bBytesBatchPool_ = null;
    bBytesBatchEntry_ = null;
    bIntBatch_ = null;
    bLongBatch_ = null;
    bBigBatch_ = null;
    bBytesBatchQueue_ = null;

    //------------------------------------------------------------------

    xIntBatchPool_ = null;
    xLongBatchPool_ = null;
    xBigBatchPool_ = null;
    xIntBatchEntry_ = null;
    xLongBatchEntry_ = null;
    xBigBatchEntry_ = null;
    xIntBatch_ = null;
    xLongBatch_ = null;
    xBigBatch_ = null;
    xBatchFuture_ = null;

    //------------------------------------------------------------------

    generateABBatchFuture_ = null;

    //------------------------------------------------------------------

    localRowCount_ = -1;
    localRowCountSh_ = -1;
    localRowCountRh_ = -1;
    localRowIndex_ = -1;
    localRowBatch_ = -1;

    otherRowCount_ = -1;
    otherRowCountSh_ = -1;
    otherRowCountRh_ = -1;
    otherRowIndex_ = -1;
    otherRowBatch_ = -1;

    if (localPartyIsDb_) {
      otherRowCountQueue_.clear();
    }

    //------------------------------------------------------------------

    xaBytesSize_ = -1;
    xaBytesBatch_ = null;
    xaFuture_ = null;
    xaBytesBatchPool_ = null;
    xaBytesBatchEntry_ = null;
    xaBytesBatchQueue_ = null;

    //------------------------------------------------------------------

    ybBytesBatchQueue_ = null;
    ybBytesBatchEntry_ = null;
    ybBytesBatch_ = null;
    ybIntBatch_ = null;
    ybLongBatch_ = null;
    ybBigBatch_ = null;

    //------------------------------------------------------------------

    sInt_ = null;
    sLong_ = null;
    sBig_ = null;
    sBytesPool_ = null;
    sBytesEntry_ = null;
    sBytes_ = null;
    sFuture_ = null;
    sBytesQueue_ = null;

    //------------------------------------------------------------------

    countSql_ = null;
    countSqlFormat_ = null;
    fullSql_ = null;
    fullSqlFormat_ = null;
    allParameters_ = null;
    domainParameters_ = null;
    countStatement_ = null;
    fullStatement_ = null;
    countResult_ = null;
    fullResult_ = null;
    fullResultMetadata_ = null;
  }

  private void gotQuery() {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(remoteParty_ != null);
        SST_ASSERT(remotePartyIsDb_ == remoteParty_.isDb());
        SST_ASSERT(remotePartyIsPh_ == !remotePartyIsDb_);

        SST_ASSERT(stateStream_ != null);
        if (outgoing_) {
          SST_ASSERT(channelPoolEntry_ != null);
        } else {
          SST_ASSERT(channelPoolEntry_ == null);
        }
        SST_ASSERT(queryString_ != null);
        SST_ASSERT(queryId_ != null);
        SST_ASSERT(query_ != null);

        SST_ASSERT(shd_ == null);

        SST_ASSERT(domainIterator_ == null);
        SST_ASSERT(domainIteratorSh_ == null);
        SST_ASSERT(domainIteratorRh_ == null);

        SST_ASSERT(localAggCount_ == -1);
        SST_ASSERT(otherAggCount_ == -1);

        SST_ASSERT(zeroScaleFpmContext_ == null);
        SST_ASSERT(fixedPointModContexts_ == null);

        SST_ASSERT(otherMaxBatch_ == -1);
        SST_ASSERT(localMaxBatch_ == -1);

        //--------------------------------------------------------------

        SST_ASSERT(phDbS1ToPhDbS3BothRowCountsQueue_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(rBytesSh_ == null);
        SST_ASSERT(rInt_ == null);
        SST_ASSERT(rLong_ == null);
        SST_ASSERT(rBig_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(rBytesRh_ == null);
        SST_ASSERT(rIntPool_ == null);
        SST_ASSERT(rLongPool_ == null);
        SST_ASSERT(rBigPool_ == null);
        SST_ASSERT(rIntEntry_ == null);
        SST_ASSERT(rLongEntry_ == null);
        SST_ASSERT(rBigEntry_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(zIntPool_ == null);
        SST_ASSERT(zLongPool_ == null);
        SST_ASSERT(zBigPool_ == null);
        SST_ASSERT(zIntEntry_ == null);
        SST_ASSERT(zLongEntry_ == null);
        SST_ASSERT(zBigEntry_ == null);
        SST_ASSERT(zInt_ == null);
        SST_ASSERT(zLong_ == null);
        SST_ASSERT(zBig_ == null);
        SST_ASSERT(zBytes_ == null);
        SST_ASSERT(zIntQueue_ == null);
        SST_ASSERT(zLongQueue_ == null);
        SST_ASSERT(zBigQueue_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(aBytesBatchSh_ == null);
        SST_ASSERT(aIntBatch_ == null);
        SST_ASSERT(aLongBatch_ == null);
        SST_ASSERT(aBigBatch_ == null);
        SST_ASSERT(aBytesBatchRh_ == null);
        SST_ASSERT(aIntBatchPool_ == null);
        SST_ASSERT(aLongBatchPool_ == null);
        SST_ASSERT(aBigBatchPool_ == null);
        SST_ASSERT(aIntBatchEntry_ == null);
        SST_ASSERT(aLongBatchEntry_ == null);
        SST_ASSERT(aBigBatchEntry_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(bBytesBatchPool_ == null);
        SST_ASSERT(bBytesBatchEntry_ == null);
        SST_ASSERT(bIntBatch_ == null);
        SST_ASSERT(bLongBatch_ == null);
        SST_ASSERT(bBigBatch_ == null);
        SST_ASSERT(bBytesBatchQueue_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(xIntBatchPool_ == null);
        SST_ASSERT(xLongBatchPool_ == null);
        SST_ASSERT(xBigBatchPool_ == null);
        SST_ASSERT(xIntBatchEntry_ == null);
        SST_ASSERT(xLongBatchEntry_ == null);
        SST_ASSERT(xBigBatchEntry_ == null);
        SST_ASSERT(xIntBatch_ == null);
        SST_ASSERT(xLongBatch_ == null);
        SST_ASSERT(xBigBatch_ == null);
        SST_ASSERT(xBatchFuture_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(generateABBatchFuture_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(localRowCount_ == -1);
        SST_ASSERT(localRowCountSh_ == -1);
        SST_ASSERT(localRowCountRh_ == -1);
        SST_ASSERT(localRowIndex_ == -1);
        SST_ASSERT(localRowBatch_ == -1);

        SST_ASSERT(otherRowCount_ == -1);
        SST_ASSERT(otherRowCountSh_ == -1);
        SST_ASSERT(otherRowCountRh_ == -1);
        SST_ASSERT(otherRowIndex_ == -1);
        SST_ASSERT(otherRowBatch_ == -1);

        if (localPartyIsDb_) {
          SST_ASSERT(otherRowCountQueue_.isEmpty());
        } else {
          SST_ASSERT(otherRowCountQueue_ == null);
        }

        //--------------------------------------------------------------

        SST_ASSERT(xaBytesSize_ == -1);
        SST_ASSERT(xaBytesBatch_ == null);
        SST_ASSERT(xaFuture_ == null);
        SST_ASSERT(xaBytesBatchPool_ == null);
        SST_ASSERT(xaBytesBatchEntry_ == null);
        SST_ASSERT(xaBytesBatchQueue_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(ybBytesBatchQueue_ == null);
        SST_ASSERT(ybBytesBatchEntry_ == null);
        SST_ASSERT(ybBytesBatch_ == null);
        SST_ASSERT(ybIntBatch_ == null);
        SST_ASSERT(ybLongBatch_ == null);
        SST_ASSERT(ybBigBatch_ == null);

        //--------------------------------------------------------------

        SST_ASSERT(sInt_ == null);
        SST_ASSERT(sLong_ == null);
        SST_ASSERT(sBig_ == null);
        SST_ASSERT(sBytesPool_ == null);
        SST_ASSERT(sBytesEntry_ == null);
        SST_ASSERT(sBytes_ == null);
        SST_ASSERT(sFuture_ == null);
        SST_ASSERT(sBytesQueue_ == null);

        //--------------------------------------------------------------
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    localAggCount_ = query_.aggCount(localDb_);
    otherAggCount_ = query_.aggCount(otherDb_);
    final int localAggCount = localAggCount_;
    final int otherAggCount = otherAggCount_;

    if (localPartyIsDb_ && stateStream_ == StateStream.S1) {
      zeroScaleFpmContext_ = new FixedPointModContext(modulus_, 0);
      final List<Aggregate> xs = query_.aggregates(localDb_);
      final int n = xs.size();
      fixedPointModContexts_ = new FixedPointModContext[n];
      for (int i = 0; i < n; ++i) {
        fixedPointModContexts_[i] =
            new FixedPointModContext(modulus_,
                                     xs.get(i).column().scale());
      }
    }

    // TODO: calculate these from the config, agg counts, and
    // valueSize_.
    localMaxBatch_ = 1000;
    otherMaxBatch_ = 1000;
    final int localMaxBatch = localMaxBatch_;
    final int otherMaxBatch = otherMaxBatch_;

    shd_ = globals_.createOrGetSharedHandlerData(this, queryId_);
    shd_.handlers.get(remoteParty_).put(stateStream_, this);
    shd_.pipelines.get(remoteParty_).get(stateStream_).set(pipeline_);

    activeQuery_ = globals_.getOrPutActiveQuery(queryId_);
    activeQuery_.rawHandlers().add(this);

    if (localPartyIsPh_ || remotePartyIsPh_) {
      domainIterator_ = new DomainIterator(config_, query_);
    } else {
      domainIteratorSh_ = new DomainIterator(config_, query_);
      domainIteratorRh_ = new DomainIterator(config_, query_);
    }

    //------------------------------------------------------------------

    if (localPartyIsPh_
        && (stateStream_ == StateStream.S1
            || stateStream_ == StateStream.S3)) {
      phDbS1ToPhDbS3BothRowCountsQueue_ =
          shd_.phDbS1ToPhDbS3BothRowCountsQueues.get(localDb_);
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsDb_) {
      rBytesSh_ = new byte[otherAggCount * valueSize_];
      if (valuesFitInt_) {
        rInt_ = new int[otherAggCount];
      } else if (valuesFitLong_) {
        rLong_ = new long[otherAggCount];
      } else {
        rBig_ = new BigInteger[otherAggCount];
      }
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsDb_) {
      rBytesRh_ = new byte[localAggCount * valueSize_];
      if (valuesFitInt_) {
        rIntPool_ = new ConcurrentPool<int[]>(new Supplier<int[]>() {
          @Override
          public final int[] get() {
            return new int[localAggCount];
          }
        });
      } else if (valuesFitLong_) {
        rLongPool_ = new ConcurrentPool<long[]>(new Supplier<long[]>() {
          @Override
          public final long[] get() {
            return new long[localAggCount];
          }
        });
      } else {
        rBigPool_ = new ConcurrentPool<BigInteger[]>(
            new Supplier<BigInteger[]>() {
              @Override
              public final BigInteger[] get() {
                return new BigInteger[localAggCount];
              }
            });
      }
    }

    //------------------------------------------------------------------

    if ((localPartyIsDb_ && remotePartyIsDb_)
        || (localPartyIsPh_ && remotePartyIsDb_
            && stateStream_ == StateStream.S1)) {
      if (valuesFitInt_) {
        zIntPool_ = new ConcurrentPool<int[]>(new Supplier<int[]>() {
          @Override
          public final int[] get() {
            return new int[otherAggCount];
          }
        });
      } else if (valuesFitLong_) {
        zLongPool_ = new ConcurrentPool<long[]>(new Supplier<long[]>() {
          @Override
          public final long[] get() {
            return new long[otherAggCount];
          }
        });
      } else {
        zBigPool_ = new ConcurrentPool<BigInteger[]>(
            new Supplier<BigInteger[]>() {
              @Override
              public final BigInteger[] get() {
                return new BigInteger[otherAggCount];
              }
            });
      }
    }

    if ((localPartyIsDb_ && remotePartyIsPh_
         && stateStream_ == StateStream.S1)
        || (localPartyIsPh_ && remotePartyIsDb_
            && stateStream_ == StateStream.S1)) {
      zBytes_ = new byte[otherAggCount * valueSize_];
    }

    if ((localPartyIsDb_ && remotePartyIsDb_
         && stateStream_ == StateStream.S1)
        || (localPartyIsDb_ && remotePartyIsPh_
            && stateStream_ == StateStream.S1)) {
      zIntQueue_ = shd_.zIntQueue1;
      zLongQueue_ = shd_.zLongQueue1;
      zBigQueue_ = shd_.zBigQueue1;
    }
    if (localPartyIsPh_ && remotePartyIsDb_
        && stateStream_ == StateStream.S1) {
      if (localDb_ == Party.DB1) {
        zIntQueue_ = shd_.zIntQueue2;
        zLongQueue_ = shd_.zLongQueue2;
        zBigQueue_ = shd_.zBigQueue2;
      } else {
        zIntQueue_ = shd_.zIntQueue1;
        zLongQueue_ = shd_.zLongQueue1;
        zBigQueue_ = shd_.zBigQueue1;
      }
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsDb_) {
      aBytesBatchSh_ =
          new byte[otherMaxBatch * otherAggCount * valueSize_];
      if (valuesFitInt_) {
        aIntBatch_ = new int[otherMaxBatch * otherAggCount];
      } else if (valuesFitLong_) {
        aLongBatch_ = new long[otherMaxBatch * otherAggCount];
      } else {
        aBigBatch_ = new BigInteger[otherMaxBatch * otherAggCount];
      }
    }

    if (localPartyIsDb_ && remotePartyIsDb_) {
      aBytesBatchRh_ =
          new byte[localMaxBatch * localAggCount * valueSize_];
      if (valuesFitInt_) {
        aIntBatchPool_ =
            new ConcurrentPool<int[]>(new Supplier<int[]>() {
              @Override
              public final int[] get() {
                return new int[localMaxBatch * localAggCount];
              }
            });
      } else if (valuesFitLong_) {
        aLongBatchPool_ =
            new ConcurrentPool<long[]>(new Supplier<long[]>() {
              @Override
              public final long[] get() {
                return new long[localMaxBatch * localAggCount];
              }
            });
      } else {
        aBigBatchPool_ = new ConcurrentPool<BigInteger[]>(
            new Supplier<BigInteger[]>() {
              @Override
              public final BigInteger[] get() {
                return new BigInteger[localMaxBatch * localAggCount];
              }
            });
      }
    }

    //------------------------------------------------------------------

    if ((localPartyIsDb_ && remotePartyIsDb_)
        || (localPartyIsPh_ && remotePartyIsDb_
            && stateStream_ == StateStream.S2)) {
      bBytesBatchPool_ =
          new ConcurrentPool<byte[]>(new Supplier<byte[]>() {
            @Override
            public final byte[] get() {
              return new byte[otherMaxBatch * valueSize_];
            }
          });
    }

    if (localPartyIsDb_ && remotePartyIsDb_) {
      if (valuesFitInt_) {
        bIntBatch_ = new int[otherMaxBatch];
      } else if (valuesFitLong_) {
        bLongBatch_ = new long[otherMaxBatch];
      } else {
        bBigBatch_ = new BigInteger[otherMaxBatch];
      }
    }

    if ((localPartyIsDb_ && remotePartyIsDb_)
        || (localPartyIsDb_ && remotePartyIsPh_
            && stateStream_ == StateStream.S2)) {
      bBytesBatchQueue_ = shd_.bBytesBatchQueues.get(Party.PH);
    } else if (localPartyIsPh_ && remotePartyIsDb_
               && stateStream_ == StateStream.S2) {
      bBytesBatchQueue_ = shd_.bBytesBatchQueues.get(otherDb_);
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsPh_
        && stateStream_ == StateStream.S1) {
      if (valuesFitInt_) {
        xIntBatchPool_ =
            new ConcurrentPool<XIntBatch>(new Supplier<XIntBatch>() {
              @Override
              public final XIntBatch get() {
                return new XIntBatch(localMaxBatch,
                                     localAggCount,
                                     linkingColumnSize_);
              }
            });
      } else if (valuesFitLong_) {
        xLongBatchPool_ =
            new ConcurrentPool<XLongBatch>(new Supplier<XLongBatch>() {
              @Override
              public final XLongBatch get() {
                return new XLongBatch(localMaxBatch,
                                      localAggCount,
                                      linkingColumnSize_);
              }
            });
      } else {
        xBigBatchPool_ =
            new ConcurrentPool<XBigBatch>(new Supplier<XBigBatch>() {
              @Override
              public final XBigBatch get() {
                return new XBigBatch(localMaxBatch,
                                     localAggCount,
                                     linkingColumnSize_);
              }
            });
      }
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsPh_
        && stateStream_ == StateStream.S1) {
      xaBytesSize_ = linkingColumnSize_ + localAggCount * valueSize_;
      xaBytesBatch_ = new byte[localMaxBatch * xaBytesSize_];
    } else if (localPartyIsPh_ && stateStream_ == StateStream.S1) {
      xaBytesSize_ = linkingColumnSize_ + localAggCount * valueSize_;
      final int xaBytesSize = xaBytesSize_;
      xaBytesBatchPool_ =
          new ConcurrentPool<byte[]>(new Supplier<byte[]>() {
            @Override
            public final byte[] get() {
              return new byte[localMaxBatch * xaBytesSize];
            }
          });
      xaBytesBatchQueue_ = shd_.xaBytesBatchQueues.get(remoteParty_);
    }

    //------------------------------------------------------------------

    if (localPartyIsPh_ && stateStream_ == StateStream.S3) {
      ybBytesBatchQueue_ = shd_.ybBytesBatchQueues.get(localDb_);
    }
    if (localPartyIsDb_ && remotePartyIsPh_
        && stateStream_ == StateStream.S3) {
      ybBytesBatch_ = new byte[localMaxBatch * valueSize_];
      if (valuesFitInt_) {
        ybIntBatch_ = new int[localMaxBatch];
      } else if (valuesFitLong_) {
        ybLongBatch_ = new long[localMaxBatch];
      } else {
        ybBigBatch_ = new BigInteger[localMaxBatch];
      }
    }

    //------------------------------------------------------------------

    if (localPartyIsDb_ && remotePartyIsPh_
        && stateStream_ == StateStream.S3) {
      if (valuesFitInt_) {
        sInt_ = new int[localAggCount_];
      } else if (valuesFitLong_) {
        sLong_ = new long[localAggCount_];
      } else {
        sBig_ = new BigInteger[localAggCount_];
      }
      sBytes_ = new byte[localAggCount_ * valueSize_];
    }
    if (localPartyIsPh_ && remotePartyIsDb_
        && stateStream_ == StateStream.S3) {
      sBytesPool_ = new ConcurrentPool<byte[]>(new Supplier<byte[]>() {
        @Override
        public final byte[] get() {
          return new byte[localAggCount * valueSize_];
        }
      });
      if (localDb_ == Party.DB1) {
        sBytesQueue_ = shd_.sBytesQueue1;
      } else {
        sBytesQueue_ = shd_.sBytesQueue2;
      }
    }

    //------------------------------------------------------------------
  }

  //--------------------------------------------------------------------

  private String countSql_;
  private String countSqlFormat_;
  private PreparedStatement countStatement_;
  private ResultSet countResult_;

  private String fullSql_;
  private String fullSqlFormat_;
  private PreparedStatement fullStatement_;
  private ResultSet fullResult_;
  private ResultSetMetaData fullResultMetadata_;

  private ArrayList<Object> allParameters_;
  private List<Object> domainParameters_;

  //--------------------------------------------------------------------
  // PH-DB query initiation
  //--------------------------------------------------------------------

  private void handlePhStartQueryEvent(final ChannelHandlerContext ctx,
                                       final PhStartQueryEvent event)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(event != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    stateStream_ = event.stateStream();
    updateLogPrefix();
    channelPoolEntry_ = event.rawChannel();
    queryString_ = event.queryString();
    queryId_ = event.queryId();
    query_ = event.query();
    gotQuery();
    tick(ctx);
  }

  //--------------------------------------------------------------------
  // Outgoing DB-DB query initiation
  //--------------------------------------------------------------------

  private DbStartQueryEvent dbStartQueryEvent_;

  private void
  handleDbStartQueryEvent(final ChannelHandlerContext ctx,
                          final DbStartQueryEvent dbStartQueryEvent)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(dbStartQueryEvent != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    stateStream_ = StateStream.S1;
    updateLogPrefix();
    dbStartQueryEvent_ = dbStartQueryEvent;
    channelPoolEntry_ = dbStartQueryEvent_.channel();
    queryString_ = dbStartQueryEvent_.queryString();
    queryId_ = dbStartQueryEvent_.queryId();
    query_ = dbStartQueryEvent_.query();
    gotQuery();
    tick(ctx);
  }

  //--------------------------------------------------------------------
  // Error handling
  //--------------------------------------------------------------------
  //
  // Every @Override method in this class except for exceptionCaught()
  // should begin by running "if (checkFatal(ctx)) { return; }". This
  // makes the method become a noop when a fatal error has previously
  // occurred.
  //
  // asyncFatal() can be called at any time from another thread to flag
  // a fatal error in this handler. This is usually called from future
  // listeners or other lambdas that run in other threads.
  //

  private final AtomicBoolean fatal_ = new AtomicBoolean(false);

  public final boolean isFatal() {
    return fatal_.get();
  }

  @Override
  public final void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(cause != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    fatal_.set(true);
    try {
      jdbcRunner_.close();
    } catch (final Throwable e) {
    }
    try {
      ctx.close();
    } catch (final Throwable e) {
    }
    try {
      // TODO: probably output this to the log
      synchronized (globals_.stderr()) {
        cause.printStackTrace(globals_.stderr());
      }
    } catch (final Throwable e) {
    }
  }

  private boolean checkFatal(final ChannelHandlerContext ctx) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final boolean f = fatal_.get();
    if (f) {
      exceptionCaught(ctx, new RuntimeException("post-fatal call"));
    }
    return f;
  }

  public void asyncFatal(final Throwable cause) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(cause != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    fatal_.set(true);
    try {
      pipeline_.fireExceptionCaught(cause);
    } catch (final Throwable e) {
    }
  }

  //--------------------------------------------------------------------
  // Logging
  //--------------------------------------------------------------------

  private static final AtomicLong handlerIdCounter_ = new AtomicLong(0);
  private final long handlerId_ = handlerIdCounter_.incrementAndGet();
  private String logPrefix_;

  private void updateLogPrefix() {
    logPrefix_ =
        config_.localParty().toString() + "-"
        + (remoteParty_ == null ? "?" : remoteParty_.toString()) + "-"
        + (stateStream_ == null ? "?" : stateStream_.toString()) + "#"
        + String.valueOf(handlerId_) + ": ";
  }

  private void log(final CharSequence message) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(message != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final StringBuilder s = new StringBuilder();
    globals_.logFile().timestamp(s);
    s.append(logPrefix_);
    s.append(message);
    s.append('\n');
    globals_.logFile().write(s.toString());
  }

  //--------------------------------------------------------------------
  // Socket I/O
  //--------------------------------------------------------------------
  //
  // in_ stores the bytes sent by the remote party that are waiting to
  // be consumed. We have AUTO_READ disabled, so in_ will only grow up
  // to some bounded size depending on how we call ctx.read(). Every
  // chunk of incoming bytes will wake up the state machine.
  //
  // out_ stores the number of bytes sent to the remote party that have
  // not finished sending yet. In general, we make the state machine go
  // to sleep when it wants to send something but out_ > outLimit_, and
  // every send completion will wake up the state machine if it caused
  // out_ <= outLimit_ to change from false to true. Analagous to in_,
  // out_ will only grow up to some bounded size depending on how we
  // call write().
  //

  private final ByteBuf in_ = Unpooled.buffer();
  private final AtomicInteger out_ = new AtomicInteger(0);
  private int outLimit_ = -1;
  private int outDelta_ = 0;
  private ChannelFuture outFuture_;

  @Override
  protected final void channelRead0(final ChannelHandlerContext ctx,
                                    final ByteBuf buf)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(buf != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (checkFatal(ctx)) {
      return;
    }
    try {
      in_.discardSomeReadBytes();
      in_.writeBytes(buf);
      tick(ctx);
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
    updateLastActivityTime();
  }

  private final void write(final ChannelHandlerContext ctx,
                           final ByteBuf src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(outLimit_ >= 0);
        SST_ASSERT(ctx != null);
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    outDelta_ += src.readableBytes();
    outFuture_ = ctx.write(src);
  }

  private final void write(final ChannelHandlerContext ctx,
                           final byte[] src,
                           final int srcPos,
                           final int srcLen) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(src != null);
        SST_ASSERT(srcPos >= 0);
        SST_ASSERT(srcPos <= src.length);
        SST_ASSERT(srcLen >= 0);
        SST_ASSERT(srcLen <= src.length - srcPos);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final ByteBuf x = ctx.alloc().buffer();
    try {
      x.writeBytes(src, srcPos, srcLen);
      write(ctx, x);
    } catch (final Throwable e) {
      x.release();
      throw e;
    }
  }

  private final void write(final ChannelHandlerContext ctx,
                           final byte[] src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    write(ctx, src, 0, src.length);
  }

  private final void flush(final ChannelHandlerContext ctx) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final ChannelPipeline pipeline = pipeline_;
    final int outDelta = outDelta_;
    final int outLimit = outLimit_;
    out_.addAndGet(outDelta);
    outFuture_.addListener(new FutureListener<Object>() {
      @Override
      public final void operationComplete(final Future<Object> future) {
        try {
          future.sync();
          final int k = out_.addAndGet(-outDelta);
          if (k <= outLimit && k + outDelta > outLimit) {
            fireTick(pipeline);
          }
        } catch (final Throwable e) {
          asyncFatal(e);
        }
      }
    });
    ctx.flush();
    outDelta_ = 0;
  }

  //--------------------------------------------------------------------

  private enum State {
    SEND_PARTY,
    RECV_PARTY,
    SEND_LEXICON,
    RECV_LEXICON_1,
    RECV_LEXICON_2,
    SEND_QUERY,
    RECV_QUERY_1,
    RECV_QUERY_2,

    PH_DB_S1_NEXT_DOMAIN_TUPLE,
    PH_DB_S1_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1,
    PH_DB_S1_SEND_LOCAL_ROW_COUNT_TO_MERGE_MACHINE,
    PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S3,
    PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_OTHER_PH_DB_S2,
    PH_DB_S1_NEXT_LOCAL_ROW_BATCH,
    PH_DB_S1_RECV_XA_BATCH_FROM_DB_PH_S1,
    PH_DB_S1_SEND_XA_BATCH_TO_MERGE_MACHINE,
    PH_DB_S1_RECV_Z_FROM_DB_PH_S1,
    PH_DB_S1_SEND_Z_TO_MERGE_MACHINE,

    PH_DB_S2_NEXT_DOMAIN_TUPLE,
    PH_DB_S2_RECV_BOTH_ROW_COUNTS_FROM_OTHER_PH_DB_S1,
    PH_DB_S2_NEXT_B_BATCH,
    PH_DB_S2_RECV_B_BATCH_FROM_DB_PH_S2,
    PH_DB_S2_SEND_B_BATCH_TO_MERGE_MACHINE,

    PH_DB_S3_NEXT_DOMAIN_TUPLE,
    PH_DB_S3_RECV_BOTH_ROW_COUNTS_FROM_PH_DB_S1,
    PH_DB_S3_NEXT_YB_BATCH,
    PH_DB_S3_RECV_YB_BATCH_FROM_MERGE_MACHINE,
    PH_DB_S3_SEND_YB_BATCH_TO_DB_PH_S3,
    PH_DB_S3_RECV_S_FROM_DB_PH_S3,
    PH_DB_S3_SEND_S_TO_MERGE_MACHINE,

    DB_PH_S1_CONNECT_TO_DATABASE,
    DB_PH_S1_NEXT_DOMAIN_TUPLE,
    DB_PH_S1_DO_COUNT_QUERY,
    DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_SH,
    DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_RH,
    DB_PH_S1_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH,
    DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S3,
    DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S1,
    DB_PH_S1_DO_FULL_QUERY,
    DB_PH_S1_NEXT_LOCAL_ROW_BATCH,
    DB_PH_S1_START_RETRIEVING_X_BATCH,
    DB_PH_S1_RECV_A_BATCH_FROM_DB_DB_RH,
    DB_PH_S1_FINISH_RETRIEVING_X_BATCH,
    DB_PH_S1_START_COMPUTING_XA_BATCH,
    DB_PH_S1_FINISH_COMPUTING_XA_BATCH,
    DB_PH_S1_SEND_XA_BATCH_TO_PH_DB_S1,
    DB_PH_S1_SEND_X_BATCH_TO_DB_PH_S3,
    DB_PH_S1_RECV_Z_FROM_DB_DB_SH,
    DB_PH_S1_SEND_Z_TO_PH_DB_S1,

    DB_PH_S2_NEXT_DOMAIN_TUPLE,
    DB_PH_S2_RECV_BOTH_ROW_COUNTS_FROM_DB_DB_RH,
    DB_PH_S2_NEXT_B_BATCH,
    DB_PH_S2_RECV_B_BATCH_FROM_DB_DB_SH,
    DB_PH_S2_SEND_B_BATCH_TO_PH_DB_S2,

    DB_PH_S3_NEXT_DOMAIN_TUPLE,
    DB_PH_S3_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1,
    DB_PH_S3_RECV_R_FROM_DB_DB_RH,
    DB_PH_S3_NEXT_LOCAL_ROW_BATCH,
    DB_PH_S3_RECV_X_BATCH_FROM_DB_PH_S1,
    DB_PH_S3_RECV_YB_BATCH_FROM_PH_DB_S3,
    DB_PH_S3_START_COMPUTING_S_ITERATION,
    DB_PH_S3_FINISH_COMPUTING_S_ITERATION,
    DB_PH_S3_FINISH_COMPUTING_S,
    DB_PH_S3_SEND_S_TO_PH_DB_S3,

    DB_DB_DUPLEX,

    DB_DB_SH_NEXT_DOMAIN_TUPLE,
    DB_DB_SH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1,
    DB_DB_SH_SEND_LOCAL_ROW_COUNT_TO_REMOTE_DB_DB_RH,
    DB_DB_SH_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH,
    DB_DB_SH_START_COMPUTING_Z,
    DB_DB_SH_GENERATE_R,
    DB_DB_SH_SEND_R_TO_REMOTE_DB_DB_RH,
    DB_DB_SH_NEXT_OTHER_ROW_BATCH,
    DB_DB_SH_START_GENERATING_A_B_BATCH,
    DB_DB_SH_FINISH_GENERATING_A_B_BATCH,
    DB_DB_SH_SEND_B_BATCH_TO_DB_PH_S2,
    DB_DB_SH_SEND_A_BATCH_TO_REMOTE_DB_DB_RH,
    DB_DB_SH_FINISH_COMPUTING_Z,
    DB_DB_SH_SEND_Z_TO_DB_PH_S1,
    DB_DB_SH_DONE,

    DB_DB_RH_NEXT_DOMAIN_TUPLE,
    DB_DB_RH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1,
    DB_DB_RH_RECV_OTHER_ROW_COUNT_FROM_REMOTE_DB_DB_SH,
    DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_DB_SH,
    DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_PH_S1,
    DB_DB_RH_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S2,
    DB_DB_RH_RECV_R_FROM_REMOTE_DB_DB_SH,
    DB_DB_RH_SEND_R_TO_DB_PH_S3,
    DB_DB_RH_NEXT_LOCAL_ROW_BATCH,
    DB_DB_RH_RECV_A_BATCH_FROM_REMOTE_DB_DB_SH,
    DB_DB_RH_SEND_A_BATCH_TO_DB_PH_S1,
    DB_DB_RH_DONE,

    DONE_QUERY;
  }

  private State state_;
  private State senderState_;
  private State recverState_;

  private void setState(final State state) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(state != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    //log("state: " + state.name());
    state_ = state;
  }

  private void setSenderState(final State senderState) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(senderState != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    //log("send state: " + senderState.name());
    senderState_ = senderState;
  }

  private void setRecverState(final State recverState) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(recverState != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    //log("recv state: " + recverState.name());
    recverState_ = recverState;
  }

  private boolean inIdleState() {
    if (outgoing_) {
      return state_ == State.SEND_PARTY || state_ == State.SEND_QUERY;
    } else {
      return state_ == State.RECV_PARTY || state_ == State.RECV_QUERY_1;
    }
  }

  //--------------------------------------------------------------------

  private static final Object TICK = new Object();

  private static final void fireTick(final ChannelPipeline pipeline) {
    // null means the pipeline hasn't registered itself yet, in which
    // case there's no reason to fire a tick because it will reach a
    // tick on its own.
    if (pipeline != null) {
      pipeline.fireUserEventTriggered(TICK);
    }
  }

  private final void fireTick(final Party remoteParty,
                              final StateStream stateStream) {
    fireTick(shd_.pipelines.get(remoteParty).get(stateStream).get());
  }

  private final void fireTick(final MergeMachine mergeMachine) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(mergeMachine != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (mergeMachine.tickDepth.getAndIncrement() == 0) {
      globals_.workerThreadGroup().submit(new Runnable() {
        @Override
        public final void run() {
          try {
            mergeMachine.tick();
          } catch (final Throwable e) {
            asyncFatal(e);
          }
        }
      });
    }
  }

  private <T> Consumer<T> onSuccess(final Object event) {
    return new Consumer<T>() {
      @Override
      public final void accept(final T x) {
        try {
          pipeline_.fireUserEventTriggered(event);
        } catch (final Throwable e) {
          asyncFatal(e);
        }
      }
    };
  }

  private Consumer<Throwable> onFailure() {
    return new Consumer<Throwable>() {
      @Override
      public final void accept(final Throwable e) {
        asyncFatal(e);
      }
    };
  }

  //--------------------------------------------------------------------
  // Zombie checking
  //--------------------------------------------------------------------

  private static final Object ZOMBIE_CHECK = new Object();

  private void scheduleNextZombieCheck() {
    globals_.workerThreadGroup().schedule(new Runnable() {
      @Override
      public final void run() {
        try {
          pipeline_.fireUserEventTriggered(ZOMBIE_CHECK);
        } catch (final Throwable e) {
          asyncFatal(e);
        }
      }
    }, config_.zombieCheckCooldown(), TimeUnit.SECONDS);
  }

  private void handleZombieCheck(final ChannelHandlerContext ctx) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (inIdleState()) {
      log("handler zombie check: not a zombie: in idle state");
      scheduleNextZombieCheck();
    } else {
      final long a =
          (System.nanoTime() - lastActivityTime()) / 1000000000;
      final long b = globals_.config().zombieCheckThreshold();
      if (a > b) {
        log("handler zombie check: zombie: " + a
            + " seconds idle > zombie_check_threshold = " + b
            + " seconds");
        exceptionCaught(ctx, new RuntimeException("zombie"));
      } else {
        log("handler zombie check: not a zombie: " + a
            + " seconds idle <= zombie_check_threshold = " + b
            + " seconds");
        scheduleNextZombieCheck();
      }
    }
  }

  //--------------------------------------------------------------------

  public RawChannelHandler(final Globals globals,
                           final ChannelPipeline pipeline,
                           final Party remoteParty) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(globals != null);
        SST_ASSERT(pipeline != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    globals_ = globals;
    config_ = globals.config();
    updateLogPrefix();
    pipeline_ = pipeline;
    outgoing_ = remoteParty != null;

    remoteParty_ = remoteParty;
    updateLogPrefix();
    if (outgoing_) {
      remotePartyIsDb_ = remoteParty_.isDb();
      remotePartyIsPh_ = !remotePartyIsDb_;
      outLimit_ = config_.channelOutputBufferLimit().get(remoteParty_);
    }
    lexicon_ = config_.lexicon();
    guidSize_ = lexicon_.common().guidSize();
    linkingColumnSize_ = lexicon_.common().linkingColumnSize();
    localParty_ = config_.localParty();
    localPartyIsDb_ = localParty_.isDb();
    localPartyIsPh_ = !localPartyIsDb_;
    if (localPartyIsPh_) {
      linkingColumnJdbcType_ = null;
      linkingColumnIsString_ = false;
      previousId_ = null;
    } else {
      linkingColumnJdbcType_ = lexicon_.dbInfos()
                                   .get(localParty_)
                                   .table()
                                   .linkingColumn()
                                   .jdbcType();
      linkingColumnIsString_ =
          linkingColumnJdbcType_ == JdbcType.CHAR
          || linkingColumnJdbcType_ == JdbcType.VARCHAR
          || linkingColumnJdbcType_ == JdbcType.NCHAR
          || linkingColumnJdbcType_ == JdbcType.NVARCHAR;
      previousId_ = new byte[linkingColumnSize_];
    }
    linkingColumnUnicode_ = lexicon_.common().linkingColumnUnicode();
    linkingColumnForceString_ =
        lexicon_.common().linkingColumnForceString();
    localDb_ = localPartyIsDb_ ? localParty_ : remoteParty_;
    otherDb_ = localDb_ == Party.DB1 ? Party.DB2 : Party.DB1;
    localTable_ = lexicon_.dbInfos().get(localDb_).table();
    otherTable_ = lexicon_.dbInfos().get(otherDb_).table();
    randMod_ = new RandModContext(lexicon_.common().modulus());
    randMod2_ = new RandModContext(lexicon_.common().modulus());
    valueSize_ = randMod_.valueSize();
    valuesFitInt_ = randMod_.valuesFit((Integer)null);
    valuesFitLong_ = randMod_.valuesFit((Long)null);
    modulus_ = randMod_.modulus();
    if (valuesFitInt_) {
      modulusInt_ = modulus_.get((Integer)null);
      modulusLong_ = modulus_.get((Long)null);
      modulusBig_ = modulus_.get((BigInteger)null);
    } else if (valuesFitLong_) {
      modulusInt_ = -1;
      modulusLong_ = modulus_.get((Long)null);
      modulusBig_ = modulus_.get((BigInteger)null);
    } else {
      modulusInt_ = -1;
      modulusLong_ = -1;
      modulusBig_ = modulus_.get((BigInteger)null);
    }
    if (valueSize_ == Integer.MAX_VALUE) {
      throw new RuntimeException();
    }
    randModDstBuf_ = new byte[valueSize_ + 1];
    randModDstBuf2_ = new byte[valueSize_ + 1];

    if (localPartyIsDb_) {
      otherRowCountQueue_ = new LinkedList<Long>();
    } else {
      otherRowCountQueue_ = null;
    }

    setState(outgoing_ ? State.SEND_PARTY : State.RECV_PARTY);

    resetForNextQuery();

    scheduleNextZombieCheck();
  }

  //--------------------------------------------------------------------
  // State machine
  //--------------------------------------------------------------------

  private final boolean tickSender(final ChannelHandlerContext ctx)
      throws Exception {
    boolean motion = false;
    while (true) {
      switch (senderState_) {

        case DB_DB_SH_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIteratorSh_.next()) {
            setSenderState(
                State.DB_DB_SH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1);
          } else {
            setSenderState(State.DB_DB_SH_DONE);
          }
        } break;

        case DB_DB_SH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          {
            final Long x = shd_.dbPhS1ToDbDbSsLocalRowCountQueue.poll();
            if (x == null) {
              return motion;
            }
            localRowCountSh_ = x;
          }
          fireTick(Party.PH, StateStream.S1);
          setSenderState(
              State.DB_DB_SH_SEND_LOCAL_ROW_COUNT_TO_REMOTE_DB_DB_RH);
        } break;

        case DB_DB_SH_SEND_LOCAL_ROW_COUNT_TO_REMOTE_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return motion;
          }
          {
            final ByteBuf x = ctx.alloc().buffer();
            try {
              x.writeLong(localRowCountSh_);
              write(ctx, x);
              flush(ctx);
            } catch (final Throwable e) {
              x.release();
              throw e;
            }
          }
          setSenderState(
              State.DB_DB_SH_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH);
        } break;

        case DB_DB_SH_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (otherRowCountQueue_.isEmpty()) {
            return motion;
          }
          otherRowCountSh_ = otherRowCountQueue_.removeFirst();
          if (localRowCountSh_ == 0 || otherRowCountSh_ == 0) {
            setSenderState(State.DB_DB_SH_NEXT_DOMAIN_TUPLE);
          } else {
            otherRowIndex_ = 0;
            setSenderState(State.DB_DB_SH_START_COMPUTING_Z);
          }
        } break;

        case DB_DB_SH_START_COMPUTING_Z: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(zIntEntry_ == null);
              SST_ASSERT(zLongEntry_ == null);
              SST_ASSERT(zBigEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            zIntEntry_ = zIntPool_.acquire();
            zInt_ = zIntEntry_.object();
            for (int i = 0; i < zInt_.length; ++i) {
              zInt_[i] = 0;
            }
          } else if (valuesFitLong_) {
            zLongEntry_ = zLongPool_.acquire();
            zLong_ = zLongEntry_.object();
            for (int i = 0; i < zLong_.length; ++i) {
              zLong_[i] = 0;
            }
          } else {
            zBigEntry_ = zBigPool_.acquire();
            zBig_ = zBigEntry_.object();
            for (int i = 0; i < zBig_.length; ++i) {
              zBig_[i] = BigInteger.ZERO;
            }
          }
          setSenderState(State.DB_DB_SH_GENERATE_R);
        } break;

        case DB_DB_SH_GENERATE_R: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(rBytesSh_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          randMod(rBytesSh_, otherAggCount_, randMod_);
          if (valuesFitInt_) {
            randMod(rBytesSh_, rInt_, otherAggCount_);
            //log("rInt_ = " + Rep.toString(rInt_));
          } else if (valuesFitLong_) {
            randMod(rBytesSh_, rLong_, otherAggCount_);
            //log("rLong_ = " + Rep.toString(rLong_));
          } else {
            randMod(rBytesSh_, rBig_, otherAggCount_, randModDstBuf_);
            //log("rBig_ = " + Rep.toString(rBig_));
          }
          setSenderState(State.DB_DB_SH_SEND_R_TO_REMOTE_DB_DB_RH);
        } break;

        case DB_DB_SH_SEND_R_TO_REMOTE_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(rBytesSh_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return motion;
          }
          write(ctx, rBytesSh_);
          flush(ctx);
          setSenderState(State.DB_DB_SH_NEXT_OTHER_ROW_BATCH);
        } break;

        case DB_DB_SH_NEXT_OTHER_ROW_BATCH: {
          if (!SST_NDEBUG) {
            try {
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (otherRowIndex_ < otherRowCountSh_) {
            otherRowBatch_ =
                (int)Math.min(otherRowCountSh_ - otherRowIndex_,
                              otherMaxBatch_);
            setSenderState(State.DB_DB_SH_START_GENERATING_A_B_BATCH);
          } else {
            setSenderState(State.DB_DB_SH_FINISH_COMPUTING_Z);
          }
        } break;

        case DB_DB_SH_START_GENERATING_A_B_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(aBytesBatchSh_ != null);
              SST_ASSERT(bBytesBatchEntry_ == null);
              SST_ASSERT(generateABBatchFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          bBytesBatchEntry_ = bBytesBatchPool_.acquire();
          final byte[] bBytesBatch = bBytesBatchEntry_.object();
          final int modulusInt = modulusInt_;
          final long modulusLong = modulusLong_;
          final BigInteger modulusBig = modulusBig_;
          final byte[] aBytesBatchSh = aBytesBatchSh_;
          final int otherRowBatch = otherRowBatch_;
          final int otherAggCount = otherAggCount_;
          final int[] zInt = zInt_;
          final long[] zLong = zLong_;
          final BigInteger[] zBig = zBig_;
          final int[] aIntBatch = aIntBatch_;
          final long[] aLongBatch = aLongBatch_;
          final BigInteger[] aBigBatch = aBigBatch_;
          final int[] bIntBatch = bIntBatch_;
          final long[] bLongBatch = bLongBatch_;
          final BigInteger[] bBigBatch = bBigBatch_;
          final RandModContext randMod2 = randMod2_;
          final byte[] randModDstBuf2 = new byte[valueSize_ + 1];
          generateABBatchFuture_ =
              globals_.workerThreadGroup().submit(new Runnable() {
                @Override
                public final void run() {
                  try {
                    randMod(aBytesBatchSh,
                            otherRowBatch * otherAggCount,
                            randMod2);
                    randMod(bBytesBatch, otherRowBatch, randMod2);
                    if (valuesFitInt_) {
                      randMod(aBytesBatchSh,
                              aIntBatch,
                              otherRowBatch * otherAggCount);
                      randMod(bBytesBatch, bIntBatch, otherRowBatch);
                      int ai = 0;
                      if (modulusInt == 0
                          || modulusInt == Integer.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusInt)) {
                        // m is a power of two in [1, 2^32]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          for (int k = 0; k < otherAggCount; ++k) {
                            zInt[k] += aIntBatch[ai] * bIntBatch[i];
                            ++ai;
                          }
                        }
                      } else if (modulusInt > 0) {
                        // m is a non-power-of-two in [1, 2^31]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          for (int k = 0; k < otherAggCount; ++k) {
                            zInt[k] = (int)(((long)zInt[k]
                                             + (long)aIntBatch[ai]
                                                   * (long)bIntBatch[i])
                                            % (long)modulusInt);
                            ++ai;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^31, 2^32]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          for (int k = 0; k < otherAggCount; ++k) {
                            zInt[k] = (int)Arith.unsignedMod(
                                Arith.toUnsignedLong(zInt[k])
                                    + Arith.toUnsignedLong(
                                          aIntBatch[ai])
                                          * Arith.toUnsignedLong(
                                              bIntBatch[i]),
                                Arith.toUnsignedLong(modulusInt));
                            ++ai;
                          }
                        }
                      }
                    } else if (valuesFitLong_) {
                      randMod(aBytesBatchSh,
                              aLongBatch,
                              otherRowBatch * otherAggCount);
                      randMod(bBytesBatch, bLongBatch, otherRowBatch);
                      int ai = 0;
                      if (modulusLong == 0
                          || modulusLong == Long.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusLong)) {
                        // m is a power of two in [2^33, 2^64]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          for (int k = 0; k < otherAggCount; ++k) {
                            zLong[k] += aLongBatch[ai] * bLongBatch[i];
                            ++ai;
                          }
                        }
                      } else if (modulusLong > 0) {
                        // m is a non-power-of-two in [2^32, 2^63]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          final BigInteger b =
                              BigInteger.valueOf(bLongBatch[i]);
                          for (int k = 0; k < otherAggCount; ++k) {
                            zLong[k] =
                                BigInteger.valueOf(zLong[k])
                                    .add(BigInteger
                                             .valueOf(aLongBatch[ai])
                                             .multiply(b))
                                    .remainder(modulusBig)
                                    .longValue();
                            ++ai;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^63, 2^64]
                        for (int i = 0; i < otherRowBatch; ++i) {
                          final BigInteger b =
                              Arith.toUnsignedBig(bLongBatch[i]);
                          for (int k = 0; k < otherAggCount; ++k) {
                            zLong[k] = Arith.toUnsignedBig(zLong[k])
                                           .add(Arith
                                                    .toUnsignedBig(
                                                        aLongBatch[ai])
                                                    .multiply(b))
                                           .remainder(modulusBig)
                                           .longValue();
                            ++ai;
                          }
                        }
                      }
                    } else {
                      randMod(aBytesBatchSh,
                              aBigBatch,
                              otherRowBatch * otherAggCount,
                              randModDstBuf2);
                      randMod(bBytesBatch,
                              bBigBatch,
                              otherRowBatch,
                              randModDstBuf2);
                      int ai = 0;
                      for (int i = 0; i < otherRowBatch; ++i) {
                        for (int k = 0; k < otherAggCount; ++k) {
                          zBig[k] = zBig[k]
                                        .add(aBigBatch[ai].multiply(
                                            bBigBatch[i]))
                                        .remainder(modulusBig);
                          ++ai;
                        }
                      }
                    }
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
          generateABBatchFuture_.addListener(
              new FutureListener<Object>() {
                @Override
                public final void operationComplete(
                    final Future<Object> future) {
                  try {
                    future.sync();
                    fireTick(pipeline_);
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
          setSenderState(State.DB_DB_SH_FINISH_GENERATING_A_B_BATCH);
        } break;

        case DB_DB_SH_FINISH_GENERATING_A_B_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(generateABBatchFuture_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!generateABBatchFuture_.isDone()) {
            return motion;
          }
          generateABBatchFuture_.sync();
          generateABBatchFuture_ = null;
          setSenderState(State.DB_DB_SH_SEND_B_BATCH_TO_DB_PH_S2);
        } break;

        case DB_DB_SH_SEND_B_BATCH_TO_DB_PH_S2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!bBytesBatchQueue_.offer(bBytesBatchEntry_)) {
            return motion;
          }
          bBytesBatchEntry_ = null;
          fireTick(Party.PH, StateStream.S2);
          setSenderState(
              State.DB_DB_SH_SEND_A_BATCH_TO_REMOTE_DB_DB_RH);
        } break;

        case DB_DB_SH_SEND_A_BATCH_TO_REMOTE_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          // Our batch size is not necessarily the same as the remote
          // batch size. It's a continuous stream, so it's okay.
          if (out_.get() > outLimit_) {
            return motion;
          }
          {
            final ByteBuf x = ctx.alloc().buffer();
            try {
              x.writeBytes(aBytesBatchSh_,
                           0,
                           otherRowBatch_ * otherAggCount_
                               * valueSize_);
              write(ctx, x);
              flush(ctx);
            } catch (final Throwable e) {
              x.release();
              throw e;
            }
          }
          otherRowIndex_ += otherRowBatch_;
          setSenderState(State.DB_DB_SH_NEXT_OTHER_ROW_BATCH);
        } break;

        case DB_DB_SH_FINISH_COMPUTING_Z: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(valuesFitInt_ ?
                             (zIntEntry_ != null && zLongEntry_ == null
                              && zBigEntry_ == null && rInt_ != null
                              && rLong_ == null && rBig_ == null) :
                             valuesFitLong_ ?
                             (zIntEntry_ == null && zLongEntry_ != null
                              && zBigEntry_ == null && rInt_ == null
                              && rLong_ != null && rBig_ == null) :
                             (zIntEntry_ == null && zLongEntry_ == null
                              && zBigEntry_ != null && rInt_ == null
                              && rLong_ == null && rBig_ != null));
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            final int modulus = modulusInt_;
            final int[] z = zIntEntry_.object();
            final int[] r = rInt_;
            if (modulus == 0 || Arith.isPowerOfTwo(modulus)) {
              // modulus is a power of two in [1, 2^32]
              for (int k = 0; k < otherAggCount_; ++k) {
                z[k] -= r[k];
              }
            } else if (modulus > 0) {
              // modulus is a non-power-of-two in [1, 2^31]
              for (int k = 0; k < otherAggCount_; ++k) {
                z[k] -= r[k];
                if (z[k] < 0) {
                  z[k] += modulus;
                }
              }
            } else {
              // modulus is a non-power-of-two in [2^31, 2^32]
              for (int k = 0; k < otherAggCount_; ++k) {
                long x = Arith.toUnsignedLong(z[k])
                         - Arith.toUnsignedLong(r[k]);
                if (x < 0) {
                  x += Arith.toUnsignedLong(modulus);
                }
                z[k] = (int)x;
              }
            }
            //log("zIntEntry_ = " + Rep.toString(zIntEntry_.object()));
          } else if (valuesFitLong_) {
            final long modulus = modulusLong_;
            final long[] z = zLongEntry_.object();
            final long[] r = rLong_;
            if (modulus == 0 || Arith.isPowerOfTwo(modulus)) {
              // modulus is a power of two in [2^33, 2^64]
              for (int k = 0; k < otherAggCount_; ++k) {
                z[k] -= r[k];
              }
            } else if (modulus > 0) {
              // modulus is a non-power-of-two in [2^32, 2^63]
              for (int k = 0; k < otherAggCount_; ++k) {
                z[k] -= r[k];
                if (z[k] < 0) {
                  z[k] += modulus;
                }
              }
            } else {
              // modulus is a non-power-of-two in [2^63, 2^64]
              final BigInteger m = Arith.toUnsignedBig(modulus);
              for (int k = 0; k < otherAggCount_; ++k) {
                BigInteger x = Arith.toUnsignedBig(z[k]).subtract(
                    Arith.toUnsignedBig(r[k]));
                if (x.signum() < 0) {
                  x = x.add(m);
                }
                z[k] = x.longValue();
              }
            }
            //log("zLongEntry_ = " + Rep.toString(zLongEntry_.object()));
          } else {
            final BigInteger modulus = modulusBig_;
            final BigInteger[] z = zBigEntry_.object();
            final BigInteger[] r = rBig_;
            for (int k = 0; k < otherAggCount_; ++k) {
              z[k] = z[k].subtract(r[k]);
              if (z[k].signum() < 0) {
                z[k] = z[k].add(modulus);
              }
            }
            //log("zBigEntry_ = " + Rep.toString(zBigEntry_.object()));
          }
          setSenderState(State.DB_DB_SH_SEND_Z_TO_DB_PH_S1);
        } break;

        case DB_DB_SH_SEND_Z_TO_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(valuesFitInt_ ?
                             (zIntEntry_ != null && zLongEntry_ == null
                              && zBigEntry_ == null) :
                             valuesFitLong_ ?
                             (zIntEntry_ == null && zLongEntry_ != null
                              && zBigEntry_ == null) :
                             (zIntEntry_ == null && zLongEntry_ == null
                              && zBigEntry_ != null));
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (!zIntQueue_.offer(zIntEntry_)) {
              return motion;
            }
            zIntEntry_ = null;
            zInt_ = null;
          } else if (valuesFitLong_) {
            if (!zLongQueue_.offer(zLongEntry_)) {
              return motion;
            }
            zLongEntry_ = null;
            zLong_ = null;
          } else {
            if (!zBigQueue_.offer(zBigEntry_)) {
              return motion;
            }
            zBigEntry_ = null;
            zBig_ = null;
          }
          fireTick(Party.PH, StateStream.S1);
          setSenderState(State.DB_DB_SH_NEXT_DOMAIN_TUPLE);
        } break;

        case DB_DB_SH_DONE: {
          if (!SST_NDEBUG) {
            try {
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (true) {
            return motion;
          }
        } break;

        default:
          throw new ImpossibleException();
      }
      motion = true;
    }
  }

  private final boolean tickRecver(final ChannelHandlerContext ctx)
      throws Exception {
    boolean motion = false;
    while (true) {
      switch (recverState_) {

        case DB_DB_RH_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIteratorRh_.next()) {
            setRecverState(
                State.DB_DB_RH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1);
          } else {
            setRecverState(State.DB_DB_RH_DONE);
          }
        } break;

        case DB_DB_RH_RECV_LOCAL_ROW_COUNT_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          {
            final Long x = shd_.dbPhS1ToDbDbRsLocalRowCountQueue.poll();
            if (x == null) {
              return motion;
            }
            localRowCountRh_ = x;
          }
          fireTick(Party.PH, StateStream.S1);
          localRowIndex_ = 0;
          setRecverState(
              State.DB_DB_RH_RECV_OTHER_ROW_COUNT_FROM_REMOTE_DB_DB_SH);
        } break;

        case DB_DB_RH_RECV_OTHER_ROW_COUNT_FROM_REMOTE_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < 8) {
            ctx.read();
            return motion;
          }
          otherRowCountRh_ = in_.readLong();
          setRecverState(
              State.DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_DB_SH);
        } break;

        case DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCountRh_ >= 0);
              SST_ASSERT(otherRowCountRh_ >= 0);
              SST_ASSERT(otherRowCountQueue_.size()
                         <= otherRowCountQueueLimit_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (otherRowCountQueue_.size() == otherRowCountQueueLimit_) {
            return motion;
          }
          otherRowCountQueue_.addLast(otherRowCountRh_);
          setRecverState(
              State.DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_PH_S1);
        } break;

        case DB_DB_RH_SEND_OTHER_ROW_COUNT_TO_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCountRh_ >= 0);
              SST_ASSERT(otherRowCountRh_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.dbDbRhToDbPhS1OtherRowCountQueue.offer(
                  otherRowCountRh_)) {
            return motion;
          }
          fireTick(Party.PH, StateStream.S1);
          setRecverState(
              State.DB_DB_RH_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S2);
        } break;

        case DB_DB_RH_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCountRh_ >= 0);
              SST_ASSERT(otherRowCountRh_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.bothRowCountsQueues.get(otherDb_).offer(
                  new BothRowCounts(localRowCountRh_,
                                    otherRowCountRh_))) {
            return motion;
          }
          fireTick(Party.PH, StateStream.S2);
          if (localRowCountRh_ == 0 || otherRowCountRh_ == 0) {
            setRecverState(State.DB_DB_RH_NEXT_DOMAIN_TUPLE);
          } else {
            setRecverState(State.DB_DB_RH_RECV_R_FROM_REMOTE_DB_DB_SH);
          }
        } break;

        case DB_DB_RH_RECV_R_FROM_REMOTE_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(rBytesRh_ != null);
              SST_ASSERT(rIntEntry_ == null);
              SST_ASSERT(rLongEntry_ == null);
              SST_ASSERT(rBigEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < rBytesRh_.length) {
            ctx.read();
            return motion;
          }
          in_.readBytes(rBytesRh_);
          if (valuesFitInt_) {
            rIntEntry_ = rIntPool_.acquire();
            randMod(rBytesRh_, rIntEntry_.object(), localAggCount_);
          } else if (valuesFitLong_) {
            rLongEntry_ = rLongPool_.acquire();
            randMod(rBytesRh_, rLongEntry_.object(), localAggCount_);
          } else {
            rBigEntry_ = rBigPool_.acquire();
            randMod(rBytesRh_,
                    rBigEntry_.object(),
                    localAggCount_,
                    randModDstBuf_);
          }
          setRecverState(State.DB_DB_RH_SEND_R_TO_DB_PH_S3);
        } break;

        case DB_DB_RH_SEND_R_TO_DB_PH_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (!shd_.rIntQueue.offer(rIntEntry_)) {
              return motion;
            }
            rIntEntry_ = null;
          } else if (valuesFitLong_) {
            if (!shd_.rLongQueue.offer(rLongEntry_)) {
              return motion;
            }
            rLongEntry_ = null;
          } else {
            if (!shd_.rBigQueue.offer(rBigEntry_)) {
              return motion;
            }
            rBigEntry_ = null;
          }
          fireTick(Party.PH, StateStream.S3);
          setRecverState(State.DB_DB_RH_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_DB_RH_NEXT_LOCAL_ROW_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localRowIndex_ < localRowCountRh_) {
            localRowBatch_ =
                (int)Math.min(localRowCountRh_ - localRowIndex_,
                              localMaxBatch_);
            setRecverState(
                State.DB_DB_RH_RECV_A_BATCH_FROM_REMOTE_DB_DB_SH);
          } else {
            setRecverState(State.DB_DB_RH_NEXT_DOMAIN_TUPLE);
          }
        } break;

        case DB_DB_RH_RECV_A_BATCH_FROM_REMOTE_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(aIntBatchEntry_ == null);
              SST_ASSERT(aLongBatchEntry_ == null);
              SST_ASSERT(aBigBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          // Our batch size is not necessarily the same as the remote
          // batch size. It's a continuous stream, so it's okay.
          if (in_.readableBytes()
              < localRowBatch_ * localAggCount_ * valueSize_) {
            ctx.read();
            return motion;
          }
          in_.readBytes(aBytesBatchRh_,
                        0,
                        localRowBatch_ * localAggCount_ * valueSize_);
          if (valuesFitInt_) {
            aIntBatchEntry_ = aIntBatchPool_.acquire();
            randMod(aBytesBatchRh_,
                    aIntBatchEntry_.object(),
                    localRowBatch_ * localAggCount_);
          } else if (valuesFitLong_) {
            aLongBatchEntry_ = aLongBatchPool_.acquire();
            randMod(aBytesBatchRh_,
                    aLongBatchEntry_.object(),
                    localRowBatch_ * localAggCount_);
          } else {
            aBigBatchEntry_ = aBigBatchPool_.acquire();
            randMod(aBytesBatchRh_,
                    aBigBatchEntry_.object(),
                    localRowBatch_ * localAggCount_,
                    randModDstBuf_);
          }
          setRecverState(State.DB_DB_RH_SEND_A_BATCH_TO_DB_PH_S1);
        } break;

        case DB_DB_RH_SEND_A_BATCH_TO_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (!shd_.aIntBatchQueue.offer(aIntBatchEntry_)) {
              return motion;
            }
            aIntBatchEntry_ = null;
          } else if (valuesFitLong_) {
            if (!shd_.aLongBatchQueue.offer(aLongBatchEntry_)) {
              return motion;
            }
            aLongBatchEntry_ = null;
          } else {
            if (!shd_.aBigBatchQueue.offer(aBigBatchEntry_)) {
              return motion;
            }
            aBigBatchEntry_ = null;
          }
          fireTick(Party.PH, StateStream.S1);
          localRowIndex_ += localRowBatch_;
          setRecverState(State.DB_DB_RH_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_DB_RH_DONE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (true) {
            return motion;
          }
        } break;

        default:
          throw new ImpossibleException();
      }
      motion = true;
    }
  }

  private final void tick2(final ChannelHandlerContext ctx)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final int initialReadableBytes = in_.readableBytes();
    while (true) {
      switch (state_) {

        case SEND_PARTY: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(outgoing_);
              SST_ASSERT(remoteParty_ != null);
              SST_ASSERT(outLimit_ > 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          {
            final ByteBuf out = ctx.alloc().buffer();
            try {
              out.writeInt(localParty_.toInt());
              write(ctx, out);
            } catch (final Throwable e) {
              out.release();
              throw e;
            }
          }
          setState(State.SEND_LEXICON);
        } break;

        case RECV_PARTY: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(!outgoing_);
              SST_ASSERT(outLimit_ == -1);
              SST_ASSERT(remoteParty_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < 4) {
            ctx.read();
            return;
          }
          remoteParty_ = Party.fromInt(in_.readInt());
          updateLogPrefix();
          remotePartyIsDb_ = remoteParty_.isDb();
          remotePartyIsPh_ = !remotePartyIsDb_;
          outLimit_ =
              config_.channelOutputBufferLimit().get(remoteParty_);
          setState(State.SEND_LEXICON);
        } break;

        case SEND_LEXICON: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(remoteParty_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          {
            final ByteBuf out = ctx.alloc().buffer();
            try {
              out.writeInt(globals_.lexiconString().length());
              ByteBufUtil.writeAscii(out, globals_.lexiconString());
              write(ctx, out);
              flush(ctx);
            } catch (final Throwable e) {
              out.release();
              throw e;
            }
          }
          setState(State.RECV_LEXICON_1);
        } break;

        case RECV_LEXICON_1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(remoteParty_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < 4) {
            ctx.read();
            return;
          }
          incomingLexiconStringLength_ = in_.readInt();
          setState(State.RECV_LEXICON_2);
        } break;

        case RECV_LEXICON_2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(remoteParty_ != null);
              SST_ASSERT(incomingLexiconStringLength_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < incomingLexiconStringLength_) {
            ctx.read();
            return;
          }
          final String lexiconString =
              in_.readCharSequence(incomingLexiconStringLength_,
                                   StandardCharsets.US_ASCII)
                  .toString();
          final Lexicon lexicon =
              Json.getAs(new JSONObject(lexiconString).toMap(),
                         Lexicon.fromJson());
          if (!lexicon.lexiconEquals(lexicon_)) {
            throw new RuntimeException("lexicon mismatch");
          }
          if (outgoing_) {
            setState(State.SEND_QUERY);
          } else {
            setState(State.RECV_QUERY_1);
          }
        } break;

        case SEND_QUERY: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(outgoing_);
              SST_ASSERT(remoteParty_ != null);
              SST_ASSERT(remotePartyIsDb_);
              if (localPartyIsDb_) {
                SST_ASSERT(localParty_ == Party.DB1);
              }
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (queryString_ == null) {
            // No query. This is an extra tick. Go back to sleep.
            return;
          }
          totalRxAtQueryStart_ =
              totalRx_ + (initialReadableBytes - in_.readableBytes());
          if (out_.get() > outLimit_) {
            return;
          }
          {
            final ByteBuf out = ctx.alloc().buffer();
            try {
              out.writeInt(stateStream_.toInt());
              out.writeBytes(queryId_.toBytes());
              out.writeInt(queryString_.length());
              ByteBufUtil.writeAscii(out, queryString_);
              write(ctx, out);
              flush(ctx);
            } catch (final Throwable e) {
              out.release();
              throw e;
            }
          }
          if (localPartyIsDb_) {
            setState(State.DB_DB_DUPLEX);
            setSenderState(State.DB_DB_SH_NEXT_DOMAIN_TUPLE);
            setRecverState(State.DB_DB_RH_NEXT_DOMAIN_TUPLE);
          } else if (stateStream_ == StateStream.S1) {
            setState(State.PH_DB_S1_NEXT_DOMAIN_TUPLE);
          } else if (stateStream_ == StateStream.S2) {
            setState(State.PH_DB_S2_NEXT_DOMAIN_TUPLE);
          } else if (stateStream_ == StateStream.S3) {
            setState(State.PH_DB_S3_NEXT_DOMAIN_TUPLE);
          } else if (!SST_NDEBUG) {
            throw new ImpossibleException();
          }
        } break;

        case RECV_QUERY_1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(!outgoing_);
              SST_ASSERT(localPartyIsDb_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          totalRxAtQueryStart_ =
              totalRx_ + (initialReadableBytes - in_.readableBytes());
          if (in_.readableBytes() < 4 + guidSize_ + 4) {
            ctx.read();
            return;
          }
          stateStream_ = StateStream.fromInt(in_.readInt());
          updateLogPrefix();
          final byte[] x = new byte[guidSize_];
          in_.readBytes(x);
          queryId_ = Guid.fromBytes(x);
          incomingQueryStringLength_ = in_.readInt();
          setState(State.RECV_QUERY_2);
        } break;

        case RECV_QUERY_2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(!outgoing_);
              SST_ASSERT(localPartyIsDb_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < incomingQueryStringLength_) {
            ctx.read();
            return;
          }
          queryString_ =
              in_.readCharSequence(incomingQueryStringLength_,
                                   StandardCharsets.US_ASCII)
                  .toString();
          query_ = Query.fromQueryString(queryString_, lexicon_);
          gotQuery();
          if (stateStream_ == StateStream.S1) {
            if (localParty_ == Party.DB1) {
              final String queryString = queryString_;
              final Guid queryId = queryId_;
              final Query query = query_;
              final PoolEntry<ChannelFuture> entry =
                  globals_.rawChannels(Party.DB2).acquire();
              entry.object().addListener(new ChannelFutureListener() {
                @Override
                public final void operationComplete(
                    final ChannelFuture future) throws Exception {
                  try {
                    if (!SST_NDEBUG) {
                      try {
                        SST_ASSERT(future != null);
                      } catch (final Throwable e) {
                        SST_ASSERT(e);
                      }
                    }
                    future.sync()
                        .channel()
                        .pipeline()
                        .fireUserEventTriggered(
                            new DbStartQueryEvent(queryString,
                                                  queryId,
                                                  query,
                                                  entry));
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
            }
            if (remotePartyIsDb_) {
              setState(State.DB_DB_DUPLEX);
              setSenderState(State.DB_DB_SH_NEXT_DOMAIN_TUPLE);
              setRecverState(State.DB_DB_RH_NEXT_DOMAIN_TUPLE);
            } else {
              setState(State.DB_PH_S1_CONNECT_TO_DATABASE);
            }
          } else if (stateStream_ == StateStream.S2) {
            setState(State.DB_PH_S2_NEXT_DOMAIN_TUPLE);
          } else if (stateStream_ == StateStream.S3) {
            setState(State.DB_PH_S3_NEXT_DOMAIN_TUPLE);
          } else if (!SST_NDEBUG) {
            throw new ImpossibleException();
          }
        } break;

        case PH_DB_S1_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next()) {
            localRowCount_ = -1;
            localRowIndex_ = -1;
            otherRowCount_ = -1;
            setState(State.PH_DB_S1_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case PH_DB_S1_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < 16) {
            ctx.read();
            return;
          }
          localRowCount_ = in_.readLong();
          localRowIndex_ = 0;
          otherRowCount_ = in_.readLong();
          setState(
              State.PH_DB_S1_SEND_LOCAL_ROW_COUNT_TO_MERGE_MACHINE);
        } break;

        case PH_DB_S1_SEND_LOCAL_ROW_COUNT_TO_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == 0);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localDb_ == Party.DB1) {
            if (!shd_.mergeMachine.rowCountQueue1.offer(
                    localRowCount_)) {
              return;
            }
          } else {
            if (!shd_.mergeMachine.rowCountQueue2.offer(
                    localRowCount_)) {
              return;
            }
          }
          fireTick(shd_.mergeMachine);
          setState(State.PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S3);
        } break;

        case PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == 0);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!phDbS1ToPhDbS3BothRowCountsQueue_.offer(
                  new BothRowCounts(localRowCount_, otherRowCount_))) {
            return;
          }
          fireTick(localDb_, StateStream.S3);
          setState(
              State.PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_OTHER_PH_DB_S2);
        } break;

        case PH_DB_S1_SEND_BOTH_ROW_COUNTS_TO_OTHER_PH_DB_S2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == 0);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.bothRowCountsQueues.get(localDb_).offer(
                  new BothRowCounts(localRowCount_, otherRowCount_))) {
            return;
          }
          fireTick(otherDb_, StateStream.S2);
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.PH_DB_S1_NEXT_DOMAIN_TUPLE);
          } else {
            setState(State.PH_DB_S1_NEXT_LOCAL_ROW_BATCH);
          }
        } break;

        case PH_DB_S1_NEXT_LOCAL_ROW_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ <= localRowCount_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localRowIndex_ < localRowCount_) {
            localRowBatch_ =
                (int)Math.min(localRowCount_ - localRowIndex_,
                              localMaxBatch_);
            setState(State.PH_DB_S1_RECV_XA_BATCH_FROM_DB_PH_S1);
          } else {
            setState(State.PH_DB_S1_RECV_Z_FROM_DB_PH_S1);
          }
        } break;

        case PH_DB_S1_RECV_XA_BATCH_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(xaBytesSize_ > 0);
              SST_ASSERT(xaBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < localRowBatch_ * xaBytesSize_) {
            ctx.read();
            return;
          }
          xaBytesBatchEntry_ = xaBytesBatchPool_.acquire();
          in_.readBytes(xaBytesBatchEntry_.object(),
                        0,
                        localRowBatch_ * xaBytesSize_);
          setState(State.PH_DB_S1_SEND_XA_BATCH_TO_MERGE_MACHINE);
        } break;

        case PH_DB_S1_SEND_XA_BATCH_TO_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(xaBytesBatchEntry_ != null);
              SST_ASSERT(xaBytesBatchQueue_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!xaBytesBatchQueue_.offer(xaBytesBatchEntry_)) {
            return;
          }
          xaBytesBatchEntry_ = null;
          fireTick(shd_.mergeMachine);
          localRowIndex_ += localRowBatch_;
          setState(State.PH_DB_S1_NEXT_LOCAL_ROW_BATCH);
        } break;

        case PH_DB_S1_RECV_Z_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(zBytes_ != null);
              SST_ASSERT(zIntEntry_ == null);
              SST_ASSERT(zLongEntry_ == null);
              SST_ASSERT(zBigEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < zBytes_.length) {
            ctx.read();
            return;
          }
          in_.readBytes(zBytes_, 0, zBytes_.length);
          // TODO: Let MergeMachine do the fromBytes() work? It feels
          //       better and it's consistent with how S is done.
          if (valuesFitInt_) {
            zIntEntry_ = zIntPool_.acquire();
            final int[] z = zIntEntry_.object();
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              z[i] = Rep.fromBytes(zBytes_,
                                   j,
                                   valueSize_,
                                   z[i],
                                   IntegerRep.PURE_UNSIGNED,
                                   ByteOrder.BIG_ENDIAN,
                                   false);
            }
          } else if (valuesFitLong_) {
            zLongEntry_ = zLongPool_.acquire();
            final long[] z = zLongEntry_.object();
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              z[i] = Rep.fromBytes(zBytes_,
                                   j,
                                   valueSize_,
                                   z[i],
                                   IntegerRep.PURE_UNSIGNED,
                                   ByteOrder.BIG_ENDIAN,
                                   false);
            }
          } else {
            zBigEntry_ = zBigPool_.acquire();
            final BigInteger[] z = zBigEntry_.object();
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              z[i] = Rep.fromBytes(zBytes_,
                                   j,
                                   valueSize_,
                                   z[i],
                                   IntegerRep.PURE_UNSIGNED,
                                   ByteOrder.BIG_ENDIAN,
                                   false);
            }
          }
          setState(State.PH_DB_S1_SEND_Z_TO_MERGE_MACHINE);
        } break;

        case PH_DB_S1_SEND_Z_TO_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(valuesFitInt_ ?
                             (zIntEntry_ != null && zLongEntry_ == null
                              && zBigEntry_ == null) :
                             valuesFitLong_ ?
                             (zIntEntry_ == null && zLongEntry_ != null
                              && zBigEntry_ == null) :
                             (zIntEntry_ == null && zLongEntry_ == null
                              && zBigEntry_ != null));
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (!zIntQueue_.offer(zIntEntry_)) {
              return;
            }
            zIntEntry_ = null;
          } else if (valuesFitLong_) {
            if (!zLongQueue_.offer(zLongEntry_)) {
              return;
            }
            zLongEntry_ = null;
          } else {
            if (!zBigQueue_.offer(zBigEntry_)) {
              return;
            }
            zBigEntry_ = null;
          }
          fireTick(shd_.mergeMachine);
          setState(State.PH_DB_S1_NEXT_DOMAIN_TUPLE);
        } break;

        case PH_DB_S2_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(bBytesBatchQueue_ != null);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next()) {
            localRowCount_ = -1;
            otherRowCount_ = -1;
            otherRowIndex_ = -1;
            otherRowBatch_ = -1;
            setState(
                State
                    .PH_DB_S2_RECV_BOTH_ROW_COUNTS_FROM_OTHER_PH_DB_S1);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case PH_DB_S2_RECV_BOTH_ROW_COUNTS_FROM_OTHER_PH_DB_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(bBytesBatchQueue_ != null);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
              SST_ASSERT(otherRowIndex_ == -1);
              SST_ASSERT(otherRowBatch_ == -1);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final BothRowCounts bothRowCounts =
              shd_.bothRowCountsQueues.get(otherDb_).poll();
          if (bothRowCounts == null) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          // NB: We swap the row counts because they come from the
          // PH-DB-S1 handler for the other DB, not the local DB.
          localRowCount_ = bothRowCounts.otherRowCount;
          otherRowCount_ = bothRowCounts.localRowCount;
          otherRowIndex_ = 0;
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.PH_DB_S2_NEXT_DOMAIN_TUPLE);
          } else {
            setState(State.PH_DB_S2_NEXT_B_BATCH);
          }
        } break;

        case PH_DB_S2_NEXT_B_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(bBytesBatchQueue_ != null);
              SST_ASSERT(otherRowCount_ >= 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ <= otherRowCount_);
              SST_ASSERT(otherRowBatch_ == -1);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (otherRowIndex_ < otherRowCount_) {
            otherRowBatch_ =
                (int)Math.min(otherRowCount_ - otherRowIndex_,
                              otherMaxBatch_);
            setState(State.PH_DB_S2_RECV_B_BATCH_FROM_DB_PH_S2);
          } else {
            setState(State.PH_DB_S2_NEXT_DOMAIN_TUPLE);
          }
        } break;

        case PH_DB_S2_RECV_B_BATCH_FROM_DB_PH_S2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(bBytesBatchQueue_ != null);
              SST_ASSERT(otherRowCount_ > 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ < otherRowCount_);
              SST_ASSERT(otherRowBatch_ > 0);
              SST_ASSERT(otherRowBatch_
                         <= otherRowCount_ - otherRowIndex_);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          // Our batch size is not necessarily the same as the remote
          // batch size. It's a continuous stream, so it's okay.
          if (in_.readableBytes() < otherRowBatch_ * valueSize_) {
            ctx.read();
            return;
          }
          bBytesBatchEntry_ = bBytesBatchPool_.acquire();
          in_.readBytes(bBytesBatchEntry_.object(),
                        0,
                        otherRowBatch_ * valueSize_);
          setState(State.PH_DB_S2_SEND_B_BATCH_TO_MERGE_MACHINE);
        } break;

        case PH_DB_S2_SEND_B_BATCH_TO_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchPool_ != null);
              SST_ASSERT(bBytesBatchQueue_ != null);
              SST_ASSERT(otherRowCount_ > 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ < otherRowCount_);
              SST_ASSERT(otherRowBatch_ > 0);
              SST_ASSERT(otherRowBatch_
                         <= otherRowCount_ - otherRowIndex_);
              SST_ASSERT(bBytesBatchEntry_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!bBytesBatchQueue_.offer(bBytesBatchEntry_)) {
            return;
          }
          bBytesBatchEntry_ = null;
          fireTick(shd_.mergeMachine);
          otherRowIndex_ += otherRowBatch_;
          otherRowBatch_ = -1;
          setState(State.PH_DB_S2_NEXT_B_BATCH);
        } break;

        case PH_DB_S3_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next()) {
            localRowCount_ = -1;
            localRowIndex_ = -1;
            localRowBatch_ = -1;
            otherRowCount_ = -1;
            setState(State.PH_DB_S3_RECV_BOTH_ROW_COUNTS_FROM_PH_DB_S1);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case PH_DB_S3_RECV_BOTH_ROW_COUNTS_FROM_PH_DB_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final BothRowCounts bothRowCounts =
              phDbS1ToPhDbS3BothRowCountsQueue_.poll();
          if (bothRowCounts == null) {
            return;
          }
          fireTick(localDb_, StateStream.S1);
          localRowCount_ = bothRowCounts.localRowCount;
          localRowIndex_ = 0;
          otherRowCount_ = bothRowCounts.otherRowCount;
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.PH_DB_S3_NEXT_DOMAIN_TUPLE);
          } else {
            setState(State.PH_DB_S3_NEXT_YB_BATCH);
          }
        } break;

        case PH_DB_S3_NEXT_YB_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ <= localRowCount_);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localRowIndex_ < localRowCount_) {
            setState(State.PH_DB_S3_RECV_YB_BATCH_FROM_MERGE_MACHINE);
          } else {
            if (!SST_NDEBUG) {
              SST_ASSERT(localRowIndex_ == localRowCount_);
            }
            setState(State.PH_DB_S3_RECV_S_FROM_DB_PH_S3);
          }
        } break;

        case PH_DB_S3_RECV_YB_BATCH_FROM_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(ybBytesBatchQueue_ != null);
              SST_ASSERT(ybBytesBatchEntry_ == null);
              SST_ASSERT(ybBytesBatch_ == null);
              SST_ASSERT(shd_.mergeMachine != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          ybBytesBatchEntry_ = ybBytesBatchQueue_.poll();
          if (ybBytesBatchEntry_ == null) {
            return;
          }
          fireTick(shd_.mergeMachine);
          ybBytesBatch_ = ybBytesBatchEntry_.object();
          localRowBatch_ =
              (int)Math.min(localRowCount_ - localRowIndex_,
                            ybBytesBatch_.length / valueSize_);
          setState(State.PH_DB_S3_SEND_YB_BATCH_TO_DB_PH_S3);
        } break;

        case PH_DB_S3_SEND_YB_BATCH_TO_DB_PH_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ > 0);
              SST_ASSERT(ybBytesBatchEntry_ != null);
              SST_ASSERT(ybBytesBatch_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          write(ctx, ybBytesBatch_, 0, localRowBatch_ * valueSize_);
          flush(ctx);
          ybBytesBatchEntry_.release();
          ybBytesBatchEntry_ = null;
          ybBytesBatch_ = null;
          localRowIndex_ += localRowBatch_;
          localRowBatch_ = -1;
          setState(State.PH_DB_S3_NEXT_YB_BATCH);
        } break;

        case PH_DB_S3_RECV_S_FROM_DB_PH_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(sBytesPool_ != null);
              SST_ASSERT(sBytesEntry_ == null);
              SST_ASSERT(sBytes_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < localAggCount_ * valueSize_) {
            ctx.read();
            return;
          }
          sBytesEntry_ = sBytesPool_.acquire();
          sBytes_ = sBytesEntry_.object();
          in_.readBytes(sBytes_);
          setState(State.PH_DB_S3_SEND_S_TO_MERGE_MACHINE);
        } break;

        case PH_DB_S3_SEND_S_TO_MERGE_MACHINE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsPh_);
              SST_ASSERT(remotePartyIsDb_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(sBytesEntry_ != null);
              SST_ASSERT(sBytes_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!sBytesQueue_.offer(sBytesEntry_)) {
            return;
          }
          fireTick(shd_.mergeMachine);
          sBytesEntry_ = null;
          sBytes_ = null;
          setState(State.PH_DB_S3_NEXT_DOMAIN_TUPLE);
        } break;

        case DB_PH_S1_CONNECT_TO_DATABASE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!jdbcRunner_.open(globals_.sqlChannels(),
                                this.<JdbcConnection>onSuccess(TICK),
                                onFailure())) {
            return;
          }
          final StringBuilder where = new StringBuilder();
          final StringBuilder whereFormat = new StringBuilder();
          where.append(" WHERE ");
          whereFormat.append(" WHERE ");
          allParameters_ = new ArrayList<Object>();
          final Condition prefilter = query_.prefilters().get(localDb_);
          if (prefilter == null) {
            where.append("0 = 0");
            whereFormat.append("0 = 0");
          } else {
            prefilter.toSql(where, allParameters_, whereFormat);
          }
          domainIterator_.toSql(where, whereFormat);
          domainParameters_ =
              allParameters_.subList(allParameters_.size(),
                                     allParameters_.size());
          final StringBuilder x = new StringBuilder();
          final StringBuilder y = new StringBuilder();
          x.setLength(0);
          y.setLength(0);
          x.append("SELECT COUNT(*) FROM ");
          x.append(localTable_.underlyingName());
          y.append(x.toString().replace("%", "%%"));
          x.append(where);
          y.append(whereFormat);
          countSql_ = x.toString();
          countSqlFormat_ = y.toString();
          x.setLength(0);
          y.setLength(0);
          x.append("SELECT ");
          x.append(localTable_.linkingColumn().underlyingName());
          for (final Aggregate aggregate : query_.aggregates()) {
            if (aggregate.db() == localParty_) {
              x.append(", ");
              aggregate.toSql(x);
            }
          }
          x.append(" FROM ");
          x.append(localTable_.underlyingName());
          y.append(x.toString().replace("%", "%%"));
          x.append(where);
          y.append(whereFormat);
          x.append(" ORDER BY ");
          y.append(" ORDER BY ");
          if (!config_.orderByOverride().isEmpty()) {
            x.append(config_.orderByOverride());
            y.append(config_.orderByOverride());
          } else if (linkingColumnForceString_
                     && !linkingColumnIsString_) {
            x.append("CAST(");
            y.append("CAST(");
            x.append(localTable_.linkingColumn().underlyingName());
            y.append(
                localTable_.linkingColumn().underlyingName().replace(
                    "%",
                    "%%"));
            x.append(" AS CHAR(32))");
            y.append(" AS CHAR(32))");
          } else {
            x.append(localTable_.linkingColumn().underlyingName());
            y.append(
                localTable_.linkingColumn().underlyingName().replace(
                    "%",
                    "%%"));
          }
          fullSql_ = x.toString();
          fullSqlFormat_ = y.toString();
          countStatement_ = jdbcRunner_.prepareStatement(countSql_);
          fullStatement_ =
              jdbcRunner_.prepareStreamingStatement(fullSql_);
          setState(State.DB_PH_S1_NEXT_DOMAIN_TUPLE);
        } break;

        case DB_PH_S1_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next(domainParameters_)) {
            localRowCount_ = -1;
            localRowIndex_ = -1;
            localRowBatch_ = -1;
            otherRowCount_ = -1;
            Jdbc.resetParameters(countStatement_, allParameters_);
            Jdbc.resetParameters(fullStatement_, allParameters_);
            log(String.format(
                countSqlFormat_,
                (Object[])Jdbc.formatParameters(
                    allParameters_,
                    config_.databaseConnection().subprotocol())));
            setState(State.DB_PH_S1_DO_COUNT_QUERY);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case DB_PH_S1_DO_COUNT_QUERY: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final PreparedStatement countStatement = countStatement_;
          countResult_ = jdbcRunner_.runAsync(
              new Callable<ResultSet>() {
                @Override
                public final ResultSet call() throws Exception {
                  return countStatement.executeQuery();
                }
              },
              globals_.workerThreadGroup(),
              this.<ResultSet>onSuccess(TICK),
              onFailure());
          if (countResult_ == null) {
            return;
          }
          countResult_.next();
          localRowCount_ = countResult_.getLong(1);
          jdbcRunner_.close(countResult_);
          setState(State.DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_SH);
        } break;

        case DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.dbPhS1ToDbDbSsLocalRowCountQueue.offer(
                  localRowCount_)) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_RH);
        } break;

        case DB_PH_S1_SEND_LOCAL_ROW_COUNT_TO_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.dbPhS1ToDbDbRsLocalRowCountQueue.offer(
                  localRowCount_)) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S1_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH);
        } break;

        case DB_PH_S1_RECV_OTHER_ROW_COUNT_FROM_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final Long x = shd_.dbDbRhToDbPhS1OtherRowCountQueue.poll();
          if (x == null) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          otherRowCount_ = x;
          setState(State.DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S3);
        } break;

        case DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_DB_PH_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!shd_.dbPhS1ToDbPhS3BothRowCountsQueue.offer(
                  new BothRowCounts(localRowCount_, otherRowCount_))) {
            return;
          }
          fireTick(Party.PH, StateStream.S3);
          setState(State.DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S1);
        } break;

        case DB_PH_S1_SEND_BOTH_ROW_COUNTS_TO_PH_DB_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ >= 0);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          {
            final ByteBuf x = ctx.alloc().buffer();
            try {
              x.writeLong(localRowCount_);
              x.writeLong(otherRowCount_);
              write(ctx, x);
              flush(ctx);
            } catch (final Throwable e) {
              x.release();
              throw e;
            }
          }
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.DB_PH_S1_NEXT_DOMAIN_TUPLE);
          } else {
            log(String.format(
                fullSqlFormat_,
                (Object[])Jdbc.formatParameters(
                    allParameters_,
                    config_.databaseConnection().subprotocol())));
            setState(State.DB_PH_S1_DO_FULL_QUERY);
          }
        } break;

        case DB_PH_S1_DO_FULL_QUERY: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final PreparedStatement fullStatement = fullStatement_;
          fullResult_ = jdbcRunner_.runAsync(
              new Callable<ResultSet>() {
                @Override
                public final ResultSet call() throws Exception {
                  return fullStatement.executeQuery();
                }
              },
              globals_.workerThreadGroup(),
              this.<ResultSet>onSuccess(TICK),
              onFailure());
          if (fullResult_ == null) {
            return;
          }
          fullResultMetadata_ = fullResult_.getMetaData();
          localRowIndex_ = 0;
          setState(State.DB_PH_S1_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_PH_S1_NEXT_LOCAL_ROW_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ <= localRowCount_);
              SST_ASSERT(xaBytesBatch_ != null);
              SST_ASSERT(xaFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localRowIndex_ < localRowCount_) {
            localRowBatch_ =
                (int)Math.min(localRowCount_ - localRowIndex_,
                              localMaxBatch_);
            setState(State.DB_PH_S1_START_RETRIEVING_X_BATCH);
          } else {
            fullResult_.close();
            fullResult_ = null;
            fullResultMetadata_ = null;
            setState(State.DB_PH_S1_RECV_Z_FROM_DB_DB_SH);
          }
        } break;

        case DB_PH_S1_START_RETRIEVING_X_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(xBatchFuture_ == null);
              SST_ASSERT(
                  valuesFitInt_ ?
                      (xIntBatchPool_ != null && xLongBatchPool_ == null
                       && xBigBatchPool_ == null) :
                      valuesFitLong_ ?
                      (xIntBatchPool_ == null && xLongBatchPool_ != null
                       && xBigBatchPool_ == null) :
                      (xIntBatchPool_ == null && xLongBatchPool_ == null
                       && xBigBatchPool_ != null));
              SST_ASSERT(xIntBatchEntry_ == null);
              SST_ASSERT(xLongBatchEntry_ == null);
              SST_ASSERT(xBigBatchEntry_ == null);
              SST_ASSERT(xIntBatch_ == null);
              SST_ASSERT(xLongBatch_ == null);
              SST_ASSERT(xBigBatch_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            xIntBatchEntry_ = xIntBatchPool_.acquire();
            xIntBatch_ = xIntBatchEntry_.object();
          } else if (valuesFitLong_) {
            xLongBatchEntry_ = xLongBatchPool_.acquire();
            xLongBatch_ = xLongBatchEntry_.object();
          } else {
            xBigBatchEntry_ = xBigBatchPool_.acquire();
            xBigBatch_ = xBigBatchEntry_.object();
          }
          final boolean valuesFitInt = valuesFitInt_;
          final boolean valuesFitLong = valuesFitLong_;
          final XIntBatch xIntBatch = xIntBatch_;
          final XLongBatch xLongBatch = xLongBatch_;
          final XBigBatch xBigBatch = xBigBatch_;
          final int localRowBatch = localRowBatch_;
          final ResultSet fullResult = fullResult_;
          final ResultSetMetaData metadata = fullResultMetadata_;
          final int localAggCount = localAggCount_;
          final List<Aggregate> aggregates =
              query_.aggregates(localDb_);
          final boolean lastBatch =
              localRowIndex_ + localRowBatch_ == localRowCount_;
          final long localRowIndex = localRowIndex_;
          final FixedPointModContext zeroScaleFpmContext =
              zeroScaleFpmContext_;
          final FixedPointModContext[] fixedPointModContexts =
              fixedPointModContexts_;
          // TODO: This should use Callable to transfer the exception
          //       out of the future nicely.
          xBatchFuture_ =
              globals_.workerThreadGroup().submit(new Runnable() {
                @Override
                public final void run() {
                  try {
                    // TODO: Is hoisting valuesFit* this high up really
                    //       necessary?
                    // TODO: The handling of the squaring is quite error
                    //       prone here. How do we make it better?
                    if (valuesFitInt) {
                      int k = 0;
                      for (int i = 0; i < localRowBatch; ++i) {
                        if (!fullResult.next()) {
                          throw new RuntimeException(
                              "database changed between queries?");
                        }
                        getLinkingColumn(fullResult,
                                         1,
                                         xIntBatch.id,
                                         i * linkingColumnSize_);
                        if (localRowIndex > 0 || i > 0) {
                          if (Memory.cmp(previousId_,
                                         0,
                                         xIntBatch.id,
                                         i * linkingColumnSize_,
                                         linkingColumnSize_,
                                         ByteOrder.BIG_ENDIAN)
                              > 0) {
                            throw orderMismatch();
                          }
                        }
                        System.arraycopy(xIntBatch.id,
                                         i * linkingColumnSize_,
                                         previousId_,
                                         0,
                                         linkingColumnSize_);
                        int ci = 2;
                        for (int ai = 0; ai < aggregates.size(); ++ai) {
                          final Aggregate agg = aggregates.get(ai);
                          final int n = agg.aggCount();
                          for (int j = 0; j < n; ++j) {
                            final FixedPointModContext fp =
                                agg.shouldScale(j) ?
                                    fixedPointModContexts[ai] :
                                    zeroScaleFpmContext;
                            if (j < 2) {
                              xIntBatch.xs[k] = fp.encode(
                                  fullResult,
                                  ci,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci)),
                                  (Integer)null,
                                  true);
                              ++ci;
                            } else {
                              xIntBatch.xs[k] = fp.encodeSquare(
                                  fullResult,
                                  ci - 1,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci - 1)),
                                  (Integer)null,
                                  true);
                            }
                            ++k;
                          }
                        }
                      }
                    } else if (valuesFitLong) {
                      int k = 0;
                      for (int i = 0; i < localRowBatch; ++i) {
                        if (!fullResult.next()) {
                          throw new RuntimeException(
                              "database changed between queries?");
                        }
                        getLinkingColumn(fullResult,
                                         1,
                                         xLongBatch.id,
                                         i * linkingColumnSize_);
                        if (localRowIndex > 0 || i > 0) {
                          if (Memory.cmp(previousId_,
                                         0,
                                         xLongBatch.id,
                                         i * linkingColumnSize_,
                                         linkingColumnSize_,
                                         ByteOrder.BIG_ENDIAN)
                              > 0) {
                            throw orderMismatch();
                          }
                        }
                        System.arraycopy(xLongBatch.id,
                                         i * linkingColumnSize_,
                                         previousId_,
                                         0,
                                         linkingColumnSize_);
                        int ci = 2;
                        for (int ai = 0; ai < aggregates.size(); ++ai) {
                          final Aggregate agg = aggregates.get(ai);
                          final int n = agg.aggCount();
                          for (int j = 0; j < n; ++j) {
                            final FixedPointModContext fp =
                                agg.shouldScale(j) ?
                                    fixedPointModContexts[ai] :
                                    zeroScaleFpmContext;
                            if (j < 2) {
                              xLongBatch.xs[k] = fp.encode(
                                  fullResult,
                                  ci,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci)),
                                  (Long)null,
                                  true);
                              ++ci;
                            } else {
                              xLongBatch.xs[k] = fp.encodeSquare(
                                  fullResult,
                                  ci - 1,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci - 1)),
                                  (Long)null,
                                  true);
                            }
                            ++k;
                          }
                        }
                      }
                    } else {
                      int k = 0;
                      for (int i = 0; i < localRowBatch; ++i) {
                        if (!fullResult.next()) {
                          throw new RuntimeException(
                              "database changed between queries?");
                        }
                        getLinkingColumn(fullResult,
                                         1,
                                         xBigBatch.id,
                                         i * linkingColumnSize_);
                        if (localRowIndex > 0 || i > 0) {
                          if (Memory.cmp(previousId_,
                                         0,
                                         xBigBatch.id,
                                         i * linkingColumnSize_,
                                         linkingColumnSize_,
                                         ByteOrder.BIG_ENDIAN)
                              > 0) {
                            throw orderMismatch();
                          }
                        }
                        System.arraycopy(xBigBatch.id,
                                         i * linkingColumnSize_,
                                         previousId_,
                                         0,
                                         linkingColumnSize_);
                        int ci = 2;
                        for (int ai = 0; ai < aggregates.size(); ++ai) {
                          final Aggregate agg = aggregates.get(ai);
                          final int n = agg.aggCount();
                          for (int j = 0; j < n; ++j) {
                            final FixedPointModContext fp =
                                agg.shouldScale(j) ?
                                    fixedPointModContexts[ai] :
                                    zeroScaleFpmContext;
                            if (j < 2) {
                              xBigBatch.xs[k] = fp.encode(
                                  fullResult,
                                  ci,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci)),
                                  (BigInteger)null,
                                  true);
                              ++ci;
                            } else {
                              xBigBatch.xs[k] = fp.encodeSquare(
                                  fullResult,
                                  ci - 1,
                                  JdbcType.fromInt(
                                      metadata.getColumnType(ci - 1)),
                                  (BigInteger)null,
                                  true);
                            }
                            ++k;
                          }
                        }
                      }
                    }
                    if (lastBatch && fullResult.next()) {
                      throw new RuntimeException(
                          "database changed between queries?");
                    }
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
          xBatchFuture_.addListener(new FutureListener<Object>() {
            @Override
            public final void operationComplete(
                final Future<Object> future) {
              try {
                future.sync();
                fireTick(pipeline_);
              } catch (final Throwable e) {
                asyncFatal(e);
              }
            }
          });
          setState(State.DB_PH_S1_RECV_A_BATCH_FROM_DB_DB_RH);
        } break;

        case DB_PH_S1_RECV_A_BATCH_FROM_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(aIntBatchEntry_ == null);
              SST_ASSERT(aLongBatchEntry_ == null);
              SST_ASSERT(aBigBatchEntry_ == null);
              SST_ASSERT(aIntBatch_ == null);
              SST_ASSERT(aLongBatch_ == null);
              SST_ASSERT(aBigBatch_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            aIntBatchEntry_ = shd_.aIntBatchQueue.poll();
            if (aIntBatchEntry_ == null) {
              return;
            }
            aIntBatch_ = aIntBatchEntry_.object();
          } else if (valuesFitLong_) {
            aLongBatchEntry_ = shd_.aLongBatchQueue.poll();
            if (aLongBatchEntry_ == null) {
              return;
            }
            aLongBatch_ = aLongBatchEntry_.object();
          } else {
            aBigBatchEntry_ = shd_.aBigBatchQueue.poll();
            if (aBigBatchEntry_ == null) {
              return;
            }
            aBigBatch_ = aBigBatchEntry_.object();
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S1_FINISH_RETRIEVING_X_BATCH);
        } break;

        case DB_PH_S1_FINISH_RETRIEVING_X_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!xBatchFuture_.isDone()) {
            return;
          }
          xBatchFuture_.sync();
          xBatchFuture_ = null;
          setState(State.DB_PH_S1_START_COMPUTING_XA_BATCH);
        } break;

        case DB_PH_S1_START_COMPUTING_XA_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(xaBytesBatch_ != null);
              SST_ASSERT(xaFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final int valueSize = valueSize_;
          final boolean valuesFitInt = valuesFitInt_;
          final boolean valuesFitLong = valuesFitLong_;
          final XIntBatch xIntBatch = xIntBatch_;
          final XLongBatch xLongBatch = xLongBatch_;
          final XBigBatch xBigBatch = xBigBatch_;
          final int[] aIntBatch = aIntBatch_;
          final long[] aLongBatch = aLongBatch_;
          final BigInteger[] aBigBatch = aBigBatch_;
          final int modulusInt = modulusInt_;
          final long modulusLong = modulusLong_;
          final BigInteger modulusBig = modulusBig_;
          final int localRowBatch = localRowBatch_;
          final int localAggCount = localAggCount_;
          final byte[] xaBytesBatch = xaBytesBatch_;
          xaFuture_ =
              globals_.workerThreadGroup().submit(new Runnable() {
                @Override
                public final void run() {
                  try {
                    int xi = 0;
                    int ai = 0;
                    int xai = 0;
                    if (valuesFitInt) {
                      if (modulusInt == 0
                          || modulusInt == Integer.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusInt)) {
                        // m is a power of two in [1, 2^32]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xIntBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            final int xa =
                                xIntBatch.xs[xi] + aIntBatch[ai];
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      } else if (modulusInt > 0) {
                        // m is a non-power-of-two in [1, 2^31]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xIntBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            final int xa = (int)(((long)xIntBatch.xs[xi]
                                                  + (long)aIntBatch[ai])
                                                 % (long)modulusInt);
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^31, 2^32]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xIntBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            final int xa = (int)Arith.unsignedMod(
                                Arith.toUnsignedLong(xIntBatch.xs[xi])
                                    + Arith.toUnsignedLong(
                                        aIntBatch[ai]),
                                Arith.toUnsignedLong(modulusInt));
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      }
                    } else if (valuesFitLong) {
                      if (modulusLong == 0
                          || modulusLong == Long.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusLong)) {
                        // m is a power of two in [2^33, 2^64]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xLongBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            final long xa =
                                xLongBatch.xs[xi] + aLongBatch[ai];
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      } else if (modulusLong > 0) {
                        // m is a non-power-of-two in [2^32, 2^63]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xLongBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            long xa = xLongBatch.xs[xi] - modulusLong
                                      + aLongBatch[ai];
                            if (xa < 0) {
                              xa += modulusLong;
                            }
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^63, 2^64]
                        for (int i = 0; i < localRowBatch; ++i) {
                          System.arraycopy(xLongBatch.id,
                                           i * linkingColumnSize_,
                                           xaBytesBatch,
                                           xai,
                                           linkingColumnSize_);
                          xai += linkingColumnSize_;
                          for (int j = 0; j < localAggCount; ++j) {
                            final long xa =
                                Arith.toUnsignedBig(xLongBatch.xs[xi])
                                    .add(Arith.toUnsignedBig(
                                        aLongBatch[ai]))
                                    .remainder(modulusBig)
                                    .longValue();
                            Rep.toBytes(xa,
                                        xaBytesBatch,
                                        xai,
                                        valueSize,
                                        IntegerRep.TWOS_COMPLEMENT,
                                        ByteOrder.BIG_ENDIAN,
                                        false);
                            ++xi;
                            ++ai;
                            xai += valueSize;
                          }
                        }
                      }
                    } else {
                      for (int i = 0; i < localRowBatch; ++i) {
                        System.arraycopy(xBigBatch.id,
                                         i * linkingColumnSize_,
                                         xaBytesBatch,
                                         xai,
                                         linkingColumnSize_);
                        xai += linkingColumnSize_;
                        for (int j = 0; j < localAggCount; ++j) {
                          final BigInteger xa =
                              xBigBatch.xs[xi]
                                  .add(aBigBatch[ai])
                                  .remainder(modulusBig);
                          Rep.toBytes(xa,
                                      xaBytesBatch,
                                      xai,
                                      valueSize,
                                      IntegerRep.TWOS_COMPLEMENT,
                                      ByteOrder.BIG_ENDIAN,
                                      false);
                          ++xi;
                          ++ai;
                          xai += valueSize;
                        }
                      }
                    }
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
          xaFuture_.addListener(new FutureListener<Object>() {
            @Override
            public final void operationComplete(
                final Future<Object> future) {
              try {
                future.sync();
                fireTick(pipeline_);
              } catch (final Throwable e) {
                asyncFatal(e);
              }
            }
          });
          setState(State.DB_PH_S1_FINISH_COMPUTING_XA_BATCH);
        } break;

        case DB_PH_S1_FINISH_COMPUTING_XA_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(xaFuture_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!xaFuture_.isDone()) {
            return;
          }
          xaFuture_.sync();
          xaFuture_ = null;
          if (valuesFitInt_) {
            aIntBatchEntry_.release();
            aIntBatchEntry_ = null;
            aIntBatch_ = null;
          } else if (valuesFitLong_) {
            aLongBatchEntry_.release();
            aLongBatchEntry_ = null;
            aLongBatch_ = null;
          } else {
            aBigBatchEntry_.release();
            aBigBatchEntry_ = null;
            aBigBatch_ = null;
          }
          setState(State.DB_PH_S1_SEND_XA_BATCH_TO_PH_DB_S1);
        } break;

        case DB_PH_S1_SEND_XA_BATCH_TO_PH_DB_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(xaFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          write(ctx, xaBytesBatch_, 0, localRowBatch_ * xaBytesSize_);
          flush(ctx);
          localRowIndex_ += localRowBatch_;
          setState(State.DB_PH_S1_SEND_X_BATCH_TO_DB_PH_S3);
        } break;

        case DB_PH_S1_SEND_X_BATCH_TO_DB_PH_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(xaFuture_ == null);
              SST_ASSERT(valuesFitInt_ ? (xIntBatchEntry_ != null
                                          && xLongBatchEntry_ == null
                                          && xBigBatchEntry_ == null) :
                                         valuesFitLong_ ?
                                         (xIntBatchEntry_ == null
                                          && xLongBatchEntry_ != null
                                          && xBigBatchEntry_ == null) :
                                         (xIntBatchEntry_ == null
                                          && xLongBatchEntry_ == null
                                          && xBigBatchEntry_ != null));
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (!shd_.xIntBatchQueue.offer(xIntBatchEntry_)) {
              return;
            }
            xIntBatchEntry_ = null;
            xIntBatch_ = null;
          } else if (valuesFitLong_) {
            if (!shd_.xLongBatchQueue.offer(xLongBatchEntry_)) {
              return;
            }
            xLongBatchEntry_ = null;
            xLongBatch_ = null;
          } else {
            if (!shd_.xBigBatchQueue.offer(xBigBatchEntry_)) {
              return;
            }
            xBigBatchEntry_ = null;
            xBigBatch_ = null;
          }
          fireTick(Party.PH, StateStream.S3);
          setState(State.DB_PH_S1_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_PH_S1_RECV_Z_FROM_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(zBytes_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            final PoolEntry<int[]> entry = zIntQueue_.poll();
            if (entry == null) {
              return;
            }
            final int[] z = entry.object();
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              Rep.toBytes(z[i],
                          zBytes_,
                          j,
                          valueSize_,
                          IntegerRep.TWOS_COMPLEMENT,
                          ByteOrder.BIG_ENDIAN,
                          false);
            }
            entry.release();
          } else if (valuesFitLong_) {
            final PoolEntry<long[]> entry = zLongQueue_.poll();
            if (entry == null) {
              return;
            }
            final long[] z = entry.object();
            if (z == null) {
              return;
            }
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              Rep.toBytes(z[i],
                          zBytes_,
                          j,
                          valueSize_,
                          IntegerRep.TWOS_COMPLEMENT,
                          ByteOrder.BIG_ENDIAN,
                          false);
            }
            entry.release();
          } else {
            final PoolEntry<BigInteger[]> entry = zBigQueue_.poll();
            if (entry == null) {
              return;
            }
            final BigInteger[] z = entry.object();
            if (z == null) {
              return;
            }
            for (int i = 0, j = 0; i < z.length; ++i, j += valueSize_) {
              Rep.toBytes(z[i],
                          zBytes_,
                          j,
                          valueSize_,
                          IntegerRep.TWOS_COMPLEMENT,
                          ByteOrder.BIG_ENDIAN,
                          false);
            }
            entry.release();
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S1_SEND_Z_TO_PH_DB_S1);
        } break;

        case DB_PH_S1_SEND_Z_TO_PH_DB_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S1);
              SST_ASSERT(zBytes_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          write(ctx, zBytes_, 0, zBytes_.length);
          flush(ctx);
          setState(State.DB_PH_S1_NEXT_DOMAIN_TUPLE);
        } break;

        case DB_PH_S2_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next()) {
            localRowCount_ = -1;
            otherRowCount_ = -1;
            otherRowIndex_ = -1;
            otherRowBatch_ = -1;
            setState(State.DB_PH_S2_RECV_BOTH_ROW_COUNTS_FROM_DB_DB_RH);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case DB_PH_S2_RECV_BOTH_ROW_COUNTS_FROM_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
              SST_ASSERT(otherRowIndex_ == -1);
              SST_ASSERT(otherRowBatch_ == -1);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final BothRowCounts bothRowCounts =
              shd_.bothRowCountsQueues.get(otherDb_).poll();
          if (bothRowCounts == null) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          localRowCount_ = bothRowCounts.localRowCount;
          otherRowCount_ = bothRowCounts.otherRowCount;
          otherRowIndex_ = 0;
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.DB_PH_S2_NEXT_DOMAIN_TUPLE);
          } else {
            setState(State.DB_PH_S2_NEXT_B_BATCH);
          }
        } break;

        case DB_PH_S2_NEXT_B_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(otherRowCount_ >= 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ <= otherRowCount_);
              SST_ASSERT(otherRowBatch_ == -1);
              SST_ASSERT(bBytesBatchEntry_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (otherRowIndex_ < otherRowCount_) {
            otherRowBatch_ =
                (int)Math.min(otherRowCount_ - otherRowIndex_,
                              otherMaxBatch_);
            setState(State.DB_PH_S2_RECV_B_BATCH_FROM_DB_DB_SH);
          } else {
            setState(State.DB_PH_S2_NEXT_DOMAIN_TUPLE);
          }
        } break;

        case DB_PH_S2_RECV_B_BATCH_FROM_DB_DB_SH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(otherRowCount_ > 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ < otherRowCount_);
              SST_ASSERT(otherRowBatch_ > 0);
              SST_ASSERT(otherRowBatch_
                         <= otherRowCount_ - otherRowIndex_);
              SST_ASSERT(bBytesBatchEntry_ == null);
              SST_ASSERT(bBytesBatchQueue_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          bBytesBatchEntry_ = bBytesBatchQueue_.poll();
          if (bBytesBatchEntry_ == null) {
            return;
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S2_SEND_B_BATCH_TO_PH_DB_S2);
        } break;

        case DB_PH_S2_SEND_B_BATCH_TO_PH_DB_S2: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S2);
              SST_ASSERT(otherRowCount_ > 0);
              SST_ASSERT(otherRowIndex_ >= 0);
              SST_ASSERT(otherRowIndex_ < otherRowCount_);
              SST_ASSERT(otherRowBatch_ > 0);
              SST_ASSERT(otherRowBatch_
                         <= otherRowCount_ - otherRowIndex_);
              SST_ASSERT(bBytesBatchEntry_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          // Our batch size is not necessarily the same as the remote
          // batch size. It's a continuous stream, so it's okay.
          if (out_.get() > outLimit_) {
            return;
          }
          write(ctx,
                bBytesBatchEntry_.object(),
                0,
                otherRowBatch_ * valueSize_);
          flush(ctx);
          bBytesBatchEntry_.release();
          bBytesBatchEntry_ = null;
          otherRowIndex_ += otherRowBatch_;
          otherRowBatch_ = -1;
          setState(State.DB_PH_S2_NEXT_B_BATCH);
        } break;

        case DB_PH_S3_NEXT_DOMAIN_TUPLE: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (domainIterator_.next()) {
            localRowCount_ = -1;
            localRowIndex_ = -1;
            localRowBatch_ = -1;
            otherRowCount_ = -1;
            if (valuesFitInt_) {
              for (int i = 0; i < localAggCount_; ++i) {
                sInt_[i] = 0;
              }
            } else if (valuesFitLong_) {
              for (int i = 0; i < localAggCount_; ++i) {
                sLong_[i] = 0;
              }
            } else {
              for (int i = 0; i < localAggCount_; ++i) {
                sBig_[i] = BigInteger.ZERO;
              }
            }
            setState(State.DB_PH_S3_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1);
          } else {
            setState(State.DONE_QUERY);
          }
        } break;

        case DB_PH_S3_RECV_BOTH_ROW_COUNTS_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ == -1);
              SST_ASSERT(localRowIndex_ == -1);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ == -1);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final BothRowCounts bothRowCounts =
              shd_.dbPhS1ToDbPhS3BothRowCountsQueue.poll();
          if (bothRowCounts == null) {
            return;
          }
          fireTick(Party.PH, StateStream.S1);
          localRowCount_ = bothRowCounts.localRowCount;
          localRowIndex_ = 0;
          otherRowCount_ = bothRowCounts.otherRowCount;
          if (localRowCount_ == 0 || otherRowCount_ == 0) {
            setState(State.DB_PH_S3_NEXT_DOMAIN_TUPLE);
          } else {
            setState(State.DB_PH_S3_RECV_R_FROM_DB_DB_RH);
          }
        } break;

        case DB_PH_S3_RECV_R_FROM_DB_DB_RH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ <= localRowCount_);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ >= 0);
              SST_ASSERT(rIntEntry_ == null);
              SST_ASSERT(rLongEntry_ == null);
              SST_ASSERT(rBigEntry_ == null);
              SST_ASSERT(rInt_ == null);
              SST_ASSERT(rLong_ == null);
              SST_ASSERT(rBig_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            rIntEntry_ = shd_.rIntQueue.poll();
            if (rIntEntry_ == null) {
              return;
            }
            rInt_ = rIntEntry_.object();
            //log("rInt_ = " + Rep.toString(rInt_));
          } else if (valuesFitLong_) {
            rLongEntry_ = shd_.rLongQueue.poll();
            if (rLongEntry_ == null) {
              return;
            }
            rLong_ = rLongEntry_.object();
            //log("rLong_ = " + Rep.toString(rLong_));
          } else {
            rBigEntry_ = shd_.rBigQueue.poll();
            if (rBigEntry_ == null) {
              return;
            }
            rBig_ = rBigEntry_.object();
            //log("rBig_ = " + Rep.toString(rBig_));
          }
          fireTick(otherDb_, StateStream.S1);
          setState(State.DB_PH_S3_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_PH_S3_NEXT_LOCAL_ROW_BATCH: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ >= 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ <= localRowCount_);
              SST_ASSERT(localRowBatch_ == -1);
              SST_ASSERT(otherRowCount_ >= 0);
              SST_ASSERT(valuesFitInt_ ?
                             (rIntEntry_ != null && rLongEntry_ == null
                              && rBigEntry_ == null && rInt_ != null
                              && rLong_ == null && rBig_ == null) :
                             valuesFitLong_ ?
                             (rIntEntry_ == null && rLongEntry_ != null
                              && rBigEntry_ == null && rInt_ == null
                              && rLong_ != null && rBig_ == null) :
                             (rIntEntry_ == null && rLongEntry_ == null
                              && rBigEntry_ != null && rInt_ == null
                              && rLong_ == null && rBig_ != null));
              SST_ASSERT(xIntBatchEntry_ == null);
              SST_ASSERT(xLongBatchEntry_ == null);
              SST_ASSERT(xBigBatchEntry_ == null);
              SST_ASSERT(xIntBatch_ == null);
              SST_ASSERT(xLongBatch_ == null);
              SST_ASSERT(xBigBatch_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (localRowIndex_ < localRowCount_) {
            localRowBatch_ =
                (int)Math.min(localRowCount_ - localRowIndex_,
                              localMaxBatch_);
            setState(State.DB_PH_S3_RECV_X_BATCH_FROM_DB_PH_S1);
          } else {
            setState(State.DB_PH_S3_FINISH_COMPUTING_S);
          }
        } break;

        case DB_PH_S3_RECV_X_BATCH_FROM_DB_PH_S1: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ > 0);
              SST_ASSERT(xIntBatchEntry_ == null);
              SST_ASSERT(xLongBatchEntry_ == null);
              SST_ASSERT(xBigBatchEntry_ == null);
              SST_ASSERT(xIntBatch_ == null);
              SST_ASSERT(xLongBatch_ == null);
              SST_ASSERT(xBigBatch_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            xIntBatchEntry_ = shd_.xIntBatchQueue.poll();
            if (xIntBatchEntry_ == null) {
              return;
            }
            xIntBatch_ = xIntBatchEntry_.object();
          } else if (valuesFitLong_) {
            xLongBatchEntry_ = shd_.xLongBatchQueue.poll();
            if (xLongBatchEntry_ == null) {
              return;
            }
            xLongBatch_ = xLongBatchEntry_.object();
          } else {
            xBigBatchEntry_ = shd_.xBigBatchQueue.poll();
            if (xBigBatchEntry_ == null) {
              return;
            }
            xBigBatch_ = xBigBatchEntry_.object();
          }
          fireTick(Party.PH, StateStream.S1);
          setState(State.DB_PH_S3_RECV_YB_BATCH_FROM_PH_DB_S3);
        } break;

        case DB_PH_S3_RECV_YB_BATCH_FROM_PH_DB_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ > 0);
              SST_ASSERT(ybBytesBatch_ != null);
              SST_ASSERT(ybBytesBatch_.length % valueSize_ == 0);
              SST_ASSERT(ybBytesBatch_.length / valueSize_
                         >= localRowBatch_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (in_.readableBytes() < localRowBatch_ * valueSize_) {
            ctx.read();
            return;
          }
          in_.readBytes(ybBytesBatch_, 0, localRowBatch_ * valueSize_);
          setState(State.DB_PH_S3_START_COMPUTING_S_ITERATION);
        } break;

        case DB_PH_S3_START_COMPUTING_S_ITERATION: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ > 0);
              SST_ASSERT(sFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          final int modulusInt = modulusInt_;
          final long modulusLong = modulusLong_;
          final BigInteger modulusBig = modulusBig_;
          final int valueSize = valueSize_;
          final boolean valuesFitInt = valuesFitInt_;
          final boolean valuesFitLong = valuesFitLong_;
          final int localAggCount = localAggCount_;
          final int localRowBatch = localRowBatch_;
          final int[] sInt = sInt_;
          final long[] sLong = sLong_;
          final BigInteger[] sBig = sBig_;
          final XIntBatch xIntBatch = xIntBatch_;
          final XLongBatch xLongBatch = xLongBatch_;
          final XBigBatch xBigBatch = xBigBatch_;
          final byte[] ybBytesBatch = ybBytesBatch_;
          sFuture_ =
              globals_.workerThreadGroup().submit(new Runnable() {
                @Override
                public final void run() {
                  try {
                    if (valuesFitInt) {
                      if (modulusInt == 0
                          || modulusInt == Integer.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusInt)) {
                        // m is a power of two in [1, 2^32]
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final int yb =
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (Integer)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sInt[j] += xIntBatch.xs[xi] * yb;
                            ++xi;
                          }
                        }
                      } else if (modulusInt > 0) {
                        // m is a non-power-of-two in [1, 2^31]
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final long yb =
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (Integer)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sInt[j] =
                                (int)(((long)sInt[j]
                                       + (long)xIntBatch.xs[xi] * yb)
                                      % modulusInt);
                            ++xi;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^31, 2^32]
                        final long m = Arith.toUnsignedLong(modulusInt);
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final long yb = Arith.toUnsignedLong(
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (Integer)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false));
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sInt[j] = (int)Arith.unsignedMod(
                                Arith.toUnsignedLong(sInt[j])
                                    + Arith.toUnsignedLong(
                                          xIntBatch.xs[xi])
                                          * yb,
                                m);
                            ++xi;
                          }
                        }
                      }
                    } else if (valuesFitLong) {
                      if (modulusLong == 0
                          || modulusLong == Long.MIN_VALUE
                          || Arith.isPowerOfTwo(modulusLong)) {
                        // m is a power of two in [2^33, 2^64]
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final long yb =
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (Long)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sLong[j] += xLongBatch.xs[xi] * yb;
                            ++xi;
                          }
                        }
                      } else if (modulusLong > 0) {
                        // m is a non-power-of-two in [2^32, 2^63]
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final BigInteger yb =
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (BigInteger)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false);
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sLong[j] =
                                BigInteger.valueOf(sLong[j])
                                    .add(BigInteger
                                             .valueOf(xLongBatch.xs[xi])
                                             .multiply(yb))
                                    .remainder(modulusBig)
                                    .longValue();
                            ++xi;
                          }
                        }
                      } else {
                        // m is a non-power-of-two in [2^63, 2^64]
                        int xi = 0;
                        int ybi = 0;
                        for (int i = 0; i < localRowBatch; ++i) {
                          final BigInteger yb = Arith.toUnsignedBig(
                              Rep.fromBytes(ybBytesBatch,
                                            ybi,
                                            valueSize,
                                            (Long)null,
                                            IntegerRep.PURE_UNSIGNED,
                                            ByteOrder.BIG_ENDIAN,
                                            false));
                          ybi += valueSize;
                          for (int j = 0; j < localAggCount; ++j) {
                            sLong[j] =
                                Arith.toUnsignedBig(sLong[j])
                                    .add(Arith
                                             .toUnsignedBig(
                                                 xLongBatch.xs[xi])
                                             .multiply(yb))
                                    .remainder(modulusBig)
                                    .longValue();
                            ++xi;
                          }
                        }
                      }
                    } else {
                      int xi = 0;
                      int ybi = 0;
                      for (int i = 0; i < localRowBatch; ++i) {
                        final BigInteger yb =
                            Rep.fromBytes(ybBytesBatch,
                                          ybi,
                                          valueSize,
                                          (BigInteger)null,
                                          IntegerRep.PURE_UNSIGNED,
                                          ByteOrder.BIG_ENDIAN,
                                          false);
                        ybi += valueSize;
                        for (int j = 0; j < localAggCount; ++j) {
                          sBig[j] =
                              sBig[j]
                                  .add(xBigBatch.xs[xi].multiply(yb))
                                  .remainder(modulusBig);
                          ++xi;
                        }
                      }
                    }
                  } catch (final Throwable e) {
                    asyncFatal(e);
                  }
                }
              });
          sFuture_.addListener(new FutureListener<Object>() {
            @Override
            public final void operationComplete(
                final Future<Object> future) {
              try {
                future.sync();
                fireTick(pipeline_);
              } catch (final Throwable e) {
                asyncFatal(e);
              }
            }
          });
          setState(State.DB_PH_S3_FINISH_COMPUTING_S_ITERATION);
        } break;

        case DB_PH_S3_FINISH_COMPUTING_S_ITERATION: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(localRowCount_ > 0);
              SST_ASSERT(localRowIndex_ >= 0);
              SST_ASSERT(localRowIndex_ < localRowCount_);
              SST_ASSERT(localRowBatch_ > 0);
              SST_ASSERT(sFuture_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (!sFuture_.isDone()) {
            return;
          }
          sFuture_.sync();
          sFuture_ = null;
          if (valuesFitInt_) {
            xIntBatchEntry_.release();
            xIntBatchEntry_ = null;
            xIntBatch_ = null;
          } else if (valuesFitLong_) {
            xLongBatchEntry_.release();
            xLongBatchEntry_ = null;
            xLongBatch_ = null;
          } else {
            xBigBatchEntry_.release();
            xBigBatchEntry_ = null;
            xBigBatch_ = null;
          }
          localRowIndex_ += localRowBatch_;
          localRowBatch_ = -1;
          setState(State.DB_PH_S3_NEXT_LOCAL_ROW_BATCH);
        } break;

        case DB_PH_S3_FINISH_COMPUTING_S: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(
                  valuesFitInt_ ?
                      (sInt_ != null && sLong_ == null && sBig_ == null
                       && rIntEntry_ != null && rLongEntry_ == null
                       && rBigEntry_ == null && rInt_ != null
                       && rLong_ == null && rBig_ == null) :
                      valuesFitLong_ ?
                      (sInt_ == null && sLong_ != null && sBig_ == null
                       && rIntEntry_ == null && rLongEntry_ != null
                       && rBigEntry_ == null && rInt_ == null
                       && rLong_ != null && rBig_ == null) :
                      (sInt_ == null && sLong_ == null && sBig_ != null
                       && rIntEntry_ == null && rLongEntry_ == null
                       && rBigEntry_ != null && rInt_ == null
                       && rLong_ == null && rBig_ != null));
              SST_ASSERT(sBytes_ != null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (valuesFitInt_) {
            if (modulusInt_ == 0 || modulusInt_ == Integer.MIN_VALUE
                || Arith.isPowerOfTwo(modulusInt_)) {
              // m is a power of two in [1, 2^32]
              for (int j = 0; j < localAggCount_; ++j) {
                sInt_[j] -= rInt_[j];
              }
            } else if (modulusInt_ > 0) {
              // m is a non-power-of-two in [1, 2^31]
              for (int j = 0; j < localAggCount_; ++j) {
                sInt_[j] -= rInt_[j];
                if (sInt_[j] < 0) {
                  sInt_[j] += modulusInt_;
                }
              }
            } else {
              // m is a non-power-of-two in [2^31, 2^32]
              final long m = Arith.toUnsignedLong(modulusInt_);
              for (int j = 0; j < localAggCount_; ++j) {
                long s = Arith.toUnsignedLong(sInt_[j])
                         - Arith.toUnsignedLong(rInt_[j]);
                if (s < 0) {
                  s += m;
                }
                sInt_[j] = (int)s;
              }
            }
            {
              int sBytesIndex = 0;
              for (int j = 0; j < localAggCount_; ++j) {
                Rep.toBytes(sInt_[j],
                            sBytes_,
                            sBytesIndex,
                            valueSize_,
                            IntegerRep.TWOS_COMPLEMENT,
                            ByteOrder.BIG_ENDIAN,
                            false);
                sBytesIndex += valueSize_;
              }
            }
          } else if (valuesFitLong_) {
            if (modulusLong_ == 0 || modulusLong_ == Long.MIN_VALUE
                || Arith.isPowerOfTwo(modulusLong_)) {
              // m is a power of two in [2^33, 2^64]
              for (int j = 0; j < localAggCount_; ++j) {
                sLong_[j] -= rLong_[j];
              }
            } else if (modulusLong_ > 0) {
              // m is a non-power-of-two in [2^32, 2^63]
              for (int j = 0; j < localAggCount_; ++j) {
                sLong_[j] -= rLong_[j];
                if (sLong_[j] < 0) {
                  sLong_[j] += modulusLong_;
                }
              }
            } else {
              // m is a non-power-of-two in [2^63, 2^64]
              for (int j = 0; j < localAggCount_; ++j) {
                if (Arith.unsignedCmp(sLong_[j], rLong_[j]) >= 0) {
                  sLong_[j] -= rLong_[j];
                } else {
                  sLong_[j] = modulusLong_ - (rLong_[j] - sLong_[j]);
                }
              }
            }
            {
              int sBytesIndex = 0;
              for (int j = 0; j < localAggCount_; ++j) {
                Rep.toBytes(sLong_[j],
                            sBytes_,
                            sBytesIndex,
                            valueSize_,
                            IntegerRep.TWOS_COMPLEMENT,
                            ByteOrder.BIG_ENDIAN,
                            false);
                sBytesIndex += valueSize_;
              }
            }
          } else {
            for (int j = 0; j < localAggCount_; ++j) {
              if (sBig_[j].compareTo(rBig_[j]) >= 0) {
                sBig_[j] = sBig_[j].subtract(rBig_[j]);
              } else {
                sBig_[j] =
                    modulusBig_.subtract(rBig_[j].subtract(sBig_[j]));
              }
            }
            {
              int sBytesIndex = 0;
              for (int j = 0; j < localAggCount_; ++j) {
                Rep.toBytes(sBig_[j],
                            sBytes_,
                            sBytesIndex,
                            valueSize_,
                            IntegerRep.TWOS_COMPLEMENT,
                            ByteOrder.BIG_ENDIAN,
                            false);
                sBytesIndex += valueSize_;
              }
            }
          }
          if (valuesFitInt_) {
            rIntEntry_.release();
            rIntEntry_ = null;
            rInt_ = null;
          } else if (valuesFitLong_) {
            rLongEntry_.release();
            rLongEntry_ = null;
            rLong_ = null;
          } else {
            rBigEntry_.release();
            rBigEntry_ = null;
            rBig_ = null;
          }
          setState(State.DB_PH_S3_SEND_S_TO_PH_DB_S3);
        } break;

        case DB_PH_S3_SEND_S_TO_PH_DB_S3: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsPh_);
              SST_ASSERT(stateStream_ == StateStream.S3);
              SST_ASSERT(rIntEntry_ == null);
              SST_ASSERT(rLongEntry_ == null);
              SST_ASSERT(rBigEntry_ == null);
              SST_ASSERT(rInt_ == null);
              SST_ASSERT(rLong_ == null);
              SST_ASSERT(rBig_ == null);
              SST_ASSERT(valuesFitInt_ ?
                             (sInt_ != null && sLong_ == null
                              && sBig_ == null) :
                             valuesFitLong_ ?
                             (sInt_ == null && sLong_ != null
                              && sBig_ == null) :
                             (sInt_ == null && sLong_ == null
                              && sBig_ != null));
              SST_ASSERT(sBytes_ != null);
              SST_ASSERT(sFuture_ == null);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          if (out_.get() > outLimit_) {
            return;
          }
          write(ctx, sBytes_);
          flush(ctx);
          setState(State.DB_PH_S3_NEXT_DOMAIN_TUPLE);
        } break;

        case DB_DB_DUPLEX: {
          if (!SST_NDEBUG) {
            try {
              SST_ASSERT(localPartyIsDb_);
              SST_ASSERT(remotePartyIsDb_);
            } catch (final Throwable e) {
              SST_ASSERT(e);
            }
          }
          for (boolean motion = true; motion;) {
            motion = false;
            if (tickSender(ctx)) {
              motion = true;
            }
            if (tickRecver(ctx)) {
              motion = true;
            }
          }
          if (senderState_ == State.DB_DB_SH_DONE
              && recverState_ == State.DB_DB_RH_DONE) {
            setState(State.DONE_QUERY);
          } else {
            return;
          }
        } break;

        case DONE_QUERY: {
          totalRxAtQueryDone_ =
              totalRx_ + (initialReadableBytes - in_.readableBytes());
          log("This query received a total of "
              + (totalRxAtQueryDone_ - totalRxAtQueryStart_)
              + " bytes from " + remoteParty_.toString().toUpperCase()
              + ".");
          shd_.done();
          resetForNextQuery();
          if (outgoing_) {
            setState(State.SEND_QUERY);
            return;
          } else {
            setState(State.RECV_QUERY_1);
          }
        } break;

        default:
          throw new ImpossibleException();
      }
    }
  }

  //
  // Sometimes Netty calls a future listener or userEventTriggered
  // immediately upon registering a listener or firing an event. This
  // can cause recursive calls to a handler. This can also occur when
  // combining the two.
  //
  // For example, if a handler calls ctx.write(x).addFutureListener(y)
  // and y fires an event back at the handler, this sometimes causes an
  // immediate call to userEventTriggered as if you had directly called
  // it yourself.
  //
  // This wreaks havoc on the state machine, as it's not supposed to
  // recursively call itself. It suffices to simply ignore such calls,
  // which is what inTick_ is for, as if the state machine causes a
  // recursive tick, then it's already awake and will continue any
  // processing on the next loop anyway.
  //
  // Only the state machine runs code that risks this situation, so
  // there's no need to use the inTick_ strategy elsewhere.
  //

  private boolean inTick_ = false;

  private final void tick(final ChannelHandlerContext ctx)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (!inTick_) {
      inTick_ = true;
      final int a = in_.readableBytes();
      tick2(ctx);
      final int b = in_.readableBytes();
      totalRx_ += a - b;
      inTick_ = false;
    }
  }

  //--------------------------------------------------------------------

  @Override
  public final void channelActive(final ChannelHandlerContext ctx)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (checkFatal(ctx)) {
      return;
    }
    try {
      if (!outgoing_) {
        ctx.read();
      }
    } catch (final Throwable e) {
      fatal_.set(true);
      throw e;
    }
    updateLastActivityTime();
  }

  @Override
  public final void userEventTriggered(final ChannelHandlerContext ctx,
                                       final Object event)
      throws Exception {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(ctx != null);
        SST_ASSERT(event != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (event == ZOMBIE_CHECK) {
      handleZombieCheck(ctx);
    } else if (event == TICK) {
      tick(ctx);
      updateLastActivityTime();
    } else if (event instanceof PhStartQueryEvent) {
      handlePhStartQueryEvent(ctx, (PhStartQueryEvent)event);
      updateLastActivityTime();
    } else if (event instanceof DbStartQueryEvent) {
      handleDbStartQueryEvent(ctx, (DbStartQueryEvent)event);
      updateLastActivityTime();
    } else {
      ctx.fireUserEventTriggered(event);
    }
  }
}
