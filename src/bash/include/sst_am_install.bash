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

sst_am_install() {

  # Bash >=4.2: declare -g -A sst_am_install_dirs

  declare    base
  declare    dir
  declare    dir_slug
  declare    dst
  declare    dst_a
  declare    dst_b
  declare    primary
  declare    src
  declare    suf
  declare    x
  declare    y

  if ((SST_DEBUG)); then
    sst_expect_not_subshell
    if (($# < 3)); then
      sst_expect_argument_count $# 3-
    fi
  fi

  dst=$1
  readonly dst

  dst_a=${dst%%/*}
  readonly dst_a

  if ((SST_DEBUG)); then
    sst_expect_source_path "$dst"
    x='@(bin'
    x+='|data'
    x+='|dataroot'
    x+='|include'
    x+='|libexec'
    x+=')'
    if [[ $dst_a != $x ]]; then
      sst_barf "dst='$dst' must begin with one of $x."
    fi
  fi

  dst_b=${dst#$dst_a}
  readonly dst_b

  primary=$2
  if [[ "$primary" == - ]]; then
    case $dst_a in bin | libexec)
      primary=SCRIPTS
    ;; include)
      primary=HEADERS
    ;; *)
      primary=DATA
    esac
  fi
  readonly primary

  if ((SST_DEBUG)); then
    x='@(DATA'
    x+='|HEADERS'
    x+='|JAVA'
    x+='|LIBRARIES'
    x+='|LISP'
    x+='|LTLIBRARIES'
    x+='|MANS'
    x+='|PROGRAMS'
    x+='|PYTHON'
    x+='|SCRIPTS'
    x+='|TEXINFOS'
    x+=')'
    if [[ "$primary" != $x ]]; then
      y=$(sst_quote "$primary")
      sst_barf "primary=$y must be one of $x."
    fi
  fi

  base=$3
  readonly base

  if ((SST_DEBUG)); then
    if [[ "$base" != - ]]; then
      sst_expect_source_path "$base"
    fi
  fi

  shift 3
  while (($# > 0)); do

    src=$1
    shift

    if ((SST_DEBUG)); then
      sst_expect_source_path "$src"
    fi

    if [[ -d $src ]]; then
      set -- $src/* "$@"
      continue
    fi

    if ((SST_DEBUG)); then
      if [[ $base != - && $src != $base/* ]]; then
        sst_barf "src='$src' must be below base='$base'."
      fi
    fi

    if [[ -f $src ]]; then
      # TODO: What to do about asdf here?
      sst_ag_process_leaf asdf $src src
    fi

    suf=
    if [[ $base != - ]]; then
      suf=${src#$base}
      suf=${suf%/*}
    fi

    dir=$dst$suf
    dir_slug=${dir//[!0-9A-Z_a-z]/_}

    if [[ ! "${sst_am_install_dirs[$dir]+x}" ]]; then
      if [[ "$dst_b" || "$suf" ]]; then
        sst_am_suspend_if
        sst_am_append <<EOF
${dir_slug}dir = \$(${dst_a}dir)$dst_b$suf
GATBPS_UNINSTALL_MKDIRS += \$(${dir_slug}dir)/mkdir
EOF
        sst_am_restore_if
      fi
      sst_am_install_dirs[$dir]=
    fi

    sst_am_var_add ${dir_slug}_$primary $src

  done

}; readonly -f sst_am_install
