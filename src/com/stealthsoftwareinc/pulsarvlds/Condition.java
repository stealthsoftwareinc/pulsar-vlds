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

import com.stealthsoftwareinc.sst.JdbcSubprotocol;
import com.stealthsoftwareinc.sst.QueryStringException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public final class Condition {

  private final Comparison comparison_;

  private final ConditionOperator operator_;
  private final Condition left_;
  private final Condition right_;

  //--------------------------------------------------------------------

  private Condition(final Comparison comparison) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(comparison != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    comparison_ = comparison;
    operator_ = null;
    left_ = null;
    right_ = null;
  }

  private Condition(final ConditionOperator operator,
                    final Condition left) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(operator == ConditionOperator.NOT);
        SST_ASSERT(left != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    comparison_ = null;
    operator_ = operator;
    left_ = left;
    right_ = null;
  }

  private Condition(final ConditionOperator operator,
                    final Condition left,
                    final Condition right) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(operator == ConditionOperator.AND
                   || operator == ConditionOperator.OR);
        SST_ASSERT(left != null);
        SST_ASSERT(right != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    comparison_ = null;
    operator_ = operator;
    left_ = left;
    right_ = right;
  }

  //--------------------------------------------------------------------

  private static boolean isCondition(final Object stackElement) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(stackElement != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return stackElement instanceof Condition;
  }

  private static boolean isColumn(final Object stackElement) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(stackElement != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return stackElement instanceof Column;
  }

  private static boolean isLiteral(final Object stackElement) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(stackElement != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    return stackElement instanceof ConditionToken;
  }

  public static final Condition
  fromQueryString(final CharBuffer src,
                  final Lexicon lexicon,
                  final Collection<Party> dbs) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
        SST_ASSERT(lexicon != null);
        SST_ASSERT(dbs != null);
        for (final Party db : dbs) {
          SST_ASSERT(db.isDb());
        }
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    final LinkedList<Object> stack = new LinkedList<Object>();

    while (true) {
      final ConditionToken token = ConditionToken.fromQueryString(src);

      if (token == null) {
        break;
      }

      switch (token.type()) {
        case IDENTIFIER: {
          try {
            stack.push(lexicon.findColumn(dbs, token.text()));
          } catch (final AmbiguousColumnException e) {
            throw new QueryStringException(e);
          } catch (final UnknownColumnException e) {
            throw new QueryStringException(e);
          }
        } break;

        case LITERAL: {
          stack.push(token);
        } break;

        case IS_NULL:
        case IS_NOT_NULL: {
          if (stack.size() < 1) {
            throw new QueryStringException("invalid condition a");
          }
          final Object column = stack.pop();
          if (!isColumn(column)) {
            throw new QueryStringException("invalid condition b");
          }
          stack.push(new Condition(
              new Comparison(token.type().toComparisonOperator(),
                             (Column)column)));
        } break;

        case LT:
        case GT:
        case LE:
        case GE:
        case EQ:
        case NE: {
          if (stack.size() < 2) {
            throw new QueryStringException("invalid condition c");
          }
          ConditionToken.Type operator = token.type();
          Object literal = stack.pop();
          Object column = stack.pop();
          if (isColumn(column) && isLiteral(literal)) {
          } else if (isLiteral(column) && isColumn(literal)) {
            operator = operator.flip();
            final Object x = column;
            column = literal;
            literal = x;
          } else {
            throw new QueryStringException("invalid condition d");
          }
          try {
            stack.push(new Condition(
                new Comparison(operator.toComparisonOperator(),
                               (Column)column,
                               ((ConditionToken)literal).text())));
          } catch (final NumberFormatException e) {
            throw new QueryStringException(e);
          }
        } break;

        case NOT: {
          if (stack.size() < 1) {
            throw new QueryStringException("invalid condition e");
          }
          final Object left = stack.pop();
          if (!isCondition(left)) {
            throw new QueryStringException("invalid condition f");
          }
          stack.push(new Condition(token.type().toConditionOperator(),
                                   (Condition)left));
        } break;

        case AND:
        case OR: {
          if (stack.size() < 2) {
            throw new QueryStringException("invalid condition g");
          }
          final Object right = stack.pop();
          final Object left = stack.pop();
          if (!isCondition(left)) {
            throw new QueryStringException("invalid condition h");
          }
          if (!isCondition(right)) {
            throw new QueryStringException("invalid condition i");
          }
          stack.push(new Condition(token.type().toConditionOperator(),
                                   (Condition)left,
                                   (Condition)right));
        } break;
      }
    }

    if (stack.size() != 1) {
      throw new QueryStringException("invalid condition j");
    }
    final Object root = stack.peek();
    if (!isCondition(root)) {
      throw new QueryStringException("invalid condition");
    }
    return (Condition)root;
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final Comparison comparison() {
    return comparison_;
  }

  public final ConditionOperator operator() {
    return operator_;
  };

  public final Condition left() {
    return left_;
  };

  public final Condition right() {
    return right_;
  };

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
    if (comparison() != null) {
      comparison().toSql(sql, parameters, format);
    } else if (right() == null) {
      sql.append('(');
      sql.append(operator().toSql());
      sql.append(' ');
      if (format != null) {
        format.append('(');
        format.append(operator().toSql());
        format.append(' ');
      }
      left().toSql(sql, parameters, format);
      sql.append(')');
      if (format != null) {
        format.append(')');
      }
    } else {
      sql.append('(');
      if (format != null) {
        format.append('(');
      }
      left().toSql(sql, parameters, format);
      sql.append(' ');
      sql.append(operator().toSql());
      sql.append(' ');
      if (format != null) {
        format.append(' ');
        format.append(operator().toSql());
        format.append(' ');
      }
      right().toSql(sql, parameters, format);
      sql.append(')');
      if (format != null) {
        format.append(')');
      }
    }
  }

  //--------------------------------------------------------------------
}
