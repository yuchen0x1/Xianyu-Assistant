# 项目规则

## 本地路径与编辑规则

- 本项目根目录固定为 `C:\Users\lenmo\Desktop\Xianyu Assistant`。工具调用使用该目录下的绝对路径，不要再次拼接 `Desktop\Xianyu Assistant`。
- 前端请求封装位于 `vue-code/src/utils/request.ts`，不存在 `vue-code/src/api/request.ts`。
- 读取或编辑文件前先用 `rg --files` 或 `find` 确认真实路径；不要根据类名、包名或旧会话记录猜测文件位置。
- 每次 `Edit` 前必须重新读取目标文件的当前内容。替换文本不唯一或不存在时，先重新定位上下文，不要重复提交同一编辑。
- 大文件按行号分段读取，搜索结果先收窄范围，避免一次读取完整日志或大源码文件。
