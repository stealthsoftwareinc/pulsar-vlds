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

sst_am_finish() {

  # Bash >=4.2: declare -g -a sst_am_distribute_vars

  case ${sst_am_start_has_been_called+x} in
    "")
      sst_barf 'sst_am_start has not been called'
    ;;
  esac

  case ${sst_am_finish_has_been_called+x} in
    ?*)
      sst_barf 'sst_am_finish has already been called'
    ;;
  esac
  sst_am_finish_has_been_called=x
  readonly sst_am_finish_has_been_called

  case $# in
    0)
    ;;
    *)
      sst_barf 'invalid argument count: %d' $#
    ;;
  esac

  #---------------------------------------------------------------------

  if [[ "${sst_am_distribute_vars[@]+x}" ]]; then
    printf '%s' "${sst_am_distribute_vars[@]}" >>"$autogen_am_file"
  fi

  #---------------------------------------------------------------------

}; readonly -f sst_am_finish
