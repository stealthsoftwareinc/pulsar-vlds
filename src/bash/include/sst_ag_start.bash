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
#       sst_ag_start
#
#       sst_ag_start <dir>
#
#       sst_ag_start <ac_file> <am_file>
#

sst_ag_start() {

  # Bash >=4.2: declare -g    SST_NDEBUG
  # Bash >=4.2: declare -g    sst_ac_start_has_been_called
  # Bash >=4.2: declare -g    sst_ag_start_has_been_called
  # Bash >=4.2: declare -g    sst_am_start_has_been_called

  declare    ac_file
  declare    am_file

  if ((!SST_NDEBUG)); then

    if [[ "${sst_ag_start_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_start twice"
    fi
    sst_ag_start_has_been_called=1
    readonly sst_ag_start_has_been_called

    if [[ "${sst_ac_start_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_start after sst_ac_start"
    fi

    if [[ "${sst_am_start_has_been_called-}" ]]; then
      sst_barf "You called sst_ag_start after sst_am_start"
    fi

  fi

  if (($# == 0)); then
    ac_file=autogen.ac
    am_file=autogen.am
  elif (($# == 1)); then
    if ((!SST_NDEBUG)); then
      if [[ "$1" == "" ]]; then
        sst_barf "<dir> must not be the empty string"
      fi
    fi
    if [[ "$1" == */ ]]; then
      ac_file=${1}autogen.ac
      am_file=${1}autogen.am
    else
      ac_file=$1/autogen.ac
      am_file=$1/autogen.am
    fi
  elif (($# == 2)); then
    ac_file=$1
    am_file=$2
    if ((!SST_NDEBUG)); then
      if [[ "$ac_file" == "" ]]; then
        sst_barf "<ac_file> must not be the empty string"
      fi
      if [[ "$ac_file" == */ ]]; then
        sst_barf "<ac_file> must not end with a slash"
      fi
      if [[ "$am_file" == "" ]]; then
        sst_barf "<am_file> must not be the empty string"
      fi
      if [[ "$am_file" == */ ]]; then
        sst_barf "<am_file> must not end with a slash"
      fi
    fi
  elif ((!SST_NDEBUG)); then
    sst_expect_argument_count $# 0-2
  fi

  if ((!SST_NDEBUG)); then
    sst_trap_append '
      if ((sst_trap_entry_status == 0)); then
        if [[ ! "${sst_ag_finish_has_been_called-}" ]]; then
          sst_barf "You forgot to call sst_ag_finish"
        fi
      fi
    ' EXIT
  fi

  sst_ac_start "$ac_file"
  sst_am_start "$am_file"

}; readonly -f sst_ag_start
