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

ARG BASE_IMAGE=alpine

FROM $BASE_IMAGE AS builder

RUN apk --no-cache add \
  alpine-sdk \
  autoconf \
  automake \
  file \
  g++ \
  gawk \
  gcc \
  git \
  jq \
  libtool \
  lzip \
  make \
  mariadb-dev \
  openjdk8 \
  rpm \
  tar \
  texinfo \
  xz \
;

COPY sst.tar.gz /x/

# TODO: The first run of ./configure should be using --prefix=/usr for
#       consistency, but a bug in SST 0.39.0 (via a bug in GATBPS) makes
#       this break the Netty detection. Using --prefix=/usr/local works
#       around the bug for now. This should be fixed in SST 0.40.0.

RUN : \
  && cd /x \
  && tar xzf sst.tar.gz \
  && mv sst-*/ sst \
  && cd sst \
  && CPPFLAGS= \
  && CPPFLAGS="$CPPFLAGS -I/usr/lib/jvm/java-1.8-openjdk/include" \
  && CPPFLAGS="$CPPFLAGS -I/usr/lib/jvm/java-1.8-openjdk/include/linux" \
  && ./configure \
    --build=x86_64-unknown-linux \
    --disable-invalid-argument-checks \
    --disable-static \
    --disable-undefined-behavior-checks \
    --host=x86_64-unknown-linux \
    --prefix=/usr/local \
    CPPFLAGS="$CPPFLAGS" \
    JAR=/usr/lib/jvm/java-1.8-openjdk/bin/jar \
    JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
    JDEPS=/usr/lib/jvm/java-1.8-openjdk/bin/jdeps \
  && make install-java-jardeps \
  && make DESTDIR=/x/overlay install-java-jardeps \
  && ./configure \
    --build=x86_64-unknown-linux \
    --disable-invalid-argument-checks \
    --disable-static \
    --disable-undefined-behavior-checks \
    --host=x86_64-unknown-linux \
    --prefix=/usr \
    --with-netty \
    CPPFLAGS="$CPPFLAGS" \
    JAR=/usr/lib/jvm/java-1.8-openjdk/bin/jar \
    JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
    JDEPS=/usr/lib/jvm/java-1.8-openjdk/bin/jdeps \
  && make \
  && make java \
  && make install \
  && make install-java \
  && make DESTDIR=/x/overlay install \
  && make DESTDIR=/x/overlay install-java \
;

COPY pulsar-vlds.tar.gz /x/
RUN : \
  && mkdir /x/pulsar-vlds \
  && cd /x/pulsar-vlds \
  && tar xzf ../pulsar-vlds.tar.gz \
  && cd * \
  && ./configure \
       --build=x86_64-unknown-linux \
       --disable-invalid-argument-checks \
       --disable-static \
       --disable-undefined-behavior-checks \
       --host=x86_64-unknown-linux \
       --prefix=/usr \
       CPPFLAGS="-I/usr/lib/jvm/java-1.8-openjdk/include -I/usr/lib/jvm/java-1.8-openjdk/include/linux" \
       JAR=/usr/lib/jvm/java-1.8-openjdk/bin/jar \
       JAVAC=/usr/lib/jvm/java-1.8-openjdk/bin/javac \
       JDEPS=/usr/lib/jvm/java-1.8-openjdk/bin/jdeps \
  && make install-java-jardeps \
  && make DESTDIR=/x/overlay install-java-jardeps \
  && make all java \
  && make install install-java \
  && make DESTDIR=/x/overlay install install-java \
;

FROM $BASE_IMAGE

RUN apk --no-cache add \
  mariadb-client \
  openjdk8-jre \
;

COPY --from=builder /x/overlay /
