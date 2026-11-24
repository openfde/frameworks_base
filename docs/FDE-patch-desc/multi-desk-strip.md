# 主屏虚拟桌面条（desk strip）：窗口拖到桌面缩略图

> 项目：OpenFDE Android（AOSP 17 fork，工作区根 D:\android17）
> 涉及仓库：`base`（frameworks/base，wm-shell / SystemUI 进程）
> 范围：**只做主屏（display 0）**；跨屏桌面迁移、副屏桌面条、跨屏拖拽暂不实现。
> 对应原型：`virt6.html`（双显示器 · 虚拟桌面管理）中"底部工具条 + 桌面缩略图 + 把窗口拖到缩略图"的部分。

---

## 1. 需求与范围

### 1.1 本版实现

- 主屏底部（FDE dock 上方）常显一条**桌面条**，每个虚拟桌面（desk）一个缩略图：
  - 缩略图内按窗口 bounds 等比画出 mini 矩形（最小化窗口半透明）；
  - 高亮当前 active 桌面；
  - 第一张桌面标记为"主"，不参与拖拽/迁移；
  - 末尾一个 `+` 新建桌面。
- 点击缩略图切桌（复用 `switchDeskWithAnimation`，同屏动画）。
- 点击 `+` 新建并激活桌面（`DesksController.createDesk(activateDesk = true)`）。
- **把窗口标题栏拖到任意桌面缩略图上，窗口移动到该桌面，但当前桌面不切换**（原型语义）。
  - 拖拽过程中目标缩略图高亮；
  - 松手后显示"已移动到 桌面N"toast；
  - 拖到窗口当前所属桌面 = 取消拖拽并弹回原位。

### 1.2 本版不做（已知限制）

| 项 | 说明 |
| --- | --- |
| 整桌跨屏迁移 | 仅断屏流程里有 `moveDeskToDisplay`，本版不暴露 |
| 副屏桌面条 | `DeskStripController` 目前只挂 `DEFAULT_DISPLAY` |
| 跨屏拖拽 | 依赖 DisplayTopology / topology-aware 输入，未启用 |
| 桌面重排 / 重命名 / 删除 | 未做；`removeDesk` 会关闭桌内窗口，语义较重 |
| 真像素缩略图 | 先用几何矩形；`mirrorSurface` 需原型验证 alpha=0/hidden/secure 三坑 |
| 空桌/顺序持久化 | 沿用 AOSP 现状：空桌不落盘，重启后编号可能重排 |
| 桌面数量上限 | FDE overlay 里 `config_maxDesktopWindowingDesks=0`（不限）；+ 按钮受 `canCreateDesks` 约束 |
| 桌面条宽度 | 桌面很多时条宽可能超出屏幕，本版不做横向滚动，先截断 |
| 沉浸式全屏 | 全屏/沉浸应用会让 desk 失活、条自动隐藏；但 desk 内请求 immersive 的窗口场景可能仍显示条，待实机确认 |

---

## 2. 复用的既有能力

| 能力 | 位置 |
| --- | --- |
| 每 display 一套桌 + active desk | `DesktopRepository`（`MultiDesktopData`） |
| 桌 = FREEFORM root task + 隐藏 minimization root | `RootTaskDesksOrganizer` |
| 建桌 / 激活 / 切桌动画 | `DesksController.createDesk`、`DesktopTasksController.switchDeskWithAnimation` |
| 窗口标题栏拖拽（同屏） | `DesktopModeTouchEventListener` + `MultiDisplayVeiledResizeTaskPositioner` |
| 只移窗、不激活目标桌 | `desksOrganizer.moveTaskToDesk` + `DeskTransition.AddTaskToDesk`（restore 流程已有先例） |
| 每屏 overlay | display context 的 `WindowManager.addView`（SystemUI 常规做法，`TYPE_APPLICATION_OVERLAY`） |

关键结论：**切桌、建桌、移窗的底层全部现成**，本补丁只补三件事：
1. 一个面向 UI 的桌快照 API；
2. 一条"不激活目标桌"的移窗路径；
3. 桌面条 UI + 拖拽落点接线。

---

## 3. 实现结构

```
DesktopTasksController
 ├─ onDragPositioningMove ──► DeskStripController.onWindowDragMove(displayId, x, y)  // 高亮
 ├─ onDragPositioningEnd  ──► DeskStripController.onWindowDragEnd(...)               // 命中判定
 │                              └─► moveTaskToDeskWithoutActivation(task, deskId, bounds)
 └─ onInit ────────────────► DeskStripController.onInit(controller)                   // 建窗/监听

DeskStripController
 ├─ 每屏一个 StripWindow（当前只 display 0）：SystemWindows + SurfaceControlViewHost
 ├─ 数据：DesktopRepository.getDeskSummaries(displayId) + ShellTaskOrganizer 的 RunningTaskInfo
 ├─ 监听：DeskChangeListener / DesktopTaskInfoChanged / task vanished / overview / keyguard / user
 ├─ 点击：switchDeskWithAnimation / DesksController.createDesk
 └─ 绘制：DeskStripView（单 View Canvas 绘制缩略图、主角标、+、hover/drag 高亮）
```

### 3.1 新增 API：`DesktopRepository.getDeskSummaries(displayId)`

```kotlin
data class DeskSummary(
    val deskId: Int,
    val position: Int,          // 0-based，用于 "桌面N"
    val isActive: Boolean,
    val taskIds: List<Int>,     // z-order，top → bottom，已剔除 closing
    val minimizedTaskIds: Set<Int>,
)
```

只返回数据，不包含 bounds；bounds 由 `DeskStripController` 从
`shellTaskOrganizer.getRunningTaskInfo(taskId).configuration.windowConfiguration.bounds`
读取（避免 repository 依赖 organizer）。

### 3.2 新增 API：`DesktopTasksController.moveTaskToDeskWithoutActivation`

```kotlin
fun moveTaskToDeskWithoutActivation(
    task: RunningTaskInfo,
    deskId: Int,
    taskBounds: Rect,
): Boolean
```

流程：
1. 校验 target desk 存在、task 不在该桌、task 是 FREEFORM；
2. `wct`：`desksOrganizer.moveTaskToDesk(wct, deskId, task)` + `wct.setBounds(task.token, taskBounds)`；
3. `transitions.startTransition(TRANSIT_CHANGE, wct, null)`；
4. 注册 `DeskTransition.AddTaskToDesk`，由 `DesksTransitionObserver` 更新
   `DesktopRepository`（跨桌清理 active/visible/z-order），**不触碰 activeDeskId**；
5. 返回是否发起成功。

`taskBounds` 传拖拽前的 bounds，保证窗口回到原位置而不是停在桌面条上。

### 3.3 拖拽落点接线

- `onDragPositioningMove(displayId, inputX, inputY)`：
  `DeskStripController.onWindowDragMove` 把缩略图命中结果写入 `DeskStripView.dragHoveredDeskId`。
- `onDragPositioningEnd(...)` 最前面（在 `updateIndicatorType` 之前）：
  - 命中缩略图且 **不是** 窗口当前桌 → `moveTaskToDeskWithoutActivation`，`releaseVisualIndicator()`，toast；
  - 命中缩略图且 **是** 当前桌 → `returnToDragStartAnimator.start(...)` 弹回，视为取消；
  - 未命中 → 走原有拖拽落点逻辑（最大化/贴边/普通 move），行为不变。
- 桌面条窗口是普通 overlay，拖拽期间输入流仍由 caption 窗口持有（AOSP 的 sink region 机制），
  桌面条不需要接收拖拽事件，只负责画高亮。

### 3.4 桌面条窗口

- 用 display context 的 `createWindowContext(TYPE_APPLICATION_OVERLAY)` 拿到窗口 context，再
  `WindowManager.addView()` 添加一个 `TYPE_APPLICATION_OVERLAY` 窗口
  （SystemUI 已持有 `SYSTEM_ALERT_WINDOW` / `INTERNAL_SYSTEM_WINDOW`），内容尺寸 `WRAP_CONTENT`：
  - `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_HARDWARE_ACCELERATED`、关闭 fit insets；
  - `gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL`、`y = -desk_strip_bottom_margin`
    （默认 96dp，`res/values/fde_dimens.xml`），即 FDE dock 上方；
- 可见性：有 active 桌 + 非 overview + 非 keyguard + 该屏支持 desktop mode；
  全屏/沉浸应用会退出 desktop（active desk 为空）时自动隐藏；
- 输入：View 自行处理点击/hover；拖拽高亮由 controller 直接设置，不依赖触摸事件。

### 3.5 数据刷新

- `DeskChangeListener`：桌增删、active 变化、canCreate 变化、task 出现在桌；
- `DesksOrganizer.addOnDesktopTaskInfoChangedListener` / `addOnDesktopTaskVanishedListener`：
  窗口移动/缩放/最小化/关闭；
- 以上统一 `handler.postDelayed(..., 80ms)` 去抖后 `refresh()`；
- 旋转/分辨率变化通过 `DisplayLayout` 在每次 refresh 读取，窗口随之重新 measure；
- 用户切换时重新注册 repository listener。

---

## 4. 文件清单（相对 base）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/data/DeskSummary.kt` | 新增 | 桌快照数据类 |
| `.../desktopmode/data/DesktopRepository.kt` | 修改 | `getDeskSummaries(displayId)` |
| `.../desktopmode/DesktopTasksController.kt` | 修改 | 新构造参数、`onInit` 接线、拖拽 hook、`moveTaskToDeskWithoutActivation` |
| `.../desktopmode/DeskStripController.kt` | 新增 | 桌面条窗口、数据刷新、点击/建桌、拖拽命中/toast |
| `.../desktopmode/DeskStripView.kt` | 新增 | 桌面条绘制与点击处理 |
| `.../dagger/WMShellBaseModule.java` | 修改 | `@BindsOptionalOf DeskStripController` |
| `.../dagger/WMShellModule.java` | 修改 | `provideDesktopTasksController` 增加 `Optional<DeskStripController>` |
| `res/values/fde_strings.xml` + `values-zh-rCN/-rHK/-rTW` | 修改 | 桌面名、主角标、a11y、移动 toast |
| `res/values/fde_dimens.xml` | 新增 | `desk_strip_bottom_margin` |
| `tests/unittest/.../data/DesktopRepositoryTest.kt` | 修改 | `getDeskSummaries` 单测 |

---

## 5. 调试开关

```bash
# 关闭桌面条（改后重启 SystemUI）
adb shell setprop persist.fde.desk_strip.enable 0

# 只关"拖窗口到桌面"的落点接管，保留桌面条
adb shell setprop persist.fde.desk_strip.drag_to_desk 0

# 恢复
adb shell setprop persist.fde.desk_strip.enable 1
adb shell setprop persist.fde.desk_strip.drag_to_desk 1
```

日志 Tag：`DeskStripController`（通过 `DesktopTasksController` 的 log 也能看到
`moveTaskToDeskWithoutActivation`）。

---

## 6. 验证清单

单测：
- `DesktopRepositoryTest#getDeskSummaries_returnsOrderedDesksWithTasksAndActiveState`

真机（主屏 + 双屏环境均可，只操作主屏）：
1. 默认有一个 desk：桌面条显示 1 个缩略图 + `+`，主桌有"主"角标；
2. `+` 新建：出现第 2 个缩略图并自动切过去，toast "桌面2"；
3. 点击缩略图切桌：desk 切换动画正常，active 高亮跟随；
4. 打开两个窗口，拖一个窗口标题栏到另一桌面缩略图：目标缩略图高亮，松手后窗口消失、
   缩略图出现 mini 矩形、toast "已移动到 桌面N"，**当前桌面不变**；
5. 拖窗口到当前桌面缩略图：窗口弹回原位，无移动；
6. 拖窗口到桌面条以外区域：行为与改动前一致（最大化/贴边/普通移动）；
7. 拖窗口到缩略图后切到目标桌面：窗口在目标桌、位置为拖拽前的位置；
8. 最小化窗口：缩略图对应 mini 矩形变半透明；
9. 打开全屏/沉浸应用、overview、锁屏：桌面条隐藏，退出后恢复；
10. 旋转屏幕/改分辨率：桌面条重新居中，点击/拖拽命中仍准确；
11. `persist.fde.desk_strip.enable=0` 重启 SystemUI：桌面条不出现，拖拽行为恢复原样。

已知需要实机确认的点：
- `TYPE_APPLICATION_OVERLAY` 在 FDE dock 上方的实际层级（`y=-96dp` 是否够高）；
- 桌面条窗口与任务标题栏拖拽的输入竞争（拖拽流应始终由 caption 窗口持有）；
- 拖拽落点与窗口 surface 的 z-order（桌面条在窗口之上，拖拽 ghost 可能被条遮住）。

---

## 7. 后续（不在本版）

1. 副屏桌面条 + 整桌跨屏迁移（`DesksOrganizer.moveDeskToDisplay` + `ChangeDeskDisplay` 转场，
   参考 display 断连流程）；
2. 跨屏拖拽（需要 DisplayTopology 生效 + topology-aware 窗口/原生 DnD）；
3. 桌面顺序/空桌持久化（proto 加 order、空桌也落盘、恢复按 order 排序）；
4. 真缩略图（`mirrorSurface`）与"缩到缩略图"动画；
5. 桌面重排/重命名/删除、设置项、无障碍虚拟子节点。
