#!/system/bin/sh

echo "Night 前台监听测试脚本已执行"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "用户: $(id)"
echo "目录: $(pwd)"
echo "触发类型: ${KSU_TRIGGER_TYPE:-手动执行}"
echo "前台应用: ${KSU_TRIGGER_PACKAGE:-未知}"
