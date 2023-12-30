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

#
# Synopsis:
#
#       sst_ag_finish
#

sst_ag_finish() {

  # Bash >=4.2: declare -g    SST_NDEBUG
  # Bash >=4.2: declare -g    sst_ac_finish_has_been_called
  # Bash >=4.2: declare -g    sst_ag_finish_has_been_called
  # Bash >=4.2: declare -g    sst_ag_start_has_been_called
  # Bash >=4.2: declare -g    sst_am_finish_has_been_called

  if ((!SST_NDEBUG)); then

    if [[ "${sst_ag_finish_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_finish twice"
    fi
    sst_ag_finish_has_been_called=1
    readonly sst_ag_finish_has_been_called

    if [[ ! "${sst_ag_start_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_finish before sst_ag_start"
    fi

    if [[ "${sst_ac_finish_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_finish after sst_ac_finish"
    fi

    if [[ "${sst_am_finish_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_finish after sst_am_finish"
    fi

    if (($# != 0)); then
      sst_expect_argument_count $# 0
    fi

  fi

  sst_ac_finish

  sst_am_finish

}; readonly -f sst_ag_finish
