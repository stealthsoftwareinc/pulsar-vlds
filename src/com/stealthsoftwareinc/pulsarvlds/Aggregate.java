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

import com.stealthsoftwareinc.sst.ImpossibleException;

public final class Aggregate {

  private final AggregateFunction function_;
  private final Column column_;

  public Aggregate(final AggregateFunction function,
                   final Column column) {
    if (!SST_NDEBUG) {
      SST_ASSERT(function != null);
      SST_ASSERT(column != null);
    }
    function_ = function;
    column_ = column;
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final AggregateFunction function() {
    return function_;
  }

  public final Column column() {
    return column_;
  }

  public final Table table() {
    return column().table();
  }

  public final DbInfo dbInfo() {
    return table().dbInfo();
  }

  public final Party db() {
    return dbInfo().db();
  }

  //--------------------------------------------------------------------

  public final void toSql(final StringBuilder sql) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(sql != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    switch (function()) {
      case COUNT: {
        sql.append("(CASE WHEN ");
        sql.append(column().underlyingName());
        sql.append(" IS NULL THEN 0 ELSE 1 END)");
      } break;
      case SUM: {
        sql.append("(CASE WHEN ");
        sql.append(column().underlyingName());
        sql.append(" IS NULL THEN 0 ELSE ");
        sql.append(column().underlyingName());
        sql.append(" END)");
      } break;
      case AVG:
      case STDEV:
      case STDEVP:
      case VAR:
      case VARP: {
        sql.append("(CASE WHEN ");
        sql.append(column().underlyingName());
        sql.append(" IS NULL THEN 0 ELSE 1 END)");
        sql.append(", (CASE WHEN ");
        sql.append(column().underlyingName());
        sql.append(" IS NULL THEN 0 ELSE ");
        sql.append(column().underlyingName());
        sql.append(" END)");
      } break;
      default:
        throw new ImpossibleException();
    }
  }

  public final int aggCount() {
    switch (function()) {
      case COUNT:
        return 1;
      case SUM:
        return 1;
      case AVG:
        return 2;
      case STDEV:
      case STDEVP:
      case VAR:
      case VARP:
        // The third result (x*x for computing the sum of squares) is
        // virtual and should be computed by the caller.
        return 3;
      default:
        throw new ImpossibleException();
    }
  }

  public final boolean shouldScale(final int i) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(i >= 0);
        SST_ASSERT(i < aggCount());
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    switch (function()) {
      case COUNT:
        return false;
      case SUM:
        return true;
      case AVG:
        return i > 0;
      case STDEV:
      case STDEVP:
      case VAR:
      case VARP:
        return i > 0;
      default:
        throw new ImpossibleException();
    }
  }

  //--------------------------------------------------------------------
}
