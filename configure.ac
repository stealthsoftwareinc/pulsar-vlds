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

[

]AC_INIT(
  [[PULSAR-VLDS]],
  m4_esyscmd_s([
    sh build-aux/gatbps-gen-version.sh
  ])m4_if(m4_sysval, 0, [], [
    m4_fatal([gatbps-gen-version.sh failed.])
  ]),
  [[pulsar-vlds@stealthsoftwareinc.com]],
  [[pulsar-vlds]],
  [[]])[

]
dnl|--------------------------------------------------------------------
dnl| Add a copyright notice to the configure script
dnl|--------------------------------------------------------------------
[

]AC_COPYRIGHT([[

Copyright (C) 2018-2023 Stealth Software Technologies, Inc.

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or
sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice (including
the next paragraph) shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.

SPDX-License-Identifier: MIT

]])[

]
dnl---------------------------------------------------------------------
[

]m4_include([build-aux/gatbps.ac])[

]gatbps_check_macros[ || 'exit' "${?}"

]GATBPS_DEFINE_VERSION(
  [AC_],
  [PACKAGE_VERSION])[

]GATBPS_DEFINE_DATE(
  [AC_],
  [PACKAGE_DATE],
  m4_esyscmd_s([
    sh build-aux/gatbps-gen-date.sh
  ])m4_if(m4_sysval, 0, [], [
    m4_fatal([gatbps-gen-date.sh failed])
  ]))[

]AC_CONFIG_AUX_DIR([build-aux])[
]AC_CONFIG_MACRO_DIR([m4])[
]AC_CONFIG_MACRO_DIRS([m4])[
]AC_CONFIG_SRCDIR([build-aux/pulsar-vlds.srcdir])[

]AM_INIT_AUTOMAKE([
  -Wall
  foreign
  nostdinc
  subdir-objects
  tar-ustar
])[

]DEFINE_ALL[

]LT_INIT([win32-dll])[

]dnl begin_includes
[

]m4_include([build-aux/autogen.ac])[
]m4_include([src/docker/pulsar-vlds/ac])[

]dnl end_includes

dnl---------------------------------------------------------------------
dnl Program checks
dnl---------------------------------------------------------------------

GATBPS_PROG_ASCIIDOCTOR
GATBPS_PROG_AWK
GATBPS_PROG_DOCKER
GATBPS_PROG_GIT
GATBPS_PROG_GREP
GATBPS_PROG_JAVA
GATBPS_PROG_JAVAC
GATBPS_PROG_JDEPS
GATBPS_PROG_LN_S
GATBPS_PROG_MKDIR_P
GATBPS_PROG_OPENSSL
GATBPS_PROG_SED
GATBPS_PROG_UNZIP
GATBPS_PROG_WGET
GATBPS_PROG_ZIP

dnl---------------------------------------------------------------------

GATBPS_CP([artifacts/$(distdir).tar.gz], [$(artifacts_dist_archive_src)])

[

]GATBPS_CONFIG_LATER_VAR([pkgsharedstatedir])[

]

GATBPS_CONFIG_FILE_SUBST
GATBPS_CONFIG_LATER_SUBST
GATBPS_CP_SUBST
GATBPS_GIT_ARCHIVE_SUBST
GATBPS_GIT_BUNDLE_SUBST
GATBPS_GIT_CLONE_SUBST
GATBPS_WGET_SUBST

AC_CONFIG_FILES([Makefile])
AC_CONFIG_HEADERS([config.h])

AC_OUTPUT[

]
