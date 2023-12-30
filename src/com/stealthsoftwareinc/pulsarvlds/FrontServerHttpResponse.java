/*
 * Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice (including
 * the next paragraph) shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * SPDX-License-Identifier: MIT
 */

/*!
 * @file
 *
 * Defines the
 * <code>com.stealthsoftwareinc.pulsarvlds.FrontServerHttpResponse</code>
 * Java class.
 *
 * @examples
 * none
 *
 * @notablechanges
 * none
 */

package com.stealthsoftwareinc.pulsarvlds;

/* begin_imports */

import io.netty.handler.codec.http.DefaultFullHttpResponse;

/* end_imports */

/**
 *
 * @examples
 * none
 *
 * @notablechanges
 * none
 */

public class FrontServerHttpResponse {

  /**
 *
 * @examples
 * none
 *
 * @notablechanges
 * none
 */

  public boolean done;

  /**
 *
 * @examples
 * none
 *
 * @notablechanges
 * none
 */

  public final DefaultFullHttpResponse response;

  /**
 *
 * @examples
 * none
 *
 * @notablechanges
 * none
 */

  public FrontServerHttpResponse(
      final DefaultFullHttpResponse response) {
    this.done = false;
    this.response = response;
  }
}
