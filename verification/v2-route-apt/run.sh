#!/bin/bash
# V2 验证全量运行。用法:bash run.sh
cd "$(dirname "$0")"

JFLAGS="-encoding UTF-8 -J-Duser.language=en -J-Dstdout.encoding=UTF-8 -J-Dstderr.encoding=UTF-8"
SEP=";"   # Windows classpath separator

rm -rf build && mkdir -p build

echo "=== 1/4 编译 api ==="
javac $JFLAGS -d build/api $(find api -name '*.java') || exit 1

echo "=== 2/4 编译 processor ==="
javac $JFLAGS -cp build/api -d build/processor $(find processor -name '*.java') || exit 1
cp -r processor/META-INF build/processor/

echo "=== 3/4 正例:APT 解析 + 校验(期望 exit=0) ==="
javac $JFLAGS -cp build/api -processorpath build/processor -d build/sample \
      $(find sample -name '*.java') > build/pos.log 2>&1
POS=$?
cat build/pos.log
echo "    → javac exit=$POS  $([ $POS -eq 0 ] && echo '✓ 符合预期' || echo '✗ 不符合预期')"
echo

echo "=== 4/4 负例 ==="
declare -A EXPECT=( [n1]=1 [n2]=1 [n3]=1 [n4]=1 [n5]=0 )
declare -A DESC=(
  [n1]="resource() 缺失方法"
  [n2]="路径参数名与方法参数名不符"
  [n3]="路由重复定义"
  [n4]="路由名重复"
  [n5]="动态 URI(应优雅降级,不报错)"
)
FAIL=0
for n in n1 n2 n3 n4 n5; do
  javac $JFLAGS -cp "build/api${SEP}build/sample" -processorpath build/processor \
        -d build/neg_$n negative/$n/*.java > build/$n.log 2>&1
  GOT=$?
  OK=$([ "$GOT" -eq "${EXPECT[$n]}" ] && echo "✓" || { FAIL=1; echo "✗"; })
  echo "──────── $n  ${DESC[$n]}  (期望 exit=${EXPECT[$n]}, 实际 $GOT) $OK"
  grep -v "RouteProcessor 已启动" build/$n.log | sed 's/^/    /'
  echo
done

echo "════════════════════════════════════════════════"
[ $FAIL -eq 0 ] && [ $POS -eq 0 ] && echo " V2 全部用例符合预期 ✓" || echo " V2 有用例不符合预期 ✗"
echo "════════════════════════════════════════════════"
