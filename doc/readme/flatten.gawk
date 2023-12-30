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
# This script flattens an Asciidoctor file destined for a distribution
# archive or a distribution repository, as renderers such as GitHub or
# GitLab typically have limited or no support for certain Asciidoctor
# features such as the include:: directive. The following steps are
# taken:
#
#    1. All :in_source_repo: lines are removed.
#
#    2. All ifdef::in_source_repo[] blocks are removed.
#
#    3. All ifndef::in_source_repo[] lines and their corresponding
#       endif::[] lines are removed.
#
#    4. All include:: directives are expanded recursively.
#
# You can run this script on an Asciidoctor file with the following
# variables set appropriately with gawk -v:
#
#       script_file
#             A path to this script file itself.
#
#       input_dir
#             A path to the directory that contains the Asciidoctor
#             file being used as input.
#
# For example, the following command will flatten README.adoc, writing
# the result to standard output:
#
#       gawk -v script_file=doc/readme/flatten.gawk \
#            -v input_dir=. -f doc/readme/flatten.gawk README.adoc
#
# It is required that both gawk and bash are installed.
#

function sh_escape(x) {
  gsub(/'/, "'\\''", x);
  return x;
}

function sh_quote(x) {
  return "'" sh_escape(x) "'";
}

function dot_slash(x) {
  if (x ~ /^[^.\/]/) {
    x = "./" x;
  }
  return x;
}

BEGIN {
  script_file = sh_escape(sh_quote(script_file));
  if_depth = 0;
  ifdef_index = 0;
  ifndef_stack = "";
}

{
  if ($0 ~ /^if(n?def|eval)::/) {
    ++if_depth;
  } else if ($0 ~ /^endif::/) {
    --if_depth;
  }

  if (ifdef_index != 0) {
    if (if_depth < ifdef_index) {
      ifdef_index = 0;
    }
  } else if ($0 ~ /^:in_source_repo:/) {
  } else if ($0 ~ /^include::/) {
    path = $0;
    sub(/^include::/, "", path);
    sub(/\[\]$/, "", path);

    d = path;
    if (!sub(/\/[^\/]*$/, "", d)) {
      d = ".";
    }
    d = input_dir "/" d;
    d = sh_escape(sh_quote(dot_slash(d)));

    f = path;
    sub(/^.*\//, "", f);
    f = sh_escape(sh_quote(dot_slash(f)));

    s = system("bash -c 'set -e -o pipefail || exit; " \
               "(cd " d "; cat " f ") | " \
               "  gawk -v script_file=" script_file \
               "       -v input_dir=" d " -f " script_file "'");
    if (s != 0) {
      exit s;
    }
  } else if ($0 ~ /^ifdef::in_source_repo\[/) {
    ifdef_index = if_depth;
  } else if ($0 ~ /^ifndef::in_source_repo\[/) {
    ifndef_stack = if_depth "," ifndef_stack;
  } else if ($0 !~ /^endif::/) {
    print $0;
  } else if (if_depth < 0) {
    print "unmatched endif directive" >"/dev/stderr";
    if_depth = 0;
    exit 1;
  } else {
    top = ifndef_stack;
    sub(/,.*/, "", top);
    if (if_depth < top) {
      sub(/[^,]*,/, "", ifndef_stack);
    } else {
      print $0;
    }
  }
}

END {
  if (if_depth != 0) {
    print "unmatched if* directive" >"/dev/stderr";
    exit 1;
  }
}
