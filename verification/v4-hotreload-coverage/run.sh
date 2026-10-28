#!/bin/bash
# V4 验证全量运行。用法:bash run.sh
# 需要网络(首次克隆样本仓库);已克隆则直接复用。
cd "$(dirname "$0")"

SP=${SP:-/tmp/sp}
HALO=${HALO:-/tmp/halo}

echo "=== 1/4 准备样本仓库 ==="
[ -d "$SP" ]   || git clone --depth 200 --quiet https://github.com/spring-projects/spring-petclinic.git "$SP"
[ -d "$HALO" ] || git clone --depth 300 --quiet https://github.com/halo-dev/halo.git "$HALO"
echo "    petclinic: $(git -C "$SP" log --oneline | wc -l) commits"
echo "    halo     : $(git -C "$HALO" log --oneline | wc -l) commits"

echo "=== 2/4 编译工具 ==="
rm -rf out && javac -encoding UTF-8 -d out src/*.java || exit 1

echo "=== 3/4 分类器自测 ==="
java -Dstdout.encoding=UTF-8 -cp out ChangeClassifier --selftest x || exit 1

echo
echo "=== 4/4 扫描真实仓库 ==="
for spec in "$SP:200:spring-petclinic(官方示例应用)" "$HALO:300:halo(真实活跃产品)"; do
  repo="${spec%%:*}"; rest="${spec#*:}"; n="${rest%%:*}"; label="${rest#*:}"
  echo
  echo "####################  $label  ####################"
  java -Dstdout.encoding=UTF-8 -cp out HistoryAnalyzer "$repo" "$n" --exclude-chores
done

echo
echo "结论见 RESULT.md"
