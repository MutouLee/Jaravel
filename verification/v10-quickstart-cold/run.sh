#!/usr/bin/env bash
# 冷环境照 docs-site/docs/quickstart.md 跑一遍,并给每一步计时。
#
# 这个脚本**在容器里**跑(见 run-docker.ps1):空 ~/.m2、空工作目录、
# 只有 JDK 21 + Maven 3.9 + git + curl —— 也就是 quickstart §0 写的前置。
# 目的是给 V1.0-RESULT §2 第 1 条("外部开发者照文档 30 分钟跑通")
# 一个可复算的数字,而不是"内部手工走通"。
#
# 它刻意不做的事:不读仓库里的任何脚本来"走捷径",每一步都只敲
# quickstart 里写的那条命令;浏览器打开的那几步换成等价的 curl 断言
# (打开首页 = GET / 返回 200 且含预期文案)。
#
# 输出:
#   out/steps.tsv    每步 名称/秒/退出码
#   out/<step>.log   每步 stdout+stderr
#   out/summary.txt  两个总计 + 检查表
set -uo pipefail

ROOT=${ROOT:-/work}
OUT=$ROOT/out
REPO=${REPO:-https://github.com/MutouLee/Jaravel.git}
EXAMPLE_PORT=8000
APP_PORT=8010
JARAVEL_VERSION=0.1.0-SNAPSHOT

rm -rf "$OUT"; mkdir -p "$OUT"
: > "$OUT/steps.tsv"
cd "$ROOT"

fail=0

# 跑一步并计时。失败不立刻退出 —— 后面的步骤即使会连带失败,
# 它们的失败形态本身就是这次验收要报告的内容。
step() {
    local name=$1; shift
    local start end rc
    echo "==> $name"
    start=$(date +%s)
    ( "$@" ) >"$OUT/$name.log" 2>&1
    rc=$?
    end=$(date +%s)
    printf '%s\t%s\t%s\n' "$name" "$((end - start))" "$rc" >> "$OUT/steps.tsv"
    if [ "$rc" -ne 0 ]; then
        fail=1
        echo "    FAILED rc=$rc (见 out/$name.log 末尾)"
        tail -n 15 "$OUT/$name.log" | sed 's/^/    | /'
    else
        echo "    ok $((end - start))s"
    fi
    return 0
}

secs() { awk -F'\t' -v n="$1" '$1==n {print $2}' "$OUT/steps.tsv"; }

# ---------------------------------------------------------------- 应用进程

APP_PID=
start_app() { # start_app <dir> <port> <logname> [extra mvn args...]
    local dir=$1 port=$2 logname=$3; shift 3
    ( cd "$dir" && SERVER_PORT="$port" mvn -q spring-boot:run "$@" ) \
        >"$OUT/$logname.applog" 2>&1 &
    APP_PID=$!
}

wait_http() { # wait_http <url> <seconds>
    local url=$1 limit=$2 waited=0
    while [ "$waited" -lt "$limit" ]; do
        if curl -fsS -o /dev/null "$url" 2>/dev/null; then return 0; fi
        if ! kill -0 "$APP_PID" 2>/dev/null; then
            echo "应用进程已退出,没等到 $url"; return 1
        fi
        sleep 2; waited=$((waited + 2))
    done
    echo "等 ${limit}s 仍未就绪:$url"; return 1
}

stop_app() {
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
    pkill -f 'spring-boot:run' 2>/dev/null
    pkill -f 'spring-boot.run' 2>/dev/null
    sleep 3
    pkill -9 -f 'spring-boot' 2>/dev/null
    APP_PID=
    return 0
}

token_of() { grep -o 'name="_token" value="[^"]*"' "$1" | head -1 | sed 's/.*value="//; s/"$//'; }

# ---------------------------------------------------------------- 1. clone

step 01-clone git clone --depth 1 "$REPO" Jaravel

# -------------------------------------------- 2. 从源码装框架(quickstart §1)

step 02-install-framework \
    bash -c "cd $ROOT/Jaravel && mvn -q -B -DskipTests install"

# ------------------------------------------- 3. 跑现成 todo 示例(§1 后半)

run_example() {
    cd "$ROOT/Jaravel"
    start_app "$ROOT/Jaravel" "$EXAMPLE_PORT" 03-example -pl examples/todo
    wait_http "http://localhost:$EXAMPLE_PORT/" 180 || { stop_app; return 1; }
    curl -fsS "http://localhost:$EXAMPLE_PORT/" -o "$OUT/03-example.home.html" || { stop_app; return 1; }
    grep -qi 'todo\|待办' "$OUT/03-example.home.html" || {
        echo "首页内容不像 todo 示例:"; head -c 400 "$OUT/03-example.home.html"; stop_app; return 1; }
    stop_app
}
step 03-run-example run_example

# ------------------------------------------- 4. 安装 CLI(installation.md §使用 CLI)

build_cli() {
    cd "$ROOT/Jaravel"
    mvn -q -B -pl jaravel-cli -am package -DskipTests || return 1
    mvn -q -B -pl jaravel-cli dependency:build-classpath \
        -Dmdep.outputFile="$OUT/cli-cp.txt" -DincludeScope=runtime || return 1
    cat > /usr/local/bin/jaravel <<EOF
#!/bin/sh
exec java -cp "$ROOT/Jaravel/jaravel-cli/target/jaravel-cli-$JARAVEL_VERSION.jar:\$(cat $OUT/cli-cp.txt)" \\
    io.jaravel.cli.Jaravel "\$@"
EOF
    chmod +x /usr/local/bin/jaravel
    jaravel --version
}
step 04-install-cli build_cli

# ---------------------------------------------- 5. jaravel new(quickstart §2)

step 05-new bash -c "cd $ROOT && jaravel new myapp --preset=web --jaravel-version=$JARAVEL_VERSION"

run_new_project() {
    start_app "$ROOT/myapp" "$APP_PORT" 06-newapp
    wait_http "http://localhost:$APP_PORT/" 180 || { stop_app; return 1; }
    curl -fsS "http://localhost:$APP_PORT/" -o "$OUT/06-newapp.home.html" || { stop_app; return 1; }
    grep -q '已启动' "$OUT/06-newapp.home.html" || {
        echo "首页不是生成的 home.jblade.html:"; head -c 400 "$OUT/06-newapp.home.html"; stop_app; return 1; }
    stop_app
}
step 06-run-new run_new_project

# ------------------------------------------ 6. install:auth(quickstart §3)

step 07-install-auth bash -c "cd $ROOT/myapp && jaravel install:auth"

# 注册 → 自动登录进 /dashboard → 登出 → 重新登录 → 忘记密码,全部用
# cookie jar + 表单里的 _token 走,和浏览器里点的是同一条路。
auth_flow() {
    local base="http://localhost:$APP_PORT"
    local jar="$OUT/cookies.txt" t
    rm -f "$jar"

    start_app "$ROOT/myapp" "$APP_PORT" 08-auth
    wait_http "$base/login" 240 || { stop_app; return 1; }

    curl -fsS -c "$jar" -b "$jar" "$base/register" -o "$OUT/08-auth.register.html" || { stop_app; return 1; }
    t=$(token_of "$OUT/08-auth.register.html")
    [ -n "$t" ] || { echo "注册页没有 _token"; stop_app; return 1; }

    curl -sS -i -c "$jar" -b "$jar" -X POST "$base/register" \
        --data-urlencode "_token=$t" \
        --data-urlencode 'name=Cold Runner' \
        --data-urlencode 'email=cold@example.com' \
        --data-urlencode 'password=secret-12345' \
        --data-urlencode 'password_confirmation=secret-12345' \
        -o "$OUT/08-auth.register.post" || { stop_app; return 1; }
    grep -qi '^HTTP/.* 30[0-9]' "$OUT/08-auth.register.post" || {
        echo "注册没有重定向:"; head -n 20 "$OUT/08-auth.register.post"; stop_app; return 1; }
    grep -qi '^location: .*dashboard' "$OUT/08-auth.register.post" || {
        echo "注册后没去 /dashboard:"; grep -i '^location' "$OUT/08-auth.register.post"; stop_app; return 1; }

    curl -fsS -c "$jar" -b "$jar" "$base/dashboard" -o "$OUT/08-auth.dashboard.html" || {
        echo "注册后拿不到 /dashboard"; stop_app; return 1; }
    grep -q 'cold@example.com' "$OUT/08-auth.dashboard.html" || {
        echo "/dashboard 不含注册邮箱"; stop_app; return 1; }

    t=$(token_of "$OUT/08-auth.dashboard.html")
    curl -sS -i -c "$jar" -b "$jar" -X POST "$base/logout" \
        --data-urlencode "_token=$t" -o "$OUT/08-auth.logout.post" || { stop_app; return 1; }
    grep -qi '^location: .*login' "$OUT/08-auth.logout.post" || {
        echo "登出没回 /login:"; grep -i '^location' "$OUT/08-auth.logout.post"; stop_app; return 1; }

    curl -fsS -o /dev/null -w '%{http_code}' -c "$jar" -b "$jar" "$base/dashboard" \
        > "$OUT/08-auth.dashboard-after-logout.code" 2>/dev/null
    # 登出后 /dashboard 应该不再是 200 的控制台页(auth 中间件把它弹回登录)
    curl -sS -c "$jar" -b "$jar" "$base/dashboard" -o "$OUT/08-auth.guest-dashboard.html"
    grep -q 'cold@example.com' "$OUT/08-auth.guest-dashboard.html" && {
        echo "登出后仍能看到控制台"; stop_app; return 1; }

    curl -fsS -c "$jar" -b "$jar" "$base/login" -o "$OUT/08-auth.login.html" || { stop_app; return 1; }
    t=$(token_of "$OUT/08-auth.login.html")
    curl -sS -i -c "$jar" -b "$jar" -X POST "$base/login" \
        --data-urlencode "_token=$t" \
        --data-urlencode 'email=cold@example.com' \
        --data-urlencode 'password=secret-12345' \
        -o "$OUT/08-auth.login.post" || { stop_app; return 1; }
    grep -qi '^location: .*dashboard' "$OUT/08-auth.login.post" || {
        echo "重新登录没去 /dashboard:"; grep -i '^location' "$OUT/08-auth.login.post"; stop_app; return 1; }

    curl -fsS -c "$jar" -b "$jar" "$base/forgot-password" -o "$OUT/08-auth.forgot.html" || { stop_app; return 1; }
    t=$(token_of "$OUT/08-auth.forgot.html")
    curl -sS -i -c "$jar" -b "$jar" -X POST "$base/forgot-password" \
        --data-urlencode "_token=$t" \
        --data-urlencode 'email=cold@example.com' \
        -o "$OUT/08-auth.forgot.post" || { stop_app; return 1; }
    sleep 3
    grep -o 'reset-password?token=[A-Za-z0-9_-]\{8,\}' "$OUT/08-auth.applog" \
        | head -1 > "$OUT/08-auth.reset-link.txt"
    [ -s "$OUT/08-auth.reset-link.txt" ] || {
        echo "日志里找不到重置链接(quickstart §3 第 5 步)"
        grep -in 'reset\|重置' "$OUT/08-auth.applog" | tail -n 10
        stop_app; return 1; }

    stop_app
}
step 08-auth-flow auth_flow

# ------------------------------------- 7. 第一个模型 + 迁移(quickstart §4)

step 09-make-model bash -c "cd $ROOT/myapp && jaravel make:model Post -mc"
step 10-migrate     bash -c "cd $ROOT/myapp && jaravel migrate"

# ---------------------------------------------- 8. mvn test(quickstart §5)

step 11-test bash -c "cd $ROOT/myapp && mvn -B test"

# -------------------------------------- 9. package + java -jar(§6 检查表)

step 12-package bash -c "cd $ROOT/myapp && mvn -q -B -DskipTests package"

run_jar() {
    local jar
    jar=$(ls "$ROOT"/myapp/target/myapp-*.jar 2>/dev/null | grep -v original | head -1)
    [ -n "$jar" ] || { echo "没找到可运行 jar"; ls -l "$ROOT/myapp/target"; return 1; }
    ( cd "$ROOT/myapp" && SERVER_PORT="$APP_PORT" java -jar "$jar" ) >"$OUT/13-jar.applog" 2>&1 &
    APP_PID=$!
    wait_http "http://localhost:$APP_PORT/" 120 || { stop_app; return 1; }
    curl -fsS -o /dev/null "http://localhost:$APP_PORT/" || { stop_app; return 1; }
    kill "$APP_PID" 2>/dev/null; sleep 2; kill -9 "$APP_PID" 2>/dev/null
    APP_PID=
    return 0
}
step 13-run-jar run_jar

# ---------------------------------------------------------------- 汇总

total_of() { # total_of <step names...>
    local sum=0 n
    for n in "$@"; do sum=$((sum + $(secs "$n"))); done
    echo "$sum"
}

ALL_STEPS=$(cut -f1 "$OUT/steps.tsv" | tr '\n' ' ')
# "发布后"的那条路:外部开发者从 Central 取 artifact,不需要 clone 框架、
# 不需要 02 的整仓构建、也不需要 03/04 这两步(CLI 是发布页下载的二进制)。
USER_STEPS="05-new 06-run-new 07-install-auth 08-auth-flow 09-make-model 10-migrate 11-test 12-package 13-run-jar"

{
    echo "# 冷环境 quickstart 计时"
    echo
    echo "repo: $REPO"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "maven: $(mvn -v 2>&1 | head -1)"
    echo "cpus: $(nproc)  mem: $(awk '/MemTotal/{printf "%.1fGB", $2/1048576}' /proc/meminfo)"
    echo
    printf '%-20s %8s %5s\n' 步骤 秒 退出码
    while IFS=$'\t' read -r n s r; do printf '%-20s %8s %5s\n' "$n" "$s" "$r"; done < "$OUT/steps.tsv"
    echo
    echo "从源码(今天的现实,Central 未发布):$(total_of $ALL_STEPS)s"
    echo "仅用户步骤(发布后的路径):$(total_of $USER_STEPS)s"
    echo
    echo "失败步骤:$(awk -F'\t' '$3!=0 {printf "%s ", $1}' "$OUT/steps.tsv")"
    echo "整体:$([ "$fail" -eq 0 ] && echo 全绿 || echo 有失败)"
} > "$OUT/summary.txt"

cat "$OUT/summary.txt"
exit "$fail"
