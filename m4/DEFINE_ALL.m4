dnl
dnl Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
dnl
dnl Permission is hereby granted, free of charge, to any person
dnl obtaining a copy of this software and associated documentation
dnl files (the "Software"), to deal in the Software without
dnl restriction, including without limitation the rights to use,
dnl copy, modify, merge, publish, distribute, sublicense, and/or
dnl sell copies of the Software, and to permit persons to whom the
dnl Software is furnished to do so, subject to the following
dnl conditions:
dnl
dnl The above copyright notice and this permission notice (including
dnl the next paragraph) shall be included in all copies or
dnl substantial portions of the Software.
dnl
dnl THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
dnl EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
dnl OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
dnl NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
dnl HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
dnl WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
dnl FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
dnl OTHER DEALINGS IN THE SOFTWARE.
dnl
dnl SPDX-License-Identifier: MIT
dnl

AC_DEFUN([DEFINE_ALL], [[{

#
# The block that contains this comment is the expansion of the
# DEFINE_ALL macro.
#]dnl
m4_ifdef(
  [DEFINE_ALL_HAS_BEEN_CALLED],
  [gatbps_fatal([
    DEFINE_ALL has already been called
  ])],
  [m4_define([DEFINE_ALL_HAS_BEEN_CALLED])])[]dnl
m4_if(
  m4_eval([$# != 0]),
  [1],
  [gatbps_fatal([
    DEFINE_ALL requires exactly 0 arguments
    ($# ]m4_if([$#], [1], [[was]], [[were]])[ given)
  ])])[]dnl
[

]dnl begin_prerequisites
[

]AC_REQUIRE([DEFINE_javadir])[
]AC_REQUIRE([DEFINE_javadocdir])[

]dnl end_prerequisites
[

]dnl begin_prerequisites
[

]AC_REQUIRE([DEFINE_AR])[
]AC_REQUIRE([DEFINE_CC])[
]AC_REQUIRE([DEFINE_CXX])[
]AC_REQUIRE([DEFINE_GZIP])[
]AC_REQUIRE([DEFINE_JAR])[
]AC_REQUIRE([DEFINE_JAVA])[
]AC_REQUIRE([DEFINE_JAVAC])[
]AC_REQUIRE([DEFINE_JAVADOC])[
]AC_REQUIRE([DEFINE_JDEPS])[
]AC_REQUIRE([DEFINE_MAKEINFO])[
]AC_REQUIRE([DEFINE_RANLIB])[
]AC_REQUIRE([DEFINE_TAR])[
]AC_REQUIRE([DEFINE_XZ])[

]dnl end_prerequisites
[

]dnl begin_prerequisites
[

]AC_REQUIRE([DEFINE_CLASSPATH])[
]AC_REQUIRE([DEFINE_JAVACFLAGS])[

]dnl end_prerequisites
[

]AC_REQUIRE([DEFINE_AT])[
]AC_REQUIRE([DEFINE_CLASSPATH_SEPARATOR])[
]AC_REQUIRE([DEFINE_EXEEXT])[
]AC_REQUIRE([DEFINE_PULSAR_VLDS_ENABLE_INTERNAL_ERROR_CHECKS])[
]AC_REQUIRE([DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS])[
]AC_REQUIRE([DEFINE_PULSAR_VLDS_ENABLE_UNDEFINED_BEHAVIOR_CHECKS])[

:;}]])[]dnl
