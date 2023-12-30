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

# TODO: After autogen_ac_append is eventually removed, the
# quoted-by-default convention will be gone and we can remove the
# printing of "[" and "]" in this function, as well as removing the
# opening "[" and the trailing "]" in the start and finish functions.

sst_ac_append() {

  if ((SST_DEBUG)); then
    sst_expect_errexit
  fi

  if [[ ! "${sst_ac_start_has_been_called+x}" ]]; then
    sst_barf 'sst_ac_start has not been called'
  fi

  if [[ "${sst_ac_finish_has_been_called+x}" ]]; then
    sst_barf 'sst_ac_finish has been called'
  fi

  if ((SST_DEBUG)); then
    if (($# != 0)); then
      sst_expect_argument_count $# 0
    fi
  fi

  printf ']\n' >>$autogen_ac_file
  cat >>$autogen_ac_file
  printf '[\n' >>$autogen_ac_file

}; readonly -f sst_ac_append
