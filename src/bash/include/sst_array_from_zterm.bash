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

sst_array_from_zterm_helper() {

  od -A n -t o1 -v \
  | tr ' ' '\\' \
  | sed '
      1 s/^/$'\''/
      $ s/\\000$//
      $ s/$/'\''n/
      s/\\000/'\''n$'\''/g
    ' \
  | tr -d '\n' \
  | tr n '\n' \
  ;

  printf '%s\n' ")"

}; readonly -f sst_array_from_zterm_helper

sst_array_from_zterm() {

  # Bash >=4.2: declare -g    SST_NDEBUG

  if ((!SST_NDEBUG)); then
    if (($# < 1)); then
      sst_expect_argument_count $# 1-
    fi
    sst_expect_basic_identifier "$1"
  fi

  printf '%s\n' "$1=(" >"$sst_root_tmpdir/$FUNCNAME.$$.x"

  if (($# == 1)); then
    sst_array_from_zterm_helper >>"$sst_root_tmpdir/$FUNCNAME.$$.x"
  else
    shift
    "$@" | sst_array_from_zterm_helper >>"$sst_root_tmpdir/$FUNCNAME.$$.x"
  fi

  . "$sst_root_tmpdir/$FUNCNAME.$$.x"

}; readonly -f sst_array_from_zterm
