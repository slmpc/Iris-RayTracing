#!/bin/sh

#
# Copyright © 2015 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

##############################################################################
#
#   由 Gradle 生成的 POSIX 平台 gradlew 启动脚本。
#
#   运行须知：
#
#   (1) 此脚本需要符合 POSIX 标准的 shell。如果 /bin/sh 不符合标准，
#       但系统中另有符合标准的 shell，例如 ksh 或 bash，
#       则可以在整个命令行前加上该 shell 的名称，
#       例如：
#
#           ksh gradlew
#
#       Busybox 等精简 shell 无法运行此脚本，
#       因为脚本需要以下全部 POSIX shell 功能：
#         * 函数；
#         * 展开表达式 «$var»、«${var}»、«${var:-default}»、«${var+SET}»、
#           «${var#prefix}»、«${var%suffix}» 和 «$( cmd )»；
#         * 具有可测试退出状态的复合命令，尤其是 «case»；
#         * 各种内置命令，包括 «command»、«set» 和 «ulimit»。
#
#   修改须知：
#
#   (2) 此脚本面向所有 POSIX shell，因此避免使用 Bash、Ksh 等提供的扩展，
#       尤其不使用数组。
#
#       将多个参数打包为空格分隔字符串的传统做法，容易引发已知的错误
#       和安全问题。因此，此脚本主要通过逐步将选项累积到 "$@" 中，
#       最后传递给 Java 的方式，
#       来避免这些问题。
#
#       如果继承的环境变量 DEFAULT_JVM_OPTS、JAVA_OPTS 和 GRADLE_OPTS
#       依赖单词拆分，则会显式执行拆分；
#       详情参见行内注释。
#
#       脚本针对 AIX、CygWin、Darwin、MinGW 和 NonStop 等
#       操作系统做了专门适配。
#
#   (3) 此脚本由 Gradle 项目中的以下 Groovy 模板生成：
#       https://github.com/gradle/gradle/blob/3d91ce3b8caaf77ad09f381f43615b715b53f72c/platforms/jvm/plugins-application/src/main/resources/org/gradle/api/internal/plugins/unixStartScript.txt
#       该模板位于 Gradle 项目内部。
#
#       Gradle 项目地址为 https://github.com/gradle/gradle/。
#
##############################################################################

# 尝试设置 APP_HOME

# 解析链接：$0 可能是符号链接
app_path=$0

# 此循环用于处理多层符号链接。
while
    APP_HOME=${app_path%"${app_path##*/}"}  # 保留末尾的 /；如果没有前导路径，则为空
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #(
      *)    app_path=$APP_HOME$link ;;
    esac
done

# 此变量通常不会使用
# shellcheck disable=SC2034
APP_BASE_NAME=${0##*/}
# 丢弃 cd 的标准输出，避免设置了 $CDPATH 时影响路径结果（https://github.com/gradle/gradle/issues/25036）
APP_HOME=$( cd -P "${APP_HOME:-./}" > /dev/null && printf '%s\n' "$PWD" ) || exit

# 使用可用的最大值；也可将 MAX_FD 设置为不等于 -1 的值以使用指定值。
MAX_FD=maximum

warn () {
    echo "$*"
} >&2

die () {
    echo
    echo "$*"
    echo
    exit 1
} >&2

# 操作系统专用支持标记，必须为 'true' 或 'false'。
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in                #(
  CYGWIN* )         cygwin=true  ;; #(
  Darwin* )         darwin=true  ;; #(
  MSYS* | MINGW* )  msys=true    ;; #(
  NONSTOP* )        nonstop=true ;;
esac



# 确定用于启动 JVM 的 Java 命令。
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # AIX 上的 IBM JDK 使用特殊的可执行文件路径
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1
    then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# 如果可以，则提高文件描述符数量上限。
if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
    case $MAX_FD in #(
      max*)
        # POSIX sh 未定义 ulimit -H 的行为，因此需检查执行结果以确认是否成功。
        # shellcheck disable=SC2039,SC3045
        MAX_FD=$( ulimit -H -n ) ||
            warn "Could not query maximum file descriptor limit"
    esac
    case $MAX_FD in  #(
      '' | soft) :;; #(
      *)
        # POSIX sh 未定义 ulimit -n 的行为，因此需检查执行结果以确认是否成功。
        # shellcheck disable=SC2039,SC3045
        ulimit -n "$MAX_FD" ||
            warn "Could not set maximum file descriptor limit to $MAX_FD"
    esac
fi

# 收集 java 命令的全部参数，按相反顺序累积：
#   * 命令行传入的参数
#   * 主类名称
#   * -classpath
#   * -D...appname 设置
#   * --module-path，仅在需要时使用
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 和 GRADLE_OPTS 环境变量。

# 对于 Cygwin 或 MSYS，在运行 java 前将路径转换为 Windows 格式
if "$cygwin" || "$msys" ; then
    APP_HOME=$( cygpath --path --mixed "$APP_HOME" )

    JAVACMD=$( cygpath --unix "$JAVACMD" )

    # 转换参数，使用兼容 /bin/sh 的方式实现
    for arg do
        if
            case $arg in                                #(
              -*)   false ;;                            # 不修改选项 #(
              /?*)  t=${arg#/} t=/${t%%/*}              # 看起来是 POSIX 文件路径
                    [ -e "$t" ] ;;                      #(
              *)    false ;;
            esac
        then
            arg=$( cygpath --path --ignore --mixed "$arg" )
        fi
        # 按参数数量循环轮转参数列表，
        # 使每个参数最终回到原来的位置，
        # 其内容可能已被修改。
        #
        # 注意：`for` 循环在开始前就确定了迭代列表，
        # 因此在此修改位置参数不会影响迭代次数，
        # 也不会影响 `arg` 接收到的值。
        shift                   # 移除原参数
        set -- "$@" "$arg"      # 追加替换后的参数
    done
fi


# 在此添加默认 JVM 选项，也可通过 JAVA_OPTS 和 GRADLE_OPTS 向此脚本传递 JVM 选项。
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# 收集 java 命令的全部参数：
#   * DEFAULT_JVM_OPTS、JAVA_OPTS 和 optsEnvironmentVar 不允许包含 shell 代码片段，
#     其中嵌入的 shell 语法均会被转义。
#   * 例如：不能期望环境变量中的 ${Hostname} 被展开，
#     它在命令行上会按字面值 '${Hostname}' 处理。

set -- \
        "-Dorg.gradle.appname=$APP_BASE_NAME" \
        -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
        "$@"

# 如果 "xargs" 不可用，则停止执行。
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

# 使用 "xargs" 解析带引号的参数。
#
# 使用 -n1 时，每行输出一个参数，并移除引号和反斜杠。
#
# 在 Bash 中可以直接使用：
#
#   readarray ARGS < <( xargs -n1 <<<"$var" ) &&
#   set -- "${ARGS[@]}" "$@"
#
# 但 POSIX shell 不支持数组和上述进程替换语法，因此改为逐个处理参数，
# 将每个参数作为一行输入交给 sed，为可能属于 shell 元字符的字符
# 添加反斜杠转义，然后使用 eval 撤销转义，
# 同时保持参数之间的分隔，
# 最后将整个过程组合为一条 "set" 语句。
#
# 如果任一变量包含换行符或不匹配的引号，
# 此处理方式会失败。
#

eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
