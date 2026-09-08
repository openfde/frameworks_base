# Ctrl+←/→ 桌面任务切换（desk / fullscreen task 环形切换）实现文档

> 项目：OpenFDE Android（AOSP 17 fork，工作区根 D:\android17）
> 涉及仓库：`base`（framework/base，含 SystemUI / wm-shell / services / core res）
> **合入提交：`fb253d60`（分支 `fde_17_switchdeskfullscreen`，squash 自 `e98141e8` 等开发提交）**
> 说明：本文档描述的就是已 squash 合入的实现；`e98141e8` 之后的实验性提交
> （F→D 横向滑动尝试、`startTransaction.hide` 等）**不包含在内**，仅在"历史尝试"一节记录。
> 注意：开发过程中某个后续提交（7b2ef77）曾把 `META_META_MASK` 误写成 `META_META_MASKA`
> （无法编译），合入版本中是正确的 `META_META_MASK`。

---

## 1. 需求概述

- 快捷键：`Ctrl+←` / `Ctrl+→`（裸 Ctrl，不带 Shift/Alt/Meta/Sym/Function）。
- 行为：在**全屏任务（fullscreen task）**与 **desk（桌面，含其自由窗口）**之间按环形顺序切换；
  全屏任务之间、desk→全屏使用与"点击运行中任务图标切换"一致的**横向滑动动画**，左右方向互为镜像。
- 约束：desktop-first（多 desk）场景；任意前台应用获得焦点时都能响应；环顺序稳定不跳变；
  Home（Launcher）焦点参与导航。

---

## 2. 总体架构

```
物理键盘 Ctrl+方向键
        │
        ▼
PhoneWindowManager (system_server, 全局按键策略)
   interceptKeyBeforeQueueing() 捕获 KEYCODE_DPAD_LEFT/RIGHT
        │  裸 Ctrl 判定 + 消费按键(~ACTION_PASS_TO_USER)
        ▼
ServiceManager 查询 "TASK_SWITCH" binder
        │
        ▼
wm-shell (SystemUI 进程) DesktopTasksController
   环形任务列表(会话级稳定) + 当前锚点判定 + 目标选择 + 动画分派
        ├──▶ 全屏→全屏：startRunningTaskFromRecentsNative()（原生 task-open 滑动）
        ├──▶ desk→全屏：startRunningTaskFromRecentsNative()（同上，横向滑动）
        ├──▶ 全屏→desk：activateDesk(forceDefaultTransition=true)（默认过渡引擎，见第 7 节）
        ├──▶ desk→desk ：switchDeskWithAnimation()（DeskSwitchTransitionHandler 横向过渡）
        └──▶ Home 直跳：moveToFullscreen()（退出桌面动画）
```

为什么分两层：

- 按键必须在任意前台 App 有焦点时全局生效；Ctrl+方向无法新增原生 KeyGesture（要改
  frameworks/native），因此复用 fork 现有 PwM 里 Ctrl+D/F10/Esc 的拦截模式。
- "desk 是什么、哪个 desk 激活、desk 顺序"只存在于 **wm-shell（SystemUI 进程）**的桌面
  控制器/仓库，system_server 看不到，因此环构建/锚点/切换都放在 wm-shell，通过 ServiceManager
  binder 暴露给 PwM。

---

## 3. 改动文件清单（相对上游）

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `base/core/java/com/android/internal/policy/ITaskSwitchService.aidl` | 新增 | PwM→wm-shell 的 binder：`void performTaskSwitch(int displayId, int direction)`（-1=左/前，+1=右/后） |
| `base/core/res/res/anim/task_open_enter_from_left.xml` | 新增 | `task_open_enter` 水平镜像：目标从**左侧**滑入（-105% → 0，500ms `fast_out_extra_slow_in`） |
| `base/core/res/res/anim/task_open_exit_to_right.xml` | 新增 | `task_open_exit` 水平镜像：当前任务向**右侧**滑出（0 → +105%） |
| `base/services/core/java/com/android/server/policy/PhoneWindowManager.java` | 修改 | `interceptKeyBeforeQueueing()` 增加 `KEYCODE_DPAD_LEFT/RIGHT` 分支；新增 `performTaskSwitch(displayId, direction)`、`isBareCtrlShortcut(event)` |
| `base/libs/WindowManager/Shell/src/com/android/wm/shell/desktopmode/DesktopTasksController.kt` | 修改 | 核心逻辑：binder stub 与注册、环会话、锚点判定、目标分派、`startRunningTaskFromRecentsNative()`、`switchDeskWithAnimation()`、`activateDesk(forceDefaultTransition)` |

> 早期曾新增独立类 `TaskSwitchService.kt`（Dagger `@Provides` 提供、WMShellModule 注册），
> 因 `@Provides` 是惰性的、无人注入就不会实例化，导致 binder 永远没注册；已删除，改为注册在
> 确定会初始化的 `DesktopTasksController.onInit()`（与 F11 的 `TASK_CAPTION_OPERATION` 同机制）。

---

## 4. 环（Ring）的构建与维护

### 4.1 候选集（每次按键重新枚举）

```kotlin
// 1) 运行中的全屏任务：ATMS.getTasks(max=INT_MAX, filterOnlyVisibleRecents=false, ...)
//    过滤：activityType==STANDARD && windowingMode==FULLSCREEN && parentTaskId==INVALID_TASK_ID
//    （不在任何 desk 里的独立全屏 Task；desk 内自由窗口不单独入环）
//    顺序：getTasks 为"最近使用降序"，.reversed() 得到"旧 → 新"（任务栏视觉：左旧右新）
val fullscreenTaskIds = runningTasks.filter{...}.map{it.taskId}.reversed()

// 2) 当前 display 的 desk：DesktopRepository.getDeskIds(displayId)，按 getDeskPosition() 升序
val orderedDeskIds = deskIds.sortedBy { repository.getDeskPosition(it) ?: Int.MAX_VALUE }

// 环 = 全屏任务段(旧→新) ++ desk 段(位置序)  （分段拼接）
val candidates = fullscreenTaskIds + orderedDeskIds
```

注意：环只包含**真正在运行**的任务；recents 里的"冷任务"（上次会话遗留、进程未启动）
不进入环、不参与切换。

### 4.2 稳定环会话（避免 MRU 抖动）

问题：每次把任务切到前台，系统 recents 顺序会变化；若每次按键都按最新 recents 重建环，
遍历顺序会跳变、"不是循环/会漏项"。

方案：会话级稳定列表（`ringSessionDisplayId` / `ringSessionUserId` / `ringSessionIds`）：

- 首次按键或重建：`ringSessionIds = candidates`（冻结当时顺序）。
- 后续按键：保留上一份列表的**相对顺序**，去掉已消失项，末尾追加新出现项。
- 重建条件：displayId/userId 变化，或当前锚点不在候选集里。
- 环大小 ≤1 → no-op。

### 4.3 当前项（anchor）判定

```text
1) 焦点是全屏 STANDARD 任务（type=STANDARD && mode=FULLSCREEN && parent==INVALID）→ 该 taskId
2) 否则取 desk 锚点：
   a. DesktopRepository.getActiveDeskId(displayId)（且必须在 orderedDeskIds 中）
   b. 否则 resolveDeskAnchor() 回退：
      - 焦点窗口的 parentTaskId ∈ orderedDeskIds（焦点在 desk 自由窗口上）→ 该 desk
      - 有可见自由窗口的 desk（isVisible==true 的 desk 子窗口）→ 该 desk
      - 都没有 → 第一个 desk（保证从 Home/桌面也能开始走环）
3) 焦点类型不在 {STANDARD, HOME} 时（Recents/Overview、Keyguard、IME 等）→ no-op
```

### 4.4 Home（Launcher 焦点）处理

- **环会话第一次进入 Home**（开机后第一次按键 / 环在 Home 焦点下重建，`pendingHomeShortcut=true`）：
  - `Ctrl+→` 直跳**最近使用**的全屏任务（`fullscreenTaskIds.last()`）
  - `Ctrl+←` 直跳**最久未用**的全屏任务（`fullscreenTaskIds.first()`）
  - 通过 `moveToFullscreen()` 执行（退出桌面动画），随后复位环会话并清除直跳标志。
- 之后环内导航再回到 Home（典型：切到空 desk、焦点落回 Launcher）：**不再直跳**，
  按 4.3 的 desk 锚点继续走环，保证两个方向一格一格推进、不漏项。
- `pendingHomeShortcut` 仅在"环重建且此刻焦点是 HOME"时重新置 true，避免中途重建又打开直跳。

### 4.5 步进与回绕

```kotlin
anchorIndex = ring.indexOf(anchorItem)
target = ring[Math.floorMod(anchorIndex + direction, ring.size)]  // 两端回绕
// direction=+1 (Ctrl+→) 向"更右/更新"一格；direction=-1 (Ctrl+←) 向"更左/更旧"一格
```

---

## 5. 切换动作实际做了什么（wm 视角）

| 场景 | 调用 | 底层实际效果 |
| --- | --- | --- |
| 全屏→全屏 | `startRunningTaskFromRecentsNative(taskId, direction)` → `ATMS.startActivityFromRecents(taskId, options)` | ATMS 把**已运行 Task**（完整 Activity 栈）resume 到前台；options 为强制附加的 `makeCustomTaskAnimation`（右向：framework `task_open_enter/exit`；左向：镜像资源），播放横向滑动 |
| desk→全屏 | 同上（`startRunningTaskFromRecentsNative`） | 同上，目标全屏任务横向滑入；desk 内容被盖住，wm 负责后续层级 |
| 全屏→desk | `activateDesk(deskId, userId, remote=null, taskIdToReorder=null, enterReason, forceDefaultTransition=true)` | WCT 激活 desk：desk root `reorder(onTop)`、设 launch root、TDA 允许任务拖移、必要时最小化超限窗口、deactivate 其它 desk；过渡类型 `TRANSIT_TO_FRONT` + 无自定义 handler → 由**默认过渡引擎**播放（见第 7 节） |
| desk→desk | `switchDeskWithAnimation(displayId, userId, activeDeskId, deskId, enterReason)` | `addDeskActivationChanges()` 生成激活变更 + `DeskSwitchTransitionHandler.startTransition()` 播放 desk 间横向滑动（方向由 desk 位置差自动决定） |
| Home 直跳 | `moveToFullscreen(taskId, KEYBOARD_SHORTCUT, remote=null)` | 退出桌面清理 + `DesktopToFullscreenTaskAnimator`（336ms 窗口放大到全屏） |

数据模型：

- desk 在 wm 层 = wm-shell 用 TaskOrganizer 创建的 **FREEFORM 根任务**（名称 "Desk"，
  deskId == 根任务 taskId）；自由窗口是其子任务（`RunningTaskInfo.parentTaskId == deskId`）。
- desk 激活状态/顺序的唯一权威来源是 SystemUI 进程内的 `DesktopRepository` /
  `RootTaskDesksOrganizer`，因此 desk 切换必须经 wm-shell 完成。

---

## 6. 动画实现（基线状态）

### 6.1 全屏↔全屏、desk→全屏：原生 task-open 横向滑动（两个方向都强制）

- 参照效果：点击任务条上另一个运行中全屏任务图标 = wm 原生 `task_open` 过渡
  （目标从右滑入、当前向左滑出，500ms + `fast_out_extra_slow_in`）。
- 实现：`startRunningTaskFromRecentsNative()` 对**两个方向都显式**调用
  `ActivityOptions.makeCustomTaskAnimation(...)`，anim id 运行时解析：

  ```kotlin
  val resources = context.resources
  enterResId = resources.getIdentifier(
      if (direction == DIRECTION_NEXT) "task_open_enter" else "task_open_enter_from_left",
      "anim", "android")
  exitResId  = resources.getIdentifier(
      if (direction == DIRECTION_NEXT) "task_open_exit" else "task_open_exit_to_right",
      "anim", "android")
  ActivityOptions.makeCustomTaskAnimation(context, enterResId, exitResId, ...).toBundle()
  ```

  然后 `ActivityTaskManager.getService().startActivityFromRecents(taskId, options)`。

- **为什么右向也要显式 custom**：在 desk 处于激活状态等场景下，传 `null`（走默认属性动画）
  会被 wm 解析成"放大进入"而不是横向滑动；显式指定同款 `task_open` 资源可保证两个方向都是横滑。
- **为什么用运行时 `getIdentifier`**：镜像动画放在 framework core res；若直接引用
  `com.android.internal.R.anim.*`，增量编译时会撞上 framework R 生成顺序问题（unresolved
  reference）。运行时按名字+包名查 id 可完全解耦编译顺序；资源本身仍需随 framework-res
  打进系统镜像。

### 6.2 desk→desk

`DeskSwitchTransitionHandler`：横向位移 + 淡入淡出（PhysicsAnimator 弹簧，位移量为屏宽比例）。

### 6.3 全屏→desk（基线为默认过渡引擎，非横滑）

- 基线实现：`activateDesk(forceDefaultTransition=true)` → `TRANSIT_TO_FRONT` + 无自定义
  handler，由 wm 默认过渡引擎播放。实际观感：全屏淡出/桌面淡入（不是横向滑动）。
- 为什么不做横滑（重要经验）：
  - 该过渡里全屏任务 change 是 `TRANSIT_TO_FRONT` 且无可见标志，其 change **leash 不是最终
    参与合成的表面**；对该 leash 做 `setAlpha` 不生效（SurfaceFlinger 的 alpha 不会沿容器
    向子层叠加），做 `setPosition` 会产生静止副本 → 拖影/残影。
  - `Change.getSnapshot()` 在本路径为 null（无 ScreenshotSync 冻结层），没有可控的截图表面。
  - 基线之后的实验（自研 TransitionHandler 平移 desk 子窗口/根容器 + 隐藏全屏、进程内
    RemoteTransition runner）要么拖影、要么无效/闪屏，均未合入（见第 10 节）。
- 结论：基线接受"F→D 由默认引擎过渡、非横向滑动"这一折中；如需最终统一，需要更底层的
  wm 改动（拿到全屏任务真实 WindowState surface 或改变过渡语义），风险/成本较高。

---

## 7. 关键点与注意事项（合入前必读）

1. **按键是无条件全局拦截**：`Ctrl+←/→` 与 fork 其它 Ctrl 快捷键一致，不区分是否在输入框
   （IME 门控曾用 IMMS 输入会话实现，因浏览器类应用长期持有会话导致误判而回退）。
   副作用：浏览器/编辑器里的 Ctrl+方向"按词跳转"会被占用。如需细化，建议按"软键盘可见 /
   IMMS 当前编辑目标窗口"再做二期。
2. **binder 注册时机**：必须注册在确定会执行初始化的对象里。Dagger `@Provides` 惰性实例化
   不会自动构造无人依赖的类；当前注册在 `DesktopTasksController.onInit()`
   （`ServiceManager.addService("TASK_SWITCH", taskSwitchStub)`），且该回调只在
   `desktopState.canEnterDesktopMode` 时执行。
3. **环只包含运行中的任务**：recents 里冷任务不参与；若后续要支持冷启动切换，需扩展候选集与
   启动路径（本期明确不做）。
4. **Home 直跳只生效一次**（每个环会话）；之后回到 Home 继续走环，避免漏项。
5. **镜像动画资源位置与加载**：放 framework core res（`base/core/res/res/anim/`），运行时
   `getIdentifier(name, "anim", "android")` 获取 id；编译时不要引用 `com.android.internal.R`。
6. **F→D 与 D→F 不是同一种动画机制**：D→F = 原生横滑；F→D = 默认引擎过渡（非横滑）。
   这是基线的已知限制，不要把它当成 bug 回退（详见 6.3 与第 10 节）。
7. **`forceDefaultTransition` 参数**：`activateDesk()` 用它（F→D 分支传入 true）；
   `moveToFullscreen()` 也有同名参数，但基线内没有调用方传 true（预留）。
8. **多用户/多显示**：环会话按 displayId+userId 区分，但主要在单显示桌面场景验证。
9. **性能**：每次按键会调用 `getTasks` 并打全量 dump 日志（debug 用，量大时可降级为 Log.v）。

---

## 8. 调试方法

- 日志 Tag：
  - 按键侧 `PhoneWindowManager`：
    `performTaskSwitch: keyCode=... down=... repeat=... displayId=...`；
    `TASK_SWITCH service not available` = SystemUI 未注册/未刷入。
  - 逻辑侧 `DesktopTasksController`：
    `performKeyboardTaskSwitch: ...`（running tasks 全量 dump、`fullscreenTaskIds=`、
    `deskIds/orderedDeskIds=`、锚点、`ring=[...]`、`switch anchor=X -> target=Y`）。
- binder 检查：
  ```
  adb shell service check TASK_SWITCH          # 期望: found
  adb logcat -d | grep taskSwitchStub          # DesktopTasksController: taskSwitchStub registered=TASK_SWITCH
  ```
- 过滤日志：
  ```
  adb logcat -s DesktopTasksController PhoneWindowManager
  ```

---

## 9. 基线验证状态

- 全屏↔全屏：横向滑动、左右镜像、无卡帧/残影 —— 已验证。
- desk→全屏：横向滑动（两个方向）—— 已验证。
- desk↔desk：横向 desk 切换过渡 —— 已验证。
- 全屏→desk：默认引擎过渡（淡出/淡入，非横滑）—— 已知折中，可接受。
- 环形导航：顺序稳定、两端回绕、不漏项；Home 首次直跳、之后继续走环 —— 已验证。
- 回归：T↔T / D→F / desk↔desk 正常。

---

## 10. 已知限制与历史尝试（勿重复踩坑）

**已知限制**

1. F→D 不是横向滑动（默认引擎过渡）；原因与可行方向见 6.3。
2. 输入框聚焦时也会拦截 Ctrl+方向（见 7.1）。
3. 冷任务不参与切换（见 7.3）。
4. 多用户/多显示场景未完整验证。

**历史尝试（均未合入，基线不含）**

- 独立 `TaskSwitchService.kt` + Dagger `@Provides` 注册 binder → 惰性实例化导致 binder 未注册。
- SystemUI 本地 res 的镜像动画 + `getIdentifier("com.android.systemui")` → 改用 framework
  core res + 运行时查 id。
- 自研 TransitionHandler + ValueAnimator 手动平移 leash（全屏↔全屏场景）→ 中间帧/残影；
  最终全屏↔全屏完全交回 wm 原生动画引擎。
- F→D 的多种横滑尝试：`createDeskActivationSlideHandler`（平移 desk 子窗口/根容器、
  移动全屏 leash、使用 `Change.getSnapshot()`、`startTransaction.hide()`）、进程内
  `RemoteTransition` runner（TaskSlideRemoteTransition）→ 分别出现拖影、无效或闪屏，
  均未采用。
- 后续提交中的 `META_META_MASKA` 拼写错误（7b2ef77）——基线 `e98141e8` 无此问题。
