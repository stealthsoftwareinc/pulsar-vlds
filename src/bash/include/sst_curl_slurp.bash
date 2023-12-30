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

sst_curl_slurp() {

  declare a
  declare b
  declare get_next
  declare headers
  declare url

  a=$'[\t ]*'
  readonly a

  b=$'[^\t >][^\t >]*'
  readonly b

  get_next=
  get_next+="s/^$a[Ll][Ii][Nn][Kk]$a:.*<$a\\($b\\)$a>$a;"
  get_next+="$a[Rr][Ee][Ll]$a=$a\"$a[Nn][Ee][Xx][Tt]$a\".*/\\1/p"
  readonly get_next

  headers=$sst_root_tmpdir/$FUNCNAME.$BASHPID.headers
  readonly headers

  sst_expect_argument_count $# 1-

  url=$1
  shift

  while [[ "$url" ]]; do
    curl -D "$headers" "$@" -- "$url"
    url=$(tr -d '\r' <"$headers" | sed -n "$get_next")
  done

}; readonly -f sst_curl_slurp
