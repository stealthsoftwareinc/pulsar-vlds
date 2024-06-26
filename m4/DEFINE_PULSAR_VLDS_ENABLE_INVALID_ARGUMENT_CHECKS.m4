dnl
dnl This file is from the PULSAR-VLDS package.
dnl
dnl The following copyright notice is generally applicable:
dnl
dnl      Copyright (C) Stealth Software Technologies, Inc.
dnl
dnl The full copyright information depends on the distribution
dnl of the package. For more information, see the COPYING file.
dnl However, depending on the context in which you are viewing
dnl this file, the COPYING file may not be available.
dnl
AC_DEFUN([DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS], [[{

#
# The block that contains this comment is the expansion of the
# DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS macro.
#]dnl
m4_ifdef(
  [DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS_HAS_BEEN_CALLED],
  [gatbps_fatal([
    DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS has already been called
  ])],
  [m4_define([DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS_HAS_BEEN_CALLED])])[]dnl
m4_if(
  m4_eval([$# != 0]),
  [1],
  [gatbps_fatal([
    DEFINE_PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS requires exactly 0
    arguments
    ($# ]m4_if([$#], [1], [[was]], [[were]])[ given)
  ])])[]dnl
[

]GATBPS_ARG_ENABLE_BOOL(
  [
    permission to enable invalid argument checks
  ],
  [PULSAR_VLDS_ENABLE_INVALID_ARGUMENT_CHECKS],
  [invalid-argument-checks],
  [yes],
  [
    enable invalid argument checks
  ],
  [
    disable invalid argument checks
  ])[

:;}]])[]dnl
