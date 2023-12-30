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
import com.stealthsoftwareinc.sst.Enums;
import com.stealthsoftwareinc.sst.ImpossibleException;
import com.stealthsoftwareinc.sst.ToInt;
import com.stealthsoftwareinc.sst.ToJson;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

public enum ColumnType implements ToInt, ToJson {
  STRING;

  //--------------------------------------------------------------------
  // Integer representation
  //--------------------------------------------------------------------

  @Override
  public int toInt() {
    return ordinal();
  }

  public static ColumnType fromInt(final int src) {
    return Enums.fromInt(ColumnType.class, src);
  }

  //--------------------------------------------------------------------
  // String representation
  //--------------------------------------------------------------------

  @Override
  public String toString() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static ColumnType fromString(final CharSequence src) {
    return Enums.fromString(ColumnType.class, src, true);
  }

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  @Override
  public final String toJson() {
    return toString();
  }

  public static CreateFromJson<ColumnType> fromJson() {
    return Enums.fromJson(ColumnType.class, true);
  }

  //--------------------------------------------------------------------

  private final Object parseValue(final String src) {
    if (!SST_NDEBUG) {
      SST_ASSERT(src != null);
    }
    switch (this) {
      case STRING:
        return src;
      default:
        throw new ImpossibleException();
    }
  }

  public final Object parseValue(final CharSequence src) {
    if (!SST_NDEBUG) {
      SST_ASSERT(src != null);
    }
    return parseValue(src.toString());
  }

  //--------------------------------------------------------------------
}
