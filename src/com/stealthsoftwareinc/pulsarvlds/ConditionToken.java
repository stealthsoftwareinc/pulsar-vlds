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

//
// The ConditionToken class parses condition tokens from a sequence of
// characters from a query string. The tokens are used to represent an
// SQL WHERE condition in postfix notation. Here are some examples of
// conditions and their equivalent token sequences:
//
//           x = 2 OR y IS NULL
//       ->  x '2' (eq) y (is_null) (or)
//
//           (x = 2 OR y IS NULL) AND z > 'foo'
//       ->  x '2' (eq) y (is_null) (or) z 'foo' (gt) (and)
//
// Note that identifiers are unquoted, literals are quoted with single
// quotes, and operators are surrounded with parentheses.
//
// Identifiers and literals are parsed as opaque strings, with the work
// of interpreting them left to the caller.
//
// Only a limited set of condition features are supported according to
// the design of the overall system.
//

package com.stealthsoftwareinc.pulsarvlds;

import static com.stealthsoftwareinc.sst.Assert.SST_ASSERT;
import static com.stealthsoftwareinc.sst.Assert.SST_NDEBUG;

import com.stealthsoftwareinc.sst.QueryStringException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConditionToken {

  public enum Type {
    IDENTIFIER,
    LITERAL,
    IS_NULL,
    IS_NOT_NULL,
    LT,
    GT,
    LE,
    GE,
    EQ,
    NE,
    NOT,
    AND,
    OR;

    //------------------------------------------------------------------

    public static final Map<Type, ComparisonOperator>
        COMPARISON_OPERATORS;
    static {
      final Map<Type, ComparisonOperator> x =
          new HashMap<Type, ComparisonOperator>();
      x.put(IS_NULL, ComparisonOperator.IS_NULL);
      x.put(IS_NOT_NULL, ComparisonOperator.IS_NOT_NULL);
      x.put(LT, ComparisonOperator.LT);
      x.put(GT, ComparisonOperator.GT);
      x.put(LE, ComparisonOperator.LE);
      x.put(GE, ComparisonOperator.GE);
      x.put(EQ, ComparisonOperator.EQ);
      x.put(NE, ComparisonOperator.NE);
      COMPARISON_OPERATORS = Collections.unmodifiableMap(x);
    }

    public static final Map<Type, ConditionOperator>
        CONDITION_OPERATORS;
    static {
      final Map<Type, ConditionOperator> x =
          new HashMap<Type, ConditionOperator>();
      x.put(NOT, ConditionOperator.NOT);
      x.put(AND, ConditionOperator.AND);
      x.put(OR, ConditionOperator.OR);
      CONDITION_OPERATORS = Collections.unmodifiableMap(x);
    }

    public static final Set<Type> ALL_OPERATORS;
    static {
      final Set<Type> x = new HashSet<Type>();
      x.addAll(COMPARISON_OPERATORS.keySet());
      x.addAll(CONDITION_OPERATORS.keySet());
      ALL_OPERATORS = Collections.unmodifiableSet(x);
    }

    public static final Map<Type, Type> FLIPPED_OPERATORS;
    static {
      final Map<Type, Type> x = new HashMap<Type, Type>();
      x.put(LT, GT);
      x.put(GT, LT);
      x.put(LE, GE);
      x.put(GE, LE);
      x.put(EQ, EQ);
      x.put(NE, NE);
      FLIPPED_OPERATORS = Collections.unmodifiableMap(x);
    }

    public final ComparisonOperator toComparisonOperator() {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(COMPARISON_OPERATORS.keySet().contains(this));
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      return COMPARISON_OPERATORS.get(this);
    }

    public final ConditionOperator toConditionOperator() {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(CONDITION_OPERATORS.keySet().contains(this));
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      return CONDITION_OPERATORS.get(this);
    }

    public final Type flip() {
      if (!SST_NDEBUG) {
        try {
          SST_ASSERT(FLIPPED_OPERATORS.keySet().contains(this));
        } catch (final Throwable e) {
          SST_ASSERT(e);
        }
      }
      return FLIPPED_OPERATORS.get(this);
    }
  }

  private final Type type_;
  private final String text_;

  private ConditionToken(final Type type, final CharSequence text) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(type != null);
        SST_ASSERT(text != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    type_ = type;
    text_ = text.toString();
  }

  private static boolean isWhitespace(final char c) {
    return c == '\t' || c == '\n' || c == 11 || c == '\f' || c == '\r'
        || c == ' ';
  }

  private static boolean isIdentifierHead(final char c) {
    return (c >= 'A' && c <= 'Z') || c == '_' || (c >= 'a' && c <= 'z');
  }

  private static boolean isIdentifierTail(final char c) {
    return isIdentifierHead(c) || c == '.' || (c >= '0' && c <= '9');
  }

  private static boolean takeWhitespace(final CharBuffer src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (src.remaining() == 0) {
      return false;
    }
    if (!isWhitespace(src.get(src.position()))) {
      return false;
    }
    src.get();
    return true;
  }

  private static boolean takeIdentifierTail(final CharBuffer src,
                                            final StringBuilder text) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
        SST_ASSERT(text != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    if (src.remaining() == 0) {
      return false;
    }
    if (!isIdentifierTail(src.get(src.position()))) {
      return false;
    }
    text.append(src.get());
    return true;
  }

  private static boolean takeExactly(final CharBuffer src,
                                     final StringBuilder text,
                                     final CharSequence want) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
        SST_ASSERT(text != null);
        SST_ASSERT(want != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }
    final String wantString = want.toString();
    if (src.remaining() < wantString.length()) {
      return false;
    }
    for (int i = 0; i != wantString.length(); ++i) {
      if (src.get(src.position() + i) != wantString.charAt(i)) {
        return false;
      }
    }
    src.position(src.position() + wantString.length());
    text.append(wantString);
    return true;
  }

  public static final ConditionToken
  fromQueryString(final CharBuffer src) {
    if (!SST_NDEBUG) {
      try {
        SST_ASSERT(src != null);
      } catch (final Throwable e) {
        SST_ASSERT(e);
      }
    }

    while (takeWhitespace(src)) {
    }

    if (src.remaining() == 0) {
      return null;
    }

    final char c = src.get(src.position());

    final StringBuilder text = new StringBuilder();

    if (isIdentifierHead(c)) {
      text.append(src.get());
      while (takeIdentifierTail(src, text)) {
      }
      return new ConditionToken(Type.IDENTIFIER, text);
    }

    if (c == '\'') {
      src.get();
      while (true) {
        if (src.remaining() == 0) {
          throw new QueryStringException("unterminated literal");
        }
        final char d = src.get();
        if (d != '\'') {
          text.append(d);
        } else if (!takeExactly(src, text, "'")) {
          return new ConditionToken(Type.LITERAL, text);
        }
      }
    }

    if (c == '(') {
      for (final Type operator : Type.ALL_OPERATORS) {
        final String want =
            "(" + operator.name().toLowerCase(Locale.ROOT) + ")";
        if (takeExactly(src, text, want)) {
          return new ConditionToken(operator, text);
        }
      }
      throw new QueryStringException("unknown operator");
    }

    throw new QueryStringException("unexpected character");
  }

  //--------------------------------------------------------------------
  // Getters
  //--------------------------------------------------------------------

  public final Type type() {
    return type_;
  }

  public final String text() {
    return text_;
  }

  //--------------------------------------------------------------------
}
