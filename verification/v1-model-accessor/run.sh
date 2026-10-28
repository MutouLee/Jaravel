#!/bin/bash
# V1 验证全量运行。用法:bash run.sh
cd "$(dirname "$0")"

JF="-encoding UTF-8"
rm -rf out negout

echo "=== 1/5 编译(运行时 + 三种方案) ==="
javac $JF -d out $(find src -name '*.java') || exit 1

echo
echo "=== 2/5 功能等价性与快照脏检查(期望 13 通过) ==="
java -Dstdout.encoding=UTF-8 -cp out V1Report || exit 1

echo
echo "=== 3/5 方案 E' 关联形态(期望 5 通过) ==="
java -Dstdout.encoding=UTF-8 -cp out V1Relations || exit 1

echo
echo "=== 4/5 类型安全负例(四项均应编译失败) ==="
FAIL=0
for n in N1 N2 N3 N4; do
  javac $JF -J-Duser.language=en -cp out -d negout negative/$n.java > negative/$n.log 2>&1
  if [ $? -eq 0 ]; then echo "  ✗ $n 竟然编译通过了"; FAIL=1
  else
    echo "  ✓ $n 编译失败(符合预期)"
    sed -n '1,3p' negative/$n.log | sed 's/^/      /'
  fi
done

echo
echo "=== 5/5 代码量统计 ==="
count() { awk '{l=$0; gsub(/^[ \t]+|[ \t]+$/,"",l); if(l=="")next; if(l ~ /^\/\//)next; if(l ~ /^\*/)next; if(l ~ /^\/\*/)next; n++} END{print n+0}' "$1"; }
printf "  %-44s %s\n" "用户手写文件" "有效行"
for f in src/main/java/approach_c/UserC.java src/main/java/approach_a/UserA.java \
         src/main/java/approach_e/UserE.java src/main/java/approach_e/UserE2.java; do
  printf "  %-44s %6s\n" "$(basename $f)" "$(count $f)"
done

echo
echo "  规模放大对比(5 / 15 / 30 字段):"
if [ -d scale/java ]; then
  L=$(count scale/laravel/User.php)
  printf "  %-8s %8s %8s %8s %10s\n" "字段数" "方案C" "方案A" "方案E" "Laravel"
  for n in 5 15 30; do
    printf "  %-8s %8s %8s %8s %10s\n" "$n" "$(count scale/java/UC$n.java)" \
           "$(count scale/java/UA$n.java)" "$(count scale/java/UE$n.java)" "$L"
  done
fi

echo
echo "结论见 RESULT.md"
exit $FAIL
