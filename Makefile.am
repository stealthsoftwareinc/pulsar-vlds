##
## Copyright (C) 2018-2023 Stealth Software Technologies, Inc.
##
## Permission is hereby granted, free of charge, to any person
## obtaining a copy of this software and associated documentation
## files (the "Software"), to deal in the Software without
## restriction, including without limitation the rights to use,
## copy, modify, merge, publish, distribute, sublicense, and/or
## sell copies of the Software, and to permit persons to whom the
## Software is furnished to do so, subject to the following
## conditions:
##
## The above copyright notice and this permission notice (including
## the next paragraph) shall be included in all copies or
## substantial portions of the Software.
##
## THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
## EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
## OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
## NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
## HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
## WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
## FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
## OTHER DEALINGS IN THE SOFTWARE.
##
## SPDX-License-Identifier: MIT
##

##----------------------------------------------------------------------
## Add a copyright notice to Makefile.in and Makefile
##----------------------------------------------------------------------

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

##----------------------------------------------------------------------
## Include gatbps.am
##----------------------------------------------------------------------

include $(srcdir)/build-aux/gatbps.am

##----------------------------------------------------------------------

## begin_variables

ACLOCAL_AMFLAGS = -I m4

AM_CPPFLAGS = \
  -I include \
  -I $(srcdir)/include \
$(prevent_a_blank_line)

BUILT_SOURCES =

DISTCLEANFILES =

GATBPS_JAVADOCFLAGS =
GATBPS_JAVADOCFLAGS += \
  '-tag' 'param' \
  '-tag' 'return' \
  '-tag' 'throws' \
  '-tag' 'accesses:a:Accesses:' \
  '-tag' 'modifies:a:Modifies:' \
  '-tag' 'wellbehaved:a:Well-Behaved:' \
  '-tag' 'available:a:Available:' \
  '-tag' 'see' \
  '-tag' 'since' \
  '-tag' 'inheritancenotes:a:Inheritance Notes:' \
  '-tag' 'examples:a:Examples:' \
  '-tag' 'notablechanges:a:Notable Changes:' \
$(prevent_a_blank_line)

MAINTAINERCLEANFILES =

MOSTLYCLEANFILES =
MOSTLYCLEANFILES += $(java_src)

TESTS =

TEXI2DVI = \
  'texi2dvi' \
  $(AM_TEXI2DVIFLAGS) \
  $(TEXI2DVIFLAGS) \
$(prevent_a_blank_line)

TMPEXT = .tmp

XFAIL_TESTS =

bin_PROGRAMS =

bin_SCRIPTS =

check_PROGRAMS =

dist_java_DATA =
dist_java_DATA += $(jar_classpath_files)

dist_pkgdata_DATA =

pkgdata_cmd_includedir = $(pkgdatadir)/cmd/include
dist_pkgdata_cmd_include_DATA =
dist_pkgdata_cmd_include_DATA += src/cmd/include/sst_find_java.cmd
dist_pkgdata_cmd_include_DATA += src/cmd/include/sst_find_java_home.cmd

dist_pkgsharedstate_DATA =

jar_classpath_files =

java_dist_dst = lib/pulsar-vlds-$(PACKAGE_VERSION).jar

javadoc_JAVADOCFLAGS =
javadoc_JAVADOCFLAGS += $(GATBPS_JAVADOCFLAGS)
javadoc_JAVADOCFLAGS += '-windowtitle' '$(PACKAGE_STRING) Javadoc Reference'

javadoc_dist_dep =
javadoc_dist_dep += $(javadoc_src)

javadoc_dist_dst =
javadoc_dist_dst += doc/pulsar-vlds-$(PACKAGE_VERSION)-javadoc

javadoc_dist_src =
javadoc_dist_src += $(javadoc_dst)

javadoc_dst =
javadoc_dst += doc/pulsar-vlds-javadoc

lib_LTLIBRARIES =

libexec_PROGRAMS =

libexec_SCRIPTS =

nodist_noinst_HEADERS =

nodist_pkginclude_HEADERS =

noinst_HEADERS =

noinst_LIBRARIES =

noinst_LTLIBRARIES =

noinst_PROGRAMS =

pkgdata_DATA =

pkginclude_HEADERS =

pkglibexec_PROGRAMS =

pkglibexec_SCRIPTS =

pkgsharedstatedir = $(sharedstatedir)/$(PACKAGE_TARNAME)

pkgsysconfdir = $(sysconfdir)/$(PACKAGE_TARNAME)

## end_variables

artifacts_dist_archive_src = $(distdir).tar.gz
artifacts/$(distdir).tar.gz/src: FORCE
	@echo artifacts_dist_archive_src

## begin_rules

all-local: java

install-data-local: install-java

uninstall-local: uninstall-java

## end_rules

include $(srcdir)/src/docker/pulsar-vlds/am

include $(srcdir)/build-aux/GATBPS_CONFIG_FILE_RULES.am
include $(srcdir)/build-aux/GATBPS_CONFIG_LATER_RULES.am
include $(srcdir)/build-aux/GATBPS_CP_RULES.am
include $(srcdir)/build-aux/GATBPS_GIT_ARCHIVE_RULES.am
include $(srcdir)/build-aux/GATBPS_GIT_BUNDLE_RULES.am
include $(srcdir)/build-aux/GATBPS_GIT_CLONE_RULES.am
include $(srcdir)/build-aux/GATBPS_VPATH_SEARCH.am
include $(srcdir)/build-aux/GATBPS_V_PAD.am
include $(srcdir)/build-aux/GATBPS_WGET_RULES.am
include $(srcdir)/build-aux/cp.am
include $(srcdir)/build-aux/docker.am
include $(srcdir)/build-aux/dvi-dist.am
include $(srcdir)/build-aux/git.am
include $(srcdir)/build-aux/html-dist.am
include $(srcdir)/build-aux/java-dist.am
include $(srcdir)/build-aux/javadoc-dist.am
include $(srcdir)/build-aux/javadoc.am
include $(srcdir)/build-aux/ps-dist.am
include $(srcdir)/build-aux/sst.am
include $(srcdir)/build-aux/wget.am

include $(srcdir)/build-aux/autogen.am
