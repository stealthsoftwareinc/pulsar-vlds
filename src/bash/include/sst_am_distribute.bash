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
# Note that distributed files should generally never be conditional, as
# it does not make sense for the output of "make dist" to be dynamic. In
# other words, running "make dist" should always produce the same output
# regardless of how the configure script was run and regardless of what
# the configure script observed about the environment.
#
# sst_am_distribute handles this by storing the list of distributed
# files in memory, which is later written to the autogen.am file by
# sst_am_finish after all conditionals have ended. This means the files
# are distributed unconditionally, even if sst_am_distribute was called
# within an sst_am_if block.
#

sst_am_distribute() {

  # Bash >=4.2: declare -g    sst_am_distribute_i
  # Bash >=4.2: declare -g -A sst_am_distribute_seen
  # Bash >=4.2: declare -g -a sst_am_distribute_vars

  declare    i
  declare    n
  declare    path

  sst_expect_not_subshell

  # n should be hardcoded to an integer value in [1, k+1], where k is
  # the number from the highest numbered GATBPS_DISTFILES_k target in
  # GATBPS. Note that newer GATBPS versions will only ever increase k.
  n=100
  readonly n

  for path; do
    sst_expect_source_path "$path"
    if [[ ! -f $path && ! -d $path && -e $path ]]; then
      path=$(sst_quote "$path")
      sst_barf \
        "Path must either exist as a file," \
        "exist as a directory, or not exist: $path." \
      ;
    fi
    if [[ "${sst_am_distribute_seen["$path"]-}" ]]; then
      continue
    fi
    sst_am_distribute_seen["$path"]=1
    i=${sst_am_distribute_i-0}
    sst_am_distribute_i=$(((i + 1) % n))
    if [[ ! "${sst_am_distribute_vars[i]-}" ]]; then
      sst_am_distribute_vars[i]=
    fi
    sst_am_distribute_vars[i]+="GATBPS_DISTFILES_$i += $path"$'\n'
  done

}; readonly -f sst_am_distribute
