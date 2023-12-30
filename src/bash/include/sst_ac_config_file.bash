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

sst_ac_config_file() {

  # Bash >=4.2: declare -g -A sst_ac_config_file_srcs
  # Bash >=4.2: declare -g -A sst_am_targets

  declare    dst
  declare    src
  declare    x

  for src; do

    sst_expect_source_path "$src"

    if [[ "${sst_ac_config_file_srcs[$src]+x}" ]]; then
      sst_warn "'$src' was redundantly processed."
      continue
    fi

    if [[ $src == *@(.im.in|.in|.im) ]]; then
      if [[ ! -f $src ]]; then
        sst_barf "Unable to process '$src' because it does not exist."
      fi
      dst=${src%%?(.im)?(.in)}
    elif [[ -f $src.im.in ]]; then
      dst=$src
      src=$src.im.in
    elif [[ -f $src.in ]]; then
      dst=$src
      src=$src.in
    elif [[ -f $src.im ]]; then
      dst=$src
      src=$src.im
    else
      sst_barf \
        "Unable to process '$src' because it does not have a" \
        ".im.in, .in, or .im extension, nor does adding such" \
        "an extension yield an existent file." \
      ;
    fi

    for x in $dst{,.im.in,.in,.im}; do
      if [[ $x != $src && -f $x ]]; then
        sst_barf "Unable to process '$src' because '$x' exists."
      fi
    done

    sst_ac_config_file_srcs[$src]=$dst

    if [[ "${sst_am_targets[$dst]+x}" ]]; then
      sst_barf \
        "Unable to produce '$dst' from '$src' because it has" \
        "already been produced from '${sst_am_targets[$dst]}'." \
      ;
    fi

    sst_am_targets[$dst]=$src

    if [[ $src == *.im.in ]]; then
      sst_ac_append <<<"GATBPS_CONFIG_FILE([$dst.im])"
      sst_ac_append <<<"GATBPS_CONFIG_LATER([$dst])"
    elif [[ $src == *.in ]]; then
      sst_ac_append <<<"GATBPS_CONFIG_FILE([$dst])"
    elif [[ $src == *.im ]]; then
      sst_ac_append <<<"GATBPS_CONFIG_LATER([$dst])"
    fi

  done

}; readonly -f sst_ac_config_file
