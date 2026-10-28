#!/bin/bash
# V3 验证全量运行。用法:bash run.sh
cd "$(dirname "$0")"

mvn -q compile || exit 1
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/classes;$(cat cp.txt)"
J="java -Dstdout.encoding=UTF-8 -cp"

run() {
  echo "════════════════════════════════════════════════════════════"
  echo " $1"
  echo "════════════════════════════════════════════════════════════"
  $J "$CP" "$2" 2>&1
  echo
}

run "1/5 组件 + slot + 布局 + 循环 + 自动转义" app.V3Probe
run "2/5 JbContext 编排:@section/@yield/@stack/@push(含深层组件贡献)" app.V3Ctx
run "3/5 跨 partial 开闭 HTML 标签(应失败 —— jte 要求 HTML 良构)" app.V3Frag
run "4/5 模板内 Java 类型错误的行号映射质量" app.V3Err
run "5/5 热重载(改模板文件后是否自动重编译)" app.V3Hot
