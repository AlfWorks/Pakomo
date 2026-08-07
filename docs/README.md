# Pakomo 文档

[English](README_EN.md) | 简体中文

文档按职责分区。能力的权威清单是 [能力矩阵](01-capabilities/capability-matrix.md)。

| 分区 | 主题 | 内容 |
|---|---|---|
| [00-overview](00-overview/) | 概览、现状与边界 | project-overview、principles-and-boundaries、current-status |
| [01-capabilities](01-capabilities/) | 能力清单 | capability-matrix、fault-models、limitations |
| [02-architecture](02-architecture/) | 实现方式 | common-architecture、kernel-backend、hev-backend、data-flow |
| [03-product](03-product/) | 界面呈现 | ui-specification、state-mapping |
| [05-security](05-security/) | 边界与威胁 | privacy-boundary、threat-model |
| [06-testing](06-testing/) | 验证方法 | acceptance-criteria、measurement-methodology |

其他文档：

- [automation-control.md](automation-control.md)：自动化控制接口，包含协议、命令、回读与安全。

## 状态标签

能力条目以状态标签标注实现状态。当前版本收录两类：`Implemented` 表示已实现的现状能力，`Out-of-Scope` 表示当前
版本明确不做的范围。
