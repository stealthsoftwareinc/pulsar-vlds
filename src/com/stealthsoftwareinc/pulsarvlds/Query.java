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

import com.stealthsoftwareinc.sst.Json;
import com.stealthsoftwareinc.sst.QueryStringException;
import com.stealthsoftwareinc.sst.Uris;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Query {

  private final List<Aggregate> aggregates_;
  private final Map<Party, List<Aggregate>> aggregatesForDb_;
  private final List<Column> groupBys_;
  private final Map<Party, Condition> prefilters_;
  private final Map<Party, Integer> aggCounts_;

  private static class FromQueryStringTag {};
  private static final FromQueryStringTag fromQueryStringTag = null;

  private Query(final String src,
                final Lexicon lexicon,
                final FromQueryStringTag fromQueryStringTag) {
    if (!SST_NDEBUG) {
      SST_ASSERT(src != null);
      SST_ASSERT(lexicon != null);
    }

    final ArrayList<Aggregate> aggregates = new ArrayList<Aggregate>();
    final Map<Party, List<Aggregate>> aggregatesForDb =
        new HashMap<Party, List<Aggregate>>();
    for (final Party db : Party.dbValues()) {
      aggregatesForDb.put(db, new ArrayList<Aggregate>());
    }
    final ArrayList<Column> groupBys = new ArrayList<Column>();
    final HashMap<Party, Condition> prefilters =
        new HashMap<Party, Condition>();
    final HashMap<Party, Integer> aggCounts =
        new HashMap<Party, Integer>();

    for (final String term : src.split("&", -1)) {
      if (term.isEmpty()) {
        continue;
      }
      final String[] pair = term.split("=", 2);
      final String lhs = Uris.decode(pair[0]);

      if (lhs.equals("aggregate")) {

        if (pair.length == 1) {
          throw new QueryStringException(
              "parameter must have an argument: "
              + Json.smartQuote(lhs));
        }
        final String rhs = Uris.decode(pair[1]);
        final String[] funCol = rhs.split(":", 2);
        if (funCol.length != 2) {
          throw new QueryStringException(
              "parameter argument must contain a colon character: "
              + Json.smartQuote(rhs));
        }
        final AggregateFunction function;
        try {
          function = AggregateFunction.fromString(funCol[0]);
        } catch (final EnumConstantNotPresentException e) {
          throw new QueryStringException(
              "unknown aggregate function: "
                  + Json.smartQuote(funCol[0]),
              e);
        }
        final Column column;
        try {
          column = lexicon.findColumn(funCol[1]);
        } catch (final AmbiguousColumnException e) {
          throw new QueryStringException(e);
        } catch (final UnknownColumnException e) {
          throw new QueryStringException(e);
        }
        aggregates.add(new Aggregate(function, column));
        aggregatesForDb.get(column.db())
            .add(new Aggregate(function, column));

      } else if (lhs.equals("prefilter")) {

        if (pair.length == 1) {
          throw new QueryStringException(
              "parameter must have an argument: "
              + Json.smartQuote(lhs));
        }
        final String rhs = Uris.decode(pair[1]);
        final String[] tableAndCondition = rhs.split(":", 2);
        if (tableAndCondition.length != 2) {
          throw new QueryStringException(
              "parameter argument must contain a colon character: "
              + Json.smartQuote(rhs));
        }
        final Table table;
        try {
          table = lexicon.findTable(tableAndCondition[0]);
        } catch (final UnknownTableException e) {
          throw new QueryStringException(e);
        }
        final Condition condition = Condition.fromQueryString(
            CharBuffer.wrap(tableAndCondition[1]),
            lexicon,
            Collections.singleton(table.db()));
        if (prefilters.put(table.db(), condition) != null) {
          throw new QueryStringException(
              "table already has a prefilter: " + table.name());
        }

      } else if (lhs.equals("group_by")) {

        if (pair.length == 1) {
          throw new QueryStringException(
              "parameter must have an argument: "
              + Json.smartQuote(lhs));
        }
        final String rhs = Uris.decode(pair[1]);
        final Column column;
        try {
          column = lexicon.findColumn(rhs);
        } catch (final AmbiguousColumnException e) {
          throw new QueryStringException(e);
        } catch (final UnknownColumnException e) {
          throw new QueryStringException(e);
        }
        if (column.domain() == null) {
          throw new QueryStringException(
              "column cannot be grouped by"
              + " because it has an unspecified domain: "
              + Json.smartQuote(column.table().name() + "."
                                + column.name()));
        }
        groupBys.add(column);

      } else {

        throw new QueryStringException("unknown parameter: "
                                       + Json.smartQuote(lhs));
      }
    }

    if (aggregates.isEmpty()) {
      throw new QueryStringException(
          "parameter must appear at least once: "
          + Json.smartQuote("aggregate"));
    }

    if (groupBys.isEmpty()) {
      throw new QueryStringException(
          "parameter must appear at least once: "
          + Json.smartQuote("group_by"));
    }

    for (final Party db : Party.dbValues()) {
      int n = 0;
      for (final Aggregate aggregate : aggregates) {
        if (aggregate.db() == db) {
          n += aggregate.aggCount();
        }
      }
      aggCounts.put(db, n);
    }

    for (final Party db : Party.dbValues()) {
      aggregatesForDb.put(
          db,
          Collections.unmodifiableList(aggregatesForDb.get(db)));
    }

    aggregates_ = Collections.unmodifiableList(aggregates);
    aggregatesForDb_ = Collections.unmodifiableMap(aggregatesForDb);
    groupBys_ = Collections.unmodifiableList(groupBys);
    prefilters_ = Collections.unmodifiableMap(prefilters);
    aggCounts_ = Collections.unmodifiableMap(aggCounts);
  }

  public static Query fromQueryString(final CharSequence src,
                                      final Lexicon lexicon) {
    if (!SST_NDEBUG) {
      SST_ASSERT(src != null);
      SST_ASSERT(lexicon != null);
    }
    return new Query(src.toString(), lexicon, fromQueryStringTag);
  }

  public final List<Aggregate> aggregates() {
    return aggregates_;
  }

  public final List<Aggregate> aggregates(final Party db) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return aggregatesForDb_.get(db);
  }

  public final List<Column> groupBys() {
    return groupBys_;
  }

  public final Map<Party, Condition> prefilters() {
    return prefilters_;
  }

  public final int aggCount(final Party db) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(db != null);
        SST_ASSERT(db.isDb());
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return aggCounts_.get(db);
  }

  public final int tupleCount() {
    BigInteger n = BigInteger.ONE;
    for (final Column c : groupBys_) {
      n = n.multiply(BigInteger.valueOf(c.domain().size()));
    }
    if (n.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
      throw new RuntimeException("tupleCount is too big");
    }
    return n.intValue();
  }
}
