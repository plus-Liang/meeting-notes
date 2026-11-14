# scripts/ —— 工程化脚本

- `test.ps1`：统一测试入口（T1.9）。裸 shell 直接跑即注入 `.env` 与 ASR 冒烟地址；
  `-UnitOnly` 跳过两个 `*SmokeTest`。详见文件头注释。
