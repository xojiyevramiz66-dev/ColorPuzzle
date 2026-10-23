#!/usr/bin/env sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

if [ -n "$DEBUG" ]; then
    echo "DEBUG: $0"
fi

app_home=$(dirname "$(readlink -f "$0" 2>/dev/null || stat -f "$0" 2>/dev/null || echo "$0")")

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD to a smaller value
if [ -n "$MAX_FD" ]; then
  descriptor_limits=$(ulimit -H -n 2>/dev/null | awk '{print $1}')
  if [ -n "$descriptor_limits" ] && [ "$descriptor_limits" -ge "$MAX_FD" ]; then
    ulimit -n "$MAX_FD"
  fi
fi

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

escape_arg() {
  echo "$1" | sed "s/'/'\\\\''/g"
}

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the java executable
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation." >&2
        exit 1
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation." >&2
        exit 1
    }
fi

# Increase the maximum file descriptor limit if possible
if [ "$cygwin" = "false" ] && [ "$msys" = "false" ] && [ "$darwin" = "false" ] && [ "$nonstop" = "false" ]; then
    MAX_FD_LIMIT=$(ulimit -H -n 2>/dev/null)
    if [ $? -eq 0 ]; then
        if [ -n "$MAX_FD" ] && [ "$MAX_FD" -le "$MAX_FD_LIMIT" ]; then
            REST_MAX_FD=$MAX_FD
        else
            REST_MAX_FD=$MAX_FD_LIMIT
        fi
        ulimit -n "$REST_MAX_FD" 2>/dev/null || true
    fi
fi

# Escape all arguments
save="true"
index=0
for arg in "$@"
do
    case "$arg" in
      -D*=?*)
        eval 'arg_name=${arg%%=*}'
        eval 'arg_value=${arg#*=}'
        arg_value=$(escape_arg "$arg_value")
        eval "set -- \"\$@\" \"$arg_name=$arg_value\""
        shift
        ;;
      -D*=)
        eval 'arg_name=${arg%%=*}'
        eval 'set -- \"\$@\" \"$arg_name=\"'
        shift
        ;;
      *)
        eval "set -- \"\$@\" \"$(escape_arg "$arg")\""
        shift
        ;;
    esac
done

# Collect all arguments for the java command, following the shell quoting rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" -classpath "\"$app_home/gradle/wrapper/gradle-wrapper.jar\"" org.gradle.wrapper.GradleWrapperMain "$@"

exec "$JAVACMD" "$@"
