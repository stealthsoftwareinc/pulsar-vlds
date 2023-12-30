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
# sst_am_start [<file>]
#
# Start constructing an accumulative Automake source file in which to
# collect autogen output. <file> should specify a file that lives either
# in or below the current directory. If <file> is not given, it defaults
# to autogen.am.
#

sst_am_start() {

  # Bash >=4.2: declare -g    autogen_am_file
  # Bash >=4.2: declare -g    sst_am_start_has_been_called

  if [[ "${sst_am_start_has_been_called-}" ]]; then
    sst_barf "sst_am_start has already been called."
  fi
  readonly sst_am_start_has_been_called=1

  if (($# == 0)); then
    autogen_am_file=autogen.am
  elif (($# == 1)); then
    autogen_am_file=$1
  else
    sst_expect_argument_count $# 0-1
  fi

  autogen_am_file=$(sst_add_slash_abs_prefix "$autogen_am_file")
  autogen_am_file=${autogen_am_file%/}
  readonly autogen_am_file

  autogen_print_am_header >"$autogen_am_file"

  sst_trap_append '
    case $sst_trap_entry_status in
      0)
        case ${sst_am_finish_has_been_called+x} in
          "")
            sst_barf "you forgot to call sst_am_finish"
          ;;
        esac
      ;;
    esac
  ' EXIT

  sst_am_var_add EXTRA_DIST autogen

}; readonly -f sst_am_start
