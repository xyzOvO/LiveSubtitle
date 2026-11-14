# 柔和工具风 — 风格参考

> 效率工具也可以温和：深青压住信息，柔色安抚手感。

**Theme:** light

柔和工具风服务于高频使用的移动端工具：待办、记账、健康打卡。它要在「效率」与「温和」之间取平衡——主色用深青（#2d5b63）保证按钮和正文的可读，其余颜色全部是低饱和的薄荷、杏、粉与淡蓝，只用于分类和状态。圆角取 12 到 16px，比消费级产品克制、比企业产品柔软。动效刻意不做弹跳，因为日常工具里任何夸张动作都会变成打断。

## Colors

| Name | Value | Token | Role |
|---|---|---|---|
| Deep Teal | `#2d5b63` | `--color-deep-teal` | 主行动、标题、进度条与选中态。是整套系统唯一的高对比色。 |
| Mist | `#f4f8f6` | `--color-mist` | 页面与卡片底色。带一点绿的暖白，比纯白柔和。 |
| Ink Teal | `#203b40` | `--color-ink-teal` | 正文与标题文字。比主色更深，保证长段落可读。 |
| Mint | `#a9d8cc` | `--color-mint` | 分类色与图标底，表示「生活 / 完成」类语义。 |
| Apricot | `#f3c98b` | `--color-apricot` | 提醒与截止语义，暖色里最显眼的一支。 |
| Blush | `#e6b7b0` | `--color-blush` | 情感与个人类分类，面积小、频率低。 |
| Pale Blue | `#b8c9ed` | `--color-pale-blue` | 信息与日程类分类，是唯一偏冷的柔色。 |

## Typography

### Inter — 全站唯一字体。字级偏移动端（13 到 17px 为主），字重不超过 600，避免压过柔色。

- **Substitute:** system-ui, -apple-system, 'PingFang SC', sans-serif
- **Weights:** 400, 500, 600
- **Sizes:** 12, 13, 15, 17, 22, 28
- **Line height:** 1.25 (display), 1.35 (headings), 1.6 (body)
- **Letter spacing:** -0.01em on display, 0.04em on uppercase labels

### Type Scale

| Role | Family | Weight | Size | Line Height | Letter Spacing | Token |
|---|---|---|---|---|---|---|
| heading | — | 600 | 28px | 1.3 | -0.01em | `--text-heading` |
| subheading | — | 600 | 22px | 1.35 | — | `--text-subheading` |
| body-lg | — | 600 | 17px | 1.5 | — | `--text-body-lg` |
| body | — | 400 | 15px | 1.6 | — | `--text-body` |
| body-sm | — | 400 | 13px | 1.55 | — | `--text-body-sm` |
| label | — | 500 | 12px | 1.4 | 0.04em | `--text-label` |

## Spacing & Layout

**Base unit:** 4px
**Density:** comfortable

### Spacing Scale

| Name | Value | Token |
|---|---|---|
| 4 | 4px | `--spacing-4` |
| 8 | 8px | `--spacing-8` |
| 12 | 12px | `--spacing-12` |
| 16 | 16px | `--spacing-16` |
| 20 | 20px | `--spacing-20` |
| 24 | 24px | `--spacing-24` |
| 32 | 32px | `--spacing-32` |
| 48 | 48px | `--spacing-48` |

### Border Radius

| Element | Value |
|---|---|
| cards | 16px |
| buttons | 16px |
| inputs | 16px |
| icons | 12px |
| pills | 999px |

- **Page max-width:** 480px
- **Section gap:** 32px
- **Card padding:** 20px
- **Element gap:** 12px

## Components

### Primary Action

**Role:** 主行动按钮（例：完成任务）

深青填充、白字、16px 圆角、无描边，字级 17px、字重 600、padding 12px 20px，阴影 0 6px 16px 主色 20%。hover 时填充加深到 #234b52；按下缩放 0.98。过渡只改颜色，200ms。

- **Sizes:** padding 12px 20px; label 17px
- **States:** hover: darker fill; active: scale 0.98

### Task Card

**Role:** 任务卡与进度块

雾绿白填充、1px 主色 15% 描边、16px 圆角、内边距 20px，无阴影。左上 40px 薄荷图标块（12px 圆角），标题 17px/600 墨青，说明 12px 灰。底部进度条高 8px、全圆角、底槽 #d9e8e3、填充深青。

### Soft Input

**Role:** 添加任务与搜索

白底、1px 主色 20% 描边、16px 圆角、padding 12px 16px、15px 字。占位符用灰 60%。聚焦时加 4px 的薄荷 50% 外环，不改变描边宽度。

- **States:** focus: 4px mint ring

### Category Chip

**Role:** 分类与筛选标记

全圆角、padding 6px 12px、12px/500。四个分类各用一支柔色作 20% 底配主色文字：薄荷=生活、杏=截止、藕粉=个人、淡蓝=日程。选中态底色升到 100% 并保持深色文字。

### Bottom Sheet

**Role:** 移动端底部弹层

白底、顶部 20px 圆角、顶部 1px 主色 10% 描边、阴影 0 -12px 40px 墨青 12%。顶部中央放一个 36px × 4px 的灰色抓手。内边距 20px，内容区可滚动，最大高度 88vh。

## Do's and Don'ts

### Do

- 主色深青，只用它承担行动与正文
- 圆角 12 到 16px
- 柔色只用于分类与状态
- 字重不超过 600
- 动效 200ms，只改颜色
- 移动端单栏，上限 480px

### Don't

- 不要用弹跳或夸张动效
- 不要让柔色承担正文
- 不要用超过 20px 的圆角
- 不要用重阴影
- 不要在同一屏用超过三种分类色
- 不要在移动端上多栏

## Surfaces

| Level | Name | Value | Purpose |
|---|---|---|---|
| 0 | Mist | `#f4f8f6` | 页面与卡片底 |
| 1 | White | `#ffffff` | 输入框与浮层 |
| 2 | Mint Bed | `#a9d8cc` | 图标与分类块 |

## Elevation

- **Button:** `0 6px 16px rgba(45,91,99,0.20)`
- **Card:** `none — 1px border carries it`
- **Sheet:** `0 -12px 40px rgba(32,59,64,0.12)`

## Imagery

以图标与少量插画为主。允许柔色几何图形作为分类标识。不使用摄影与背景图，移动工具里图像会挤掉信息。

## Layout

移动优先：单栏、上限 480px、内边距 20px、卡片间距 12px。桌面端居中为一列，宽度不超过 560px。底部固定操作条高 64px，内容区留出同等底部内边距。

## Agent Prompt Guide

### Implementation Contract

- 以用户请求中的页面、用户任务和真实内容为依据，只实施指定范围；标明缺失的业务信息，不编造数据或能力。
- 先检查现有项目、技术栈与组件；沿用项目结构、路由和依赖，复用已有组件，不重建未要求的页面或功能。
- 沿用项目断点，调整窄屏与桌面的网格和留白；保留信息层级，避免意外横向溢出。
- 对本次涉及的交互，处理键盘焦点与减少动态效果；补齐适用的禁用、加载、空内容与错误状态。
- 完成后按项目现有脚本检查，仅报告实际运行的测试与视觉检查，并列出未验证项。

QUICK COLOR REFERENCE:

- text: `#203b40`
- background: `#f4f8f6`
- primary: `#2d5b63`
- mint: `#a9d8cc`
- apricot: `#f3c98b`
- muted: `#557077`

EXAMPLE COMPONENT PROMPTS:

1. 做一个柔和工具风任务卡：背景 #f4f8f6、1px rgba(45,91,99,0.15) 描边、16px 圆角、内边距 20px。左上 40px 见方的 #a9d8cc 图标块（12px 圆角），标题 17px/600 #203b40，说明 12px #557077。底部进度条高 8px、全圆角、底槽 #d9e8e3、填充 #2d5b63、宽度 66%。页面底色 #f4f8f6。

## Similar Brands

- **Soft UI** — 同样柔软亲和，但 Soft UI 更装饰化、圆角更大
- **Notion Style** — 同为工具型，但 Notion 是文档而这里是移动效率

## Quick Start

```css

:root {
  /* Colors */
  --color-deep-teal: #2d5b63;
  --color-mist: #f4f8f6;
  --color-ink-teal: #203b40;
  --color-mint: #a9d8cc;
  --color-apricot: #f3c98b;
  --color-blush: #e6b7b0;
  --color-pale-blue: #b8c9ed;

  /* Typography — Font Families */
  --font-sans: 'Inter', ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;

  /* Typography — Scale */
  --text-heading: 28px;
  --leading-heading: 1.3;
  --tracking-heading: -0.01em;
  --text-subheading: 22px;
  --leading-subheading: 1.35;
  --text-body-lg: 17px;
  --leading-body-lg: 1.5;
  --text-body: 15px;
  --leading-body: 1.6;
  --text-body-sm: 13px;
  --leading-body-sm: 1.55;
  --text-label: 12px;
  --leading-label: 1.4;
  --tracking-label: 0.04em;

  /* Typography — Weights */
  --font-weight-regular: 400;
  --font-weight-medium: 500;
  --font-weight-semibold: 600;

  /* Spacing */
  --spacing-unit: 4px;
  --spacing-4: 4px;
  --spacing-8: 8px;
  --spacing-12: 12px;
  --spacing-16: 16px;
  --spacing-20: 20px;
  --spacing-24: 24px;
  --spacing-32: 32px;
  --spacing-48: 48px;

  /* Layout */
  --page-max-width: 480px;
  --section-gap: 32px;
  --card-padding: 20px;
  --element-gap: 12px;
  --density: comfortable;

  /* Border Radius */
  --radius-cards: 16px;
  --radius-buttons: 16px;
  --radius-inputs: 16px;
  --radius-icons: 12px;
  --radius-pills: 999px;

  /* Surfaces */
  --surface-mist: #f4f8f6;
  --surface-white: #ffffff;
  --surface-mint-bed: #a9d8cc;
}

```
