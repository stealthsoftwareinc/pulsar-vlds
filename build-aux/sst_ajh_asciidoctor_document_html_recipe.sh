#! /bin/sh -
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

eval ASCIIDOCTOR_FLAGS='${ASCIIDOCTOR_FLAGS?}'
eval MAKE='${MAKE:?}'
eval SED='${SED:?}'
eval TSUF=${TSUF:?}
eval dst=${dst:?}
eval imagesdir=${imagesdir:?}
eval prefix=${prefix:?}
eval slug=${slug:?}
eval srcdir=${srcdir:?}

readonly ASCIIDOCTOR_FLAGS
readonly MAKE
readonly SED
readonly TSUF
readonly dst
readonly imagesdir
readonly prefix
readonly slug
readonly srcdir

x=
x="${x?} -a imagesdir=${imagesdir?}"
x="${x?} ${ASCIIDOCTOR_FLAGS?}"
eval " ${MAKE?}"' \
  ${slug?}_disable_wrapper_recipe=/x \
  ASCIIDOCTOR_FLAGS="${x?}" \
  ${dst?} \
' || exit $?

#---------------------------------------------------------------
# KaTeX installation
#---------------------------------------------------------------

if test -d ${prefix?}katex; then

  mv -f ${dst?} ${dst?}${TSUF?}1 || exit $?

  x='
    /<script.*[Mm]ath[Jj]ax.*\.js/ d
  '
  eval " ${SED?}"' \
    "${x?}" \
    <${dst?}${TSUF?}1 \
    >${dst?}${TSUF?}2 \
  ' || exit $?

  mv -f ${dst?}${TSUF?}2 ${dst?} || exit $?

fi

#---------------------------------------------------------------
# Fonts installation
#---------------------------------------------------------------

if test -d ${prefix?}fonts; then

  mv -f ${dst?} ${dst?}${TSUF?}1 || exit $?

  x='
    /<link.*fonts\.googleapis\.com/ d
  '
  eval " ${SED?}"' \
    "${x?}" \
    <${dst?}${TSUF?}1 \
    >${dst?}${TSUF?}2 \
  ' || exit $?

  mv -f ${dst?}${TSUF?}2 ${dst?} || exit $?

fi

#---------------------------------------------------------------
