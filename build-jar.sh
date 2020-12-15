#!/bin/bash
set -eo pipefail

# A script to package lib core jar containing java interface and native libraries
# This script is path sensitive, copy both build-jar.sh and build-jar-linux.sh to
# a lib core project then `cd` to the folder, run with `bash build-jar.sh`
#
# Also, renaming this file may break the script
#
# Please find the built jar in <lib-core-folder>/../build-jar/target/scala/build-jar.jar

CURRENT_DIR=$PWD
COMMAND=$1
OPT_COMMAND=$2

DOCKER_NAME=lib-core-cmake
MACOS_BUILD_DIR=$CURRENT_DIR/../lib-ledger-core-build
LINUX_BUILD_DIR=$CURRENT_DIR/../lib-ledger-core-build-linux
JAR_BUILD_DIR=$CURRENT_DIR/../build-jar
LIBCORE_AWS_URL_BASE="https://s3-eu-west-1.amazonaws.com/ledger-lib-ledger-core"
LIBCORE_VERSION="3.5.0-rc-3d380f"
LIBCORE_DL_DIR=$CURRENT_DIR/../lib-ledger-core-dl
LIBCORE_SRC_DIR=$CURRENT_DIR/../lib-ledger-core

INTERFACE_GEN_DIR=tools/generate_interfaces.sh

function gen_interface()
{
  rm -v -rf $CURRENT_DIR/api
  cd $LIBCORE_SRC_DIR
  bash $INTERFACE_GEN_DIR
  cd -
}

function clean_src()
{
  rm -v -rf $CURRENT_DIR/core/lib/secp256k1/include/
  rm -v -rf $CURRENT_DIR/core/lib/secp256k1/lib/
  rm -v -rf $CURRENT_DIR/core/lib/secp256k1/tmp/
  rm -v -rf $CURRENT_DIR/core/lib/secp256k1/src
}

# Build dylib
function build_dylib()
{
  clean_src
  rm -rf -v $MACOS_BUILD_DIR
  mkdir -v $MACOS_BUILD_DIR
  cd $MACOS_BUILD_DIR
  cmake -DTARGET_JNI=ON $CURRENT_DIR
  make -j 8
  cd $CURRENT_DIR
}

# Build so
function build_so()
{
  clean_src
  rm -rf -v $LINUX_BUILD_DIR
  mkdir -v $LINUX_BUILD_DIR
  DOCKER_WORK_DIR=/workspace
  DOCKER_SRC_DIR=$DOCKER_WORK_DIR/lib-ledger-core
  DOCKER_BUILD_DIR=$DOCKER_WORK_DIR/lib-ledger-core-build
  DOCKER_BUILD_SCRIPT=$DOCKER_SRC_DIR/build-jar-linux.sh
  docker run -t -e LINUX_SRC_DIR=$DOCKER_SRC_DIR -e LINUX_BUILD_DIR=$DOCKER_BUILD_DIR --name $DOCKER_NAME \
    -v $LINUX_BUILD_DIR:$DOCKER_BUILD_DIR -v $CURRENT_DIR:$DOCKER_SRC_DIR rikorose/gcc-cmake \
    bash $DOCKER_BUILD_SCRIPT
}

# Grab a version of libcore.
function dl_libcore()
{
  local platform="$1"
  local input_file_name="$2"
  local output_file_name="$3"
  local url="$LIBCORE_AWS_URL_BASE/$LIBCORE_VERSION/$platform/$input_file_name"

  echo "Downloading libcore-$LIBCORE_VERSION for $platform"

  mkdir -p $LIBCORE_DL_DIR
  if ! curl \
    --fail \
    --max-time 600 \
    --output $LIBCORE_DL_DIR/$output_file_name \
    "$url"; then
    echo "Cannot download $url"
    exit 1
  fi
}

# Grab the dylib (macOSX) version of libcore from AWS S3.
function dl_dylib()
{
  dl_libcore "macos/jni" "libledger-core_jni.dylib" "libledger-core.dylib"
}

# Grab the so (linux) version of libcore from AWS S3.
function dl_so() {
  dl_libcore "linux/jni" "libledger-core_jni.so" "libledger-core.so"
}

# Build jar
function build_jar()
{
  gen_interface
  rm -v -rf $JAR_BUILD_DIR
  mkdir -v $JAR_BUILD_DIR
  JAVA_API_DIR=$LIBCORE_SRC_DIR/api/core/java
  SCALA_API_DIR=$LIBCORE_SRC_DIR/api/core/scala
  RESOURCE_DIR=$JAR_BUILD_DIR/src/main/resources/resources/djinni_native_libs

  mkdir -v -p $RESOURCE_DIR

  cp -v $JAVA_API_DIR/* $JAR_BUILD_DIR
  cp -v $SCALA_API_DIR/* $JAR_BUILD_DIR

  if [ "$COMMAND" = "mac" ]; then
    if [ "$OPT_COMMAND" == "dl" ]; then
      cp -v $LIBCORE_DL_DIR/libledger-core.dylib $RESOURCE_DIR
    else
      cp -v $MACOS_BUILD_DIR/core/src/libledger-core.dylib $RESOURCE_DIR
    fi
  elif [ "$COMMAND" = "linux" ]
  then
    if [ "$OPT_COMMAND" == "dl" ]; then
      cp -v $LIBCORE_DL_DIR/libledger-core.so $RESOURCE_DIR
    else
      docker cp $DOCKER_NAME:$DOCKER_BUILD_DIR/core/src/libledger-core.so $RESOURCE_DIR
    fi
  elif [ "$COMMAND" = "all" ]
  then
    if [ "$OPT_COMMAND" == "dl" ]; then
      cp -v $LIBCORE_DL_DIR/libledger-core.{so,dylib} $RESOURCE_DIR
    else
      cp -v $MACOS_BUILD_DIR/core/src/libledger-core.dylib $RESOURCE_DIR
      docker cp $DOCKER_NAME:$DOCKER_BUILD_DIR/core/src/libledger-core.so $RESOURCE_DIR
    fi
  fi

  cd $JAR_BUILD_DIR
  sbt package
  cd $CURRENT_DIR
}

# cleanup
function cleanup()
{
  clean_src

  if [ "$OPT_COMMAND" != "dl" ]; then
    docker rm -f $DOCKER_NAME
  fi
}

trap cleanup EXIT

if [ "$COMMAND" = "mac" ]; then
  if [ "$OPT_COMMAND" = "dl" ]; then
    dl_dylib
  else
    build_dylib
  fi

  build_jar
elif [ "$COMMAND" = "linux" ]
then
  if [ "$OPT_COMMAND" = "dl" ]; then
    dl_so
  else
    build_so
  fi

  build_jar
elif [ "$COMMAND" = "all" ]
then
  if [ "$OPT_COMMAND" = "dl" ]; then
    dl_dylib
    dl_so
  else
    build_dylib
    build_so
  fi

  build_jar
else
  echo "commands:"
  echo "  'mac': build jar with only dylib"
  echo "  'linux': build jar with only so"
  echo "  'all': build jar with so and dylib"
fi
