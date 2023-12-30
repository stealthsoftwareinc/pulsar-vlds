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

sst_ag_install_bash_library() {

  # Bash >=4.2: declare -g    sst_ag_install_bash_library_i

  declare    dstdir
  declare    target
  declare    srcdir
  declare    x
  declare    y

  srcdir=$1
  readonly srcdir

  dstdir=bash
  readonly dstdir

  : ${sst_ag_install_bash_library_i=0}
  ((++sst_ag_install_bash_library_i))

  target=sst_ag_install_bash_library_$sst_ag_install_bash_library_i
  readonly target

  x=$srcdir/sst.bash
  sst_ag_process_leaf $target $x y
  sst_am_append <<EOF
${target}dir = \$(pkgdatadir)/$dstdir
${target}_DATA = $srcdir/sst.bash
EOF

  for x in $srcdir/{include,scripts}/**/; do
    x=${x#$srcdir/}
    y=$x
    x=${x%/}
    x=${x//[!0-9A-Z_a-z]/_}
    sst_am_append <<EOF
${target}_${x}dir = \$(pkgdatadir)/$dstdir/$y
${target}_${x}_DATA =
EOF
  done

  for x in $srcdir/{include,scripts}/**/*.bash*; do
    sst_ag_process_leaf $target $x y
    x=${x#$srcdir/}
    x=${x%/*}
    x=${x//[!0-9A-Z_a-z]/_}
    sst_am_append <<EOF
${target}_${x}_DATA += $y
EOF
  done

}; readonly -f sst_ag_install_bash_library
