#
# Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
#
# Permission is hereby granted, free of charge, to any person
# obtaining a copy of this software and associated documentation
# files (the "Software"), to deal in the Software without
# restriction, including without limitation the rights to use,
# copy, modify, merge, publish, distribute, sublicense, and/or
# sell copies of the Software, and to permit persons to whom the
# Software is furnished to do so, subject to the following
# conditions:
#
# The above copyright notice and this permission notice (including
# the next paragraph) shall be included in all copies or
# substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
# OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
# NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
# HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
# WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
# FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
# OTHER DEALINGS IN THE SOFTWARE.
#
# SPDX-License-Identifier: MIT
#

sst_expect_prefix() {

  declare path
  declare prefix
  declare x

  if (($# == 0)); then
    path=$(cat | sst_csf)
    sst_csf path
  elif (($# == 1)); then
    path=$1
  else
    sst_expect_argument_count $# 0-1
  fi
  readonly path

  prefix=$(sst_get_prefix "$path" | sst_csf)
  sst_csf prefix
  readonly prefix

  if [[ "$prefix" == "" ]]; then
    x=$(sst_smart_quote "$path")
    sst_barf "Path must have a nonempty prefix: $x"
  fi

  printf '%s\n' "$prefix"

}; readonly -f sst_expect_prefix
