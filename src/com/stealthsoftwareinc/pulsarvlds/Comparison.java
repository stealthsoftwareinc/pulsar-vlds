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

import java.util.List;

public final class Comparison {
  private final ComparisonOperator operator_;
  private final Column column_;
  private final Object literal_;

  //--------------------------------------------------------------------

  public Comparison(final ComparisonOperator operator,
                    final Column column) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(operator == ComparisonOperator.IS_NULL
                   || operator == ComparisonOperator.IS_NOT_NULL);
        SST_ASSERT(column != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    operator_ = operator;
    column_ = column;
    literal_ = null;
  }

  public Comparison(final ComparisonOperator operator,
                    final Column column,
                    final CharSequence literal) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(operator == ComparisonOperator.LT
                   || operator == ComparisonOperator.GT
                   || operator == ComparisonOperator.LE
                   || operator == ComparisonOperator.GE
                   || operator == ComparisonOperator.EQ
                   || operator == ComparisonOperator.NE);
        SST_ASSERT(column != null);
        SST_ASSERT(literal != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    operator_ = operator;
    column_ = column;
    literal_ = column.type().parseValue(literal);
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final ComparisonOperator operator() {
    return operator_;
  }

  public final Column column() {
    return column_;
  }

  public final Object literal() {
    return literal_;
  }

  //--------------------------------------------------------------------

  public final void toSql(final StringBuilder sql,
                          final List<Object> parameters,
                          final StringBuilder format) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(sql != null);
        SST_ASSERT(parameters != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    sql.append('(');
    sql.append(column().underlyingName());
    sql.append(' ');
    sql.append(operator().toSql());
    if (literal() != null) {
      sql.append(" ?");
      parameters.add(literal());
    }
    sql.append(')');
    if (format != null) {
      format.append('(');
      format.append(column().underlyingName().replace("%", "%%"));
      format.append(' ');
      format.append(operator().toSql());
      if (literal() != null) {
        format.append(" %s");
      }
      format.append(')');
    }
  }

  //--------------------------------------------------------------------
}
