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

import com.stealthsoftwareinc.sst.CreateFromJson;
import com.stealthsoftwareinc.sst.Enums;
import com.stealthsoftwareinc.sst.ToInt;
import com.stealthsoftwareinc.sst.ToJson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public enum Party implements ToInt, ToJson {
  PH,
  DB1,
  DB2;

  //--------------------------------------------------------------------

  private static final Set<Party> dbValues_;
  static {
    dbValues_ = Collections.unmodifiableSet(
        new HashSet<Party>(Arrays.asList(new Party[] {DB1, DB2})));
  }

  public static final Set<Party> dbValues() {
    return dbValues_;
  }

  //--------------------------------------------------------------------
  // Integer representation
  //--------------------------------------------------------------------

  @Override
  public int toInt() {
    return ordinal();
  }

  public static Party fromInt(final int src) {
    return Enums.fromInt(Party.class, values(), src);
  }

  //--------------------------------------------------------------------
  // String representation
  //--------------------------------------------------------------------

  @Override
  public String toString() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Party fromString(final CharSequence src) {
    return Enums.fromString(Party.class, src, true);
  }

  //--------------------------------------------------------------------
  // JSON representation
  //--------------------------------------------------------------------

  @Override
  public String toJson() {
    return toString();
  }

  public static CreateFromJson<Party> fromJson() {
    return Enums.fromJson(Party.class, true);
  }

  //--------------------------------------------------------------------

  public final boolean isDb() {
    return dbValues().contains(this);
  }

  //--------------------------------------------------------------------
}
