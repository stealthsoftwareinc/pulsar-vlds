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

sst_find_dist_archive() {

  declare    archive
  declare -r dir="$sst_root_tmpdir/$FUNCNAME.$BASHPID"
  declare    n
  declare    x
  declare    y

  if ((SST_DEBUG)); then
    if (($# != 0)); then
      sst_expect_argument_count $# 0
    fi
  fi

  for archive in *.tar*; do

    rm -f -r "$dir"
    mkdir -p "$dir"/x

    sst_pushd "$dir"/x
    sst_extract_archive "$sst_rundir/$archive"
    sst_popd

    # The archive should have extracted to exactly one directory.
    n=0
    for x in "$dir"/x/*; do
      if ((++n > 1)); then
        continue 2
      elif [[ ! -d "$x" ]]; then
        continue 2
      fi
    done

    # The directory name should end with our version number.
    sst_install_utility awk git sed
    y=$(sh build-aux/gatbps-gen-version.sh)
    if [[ "$x" != *"$y" ]]; then
      continue
    fi

    # We found it.
    printf '%s\n' "$archive"
    return

  done

  sst_barf "Distribution archive not found."

}; readonly -f sst_find_dist_archive
