# Raft论文地址
Raft中文翻译：[https://www.cnblogs.com/ki11-9/articles/16586010.html#%E6%91%98%E8%A6%81](https://www.cnblogs.com/ki11-9/articles/16586010.html#%E6%91%98%E8%A6%81)和[https://github.com/maemual/raft-zh_cn/blob/master/raft-zh_cn.md](https://github.com/maemual/raft-zh_cn/blob/master/raft-zh_cn.md)

# 选举
## 选举接收者
### Raft - RequestVote RPC 处理流程梳理
#### 方法：`requestVote(RvoteParam param)`
该方法用于实现 Raft 协议中的请求投票逻辑，由 **接收者节点（Follower）** 执行，响应来自候选人的投票请求。

#### 总体流程逻辑：
1. **加锁防并发**
    - 使用 `voteLock.tryLock()` 防止多个投票请求并发执行。
    - 若加锁失败，立即拒绝投票（`voteGranted = false`）。
2. **任期比较**
    - 如果 `param.term < currentTerm`，说明对方任期过旧，直接拒绝投票。
3. **更新本地任期和状态**
    - 如果 `param.term > currentTerm`：
        * 更新本地 `currentTerm`
        * 清空本地 `votedFor`（任期变了，重置投票）
        * 切换为 FOLLOWER 状态
4. **是否可以投票**
    - 若本地尚未投票，或已经投给该候选人（`votedFor == null || votedFor == candidateId`），则进入下一步判断；
    - 否则，拒绝投票。
5. **候选人日志新旧判断**
    - 获取当前节点的最后日志条目（`lastLog`）；
    - 若候选人日志的 `term < lastLog.term`，拒绝；
    - 若 `term` 相同，但 `index < lastLog.index`，也拒绝。
6. **确认投票**
    - 满足所有条件，记录投票信息：
        * 设置 `votedFor = candidateId`
        * 更新 `currentTerm`
        * 状态设为 FOLLOWER
        * 记录 leader 为该候选人
    - 最终返回 `voteGranted = true`。

#### 关键判断总结：
| 判断项 | 条件 | 说明 |
| --- | --- | --- |
| 任期是否过时 | `param.term < currentTerm` | 否决 |
| 任期是否更新 | `param.term > currentTerm` | 刷新 term、状态 |
| 是否能投票 | `votedFor == null | |
| 日志是否新 | `param.lastLogTerm > lastLog.term`<br/> or 相等时 `param.lastLogIndex >= lastLog.index` | 满足才投 |
| 投票确认 | 满足所有判断 | 返回 `voteGranted = true` |


---

#### 小贴士
+ 日志新旧判断遵循 Raft 协议 5.4 节：“日志越新越可信”。

## 选举发起者
### <font style="color:rgb(64, 64, 64);">Raft 选举发起流程总结（基于代码分析）</font>
#### **<font style="color:rgb(64, 64, 64);">1. 选举触发条件</font>**
+ **<font style="color:rgb(64, 64, 64);">心跳超时</font>**<font style="color:rgb(64, 64, 64);">：当节点（Follower/Candidate）超过</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">electionTimeout</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">未收到 Leader 的心跳（</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">preHeartBeatTime</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">未更新）。</font>
+ **<font style="color:rgb(64, 64, 64);">随机化选举超时</font>**<font style="color:rgb(64, 64, 64);">：</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">electionTimeout = base + 随机时间（150-350ms）</font>**`<font style="color:rgb(64, 64, 64);">，避免多个节点同时发起选举。</font>

#### **<font style="color:rgb(64, 64, 64);">2. 候选人（Candidate）阶段</font>**
1. **<font style="color:rgb(64, 64, 64);">状态转换</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">节点状态从</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">FOLLOWER</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">变为</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">CANDIDATE</font>**`<font style="color:rgb(64, 64, 64);">。</font>
    - **<font style="color:rgb(64, 64, 64);">任期号自增</font>**<font style="color:rgb(64, 64, 64);">：</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">currentTerm++</font>**`<font style="color:rgb(64, 64, 64);">。</font>
    - **<font style="color:rgb(64, 64, 64);">自投票</font>**<font style="color:rgb(64, 64, 64);">：</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">votedFor = selfAddress</font>**`<font style="color:rgb(64, 64, 64);">。</font>
    - **<font style="color:rgb(64, 64, 64);">重置选举计时器</font>**<font style="color:rgb(64, 64, 64);">：更新</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">preElectionTime</font>**`<font style="color:rgb(64, 64, 64);">。</font>
2. **<font style="color:rgb(64, 64, 64);">发起投票请求：</font>**
    - <font style="color:rgb(64, 64, 64);">向所有其他节点并行发送</font><font style="color:rgb(64, 64, 64);"> </font>**<font style="color:rgb(64, 64, 64);">RequestVote RPC</font>**<font style="color:rgb(64, 64, 64);">。</font>
    - <font style="color:rgb(64, 64, 64);">RPC 参数包含：</font>
        * `**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">term</font>**`<font style="color:rgb(64, 64, 64);">：当前任期。</font>
        * `**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">candidateId</font>**`<font style="color:rgb(64, 64, 64);">：自身地址。</font>
        * `**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastLogIndex</font>**`<font style="color:rgb(64, 64, 64);"> 和 </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastLogTerm</font>**`<font style="color:rgb(64, 64, 64);">：本地最新日志的索引和任期（用于日志新旧比较）。</font>

#### **<font style="color:rgb(64, 64, 64);">3. 投票处理逻辑</font>**
1. **<font style="color:rgb(64, 64, 64);">等待投票结果</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">使用</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">CountDownLatch</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">等待所有 RPC 响应，超时时间为 3.5 秒。</font>
    - <font style="color:rgb(64, 64, 64);">统计成功获得的投票数（需满足</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">投票数 > 总节点数/2</font>**`<font style="color:rgb(64, 64, 64);">）。</font>
2. **<font style="color:rgb(64, 64, 64);">处理响应</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - **<font style="color:rgb(64, 64, 64);">成功投票</font>**<font style="color:rgb(64, 64, 64);">：响应中</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">voteGranted=true</font>**`<font style="color:rgb(64, 64, 64);">，统计成功数。</font>
    - **<font style="color:rgb(64, 64, 64);">任期号更新</font>**<font style="color:rgb(64, 64, 64);">：若对方节点任期更大（</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">resTerm > currentTerm</font>**`<font style="color:rgb(64, 64, 64);">），退化为 Follower 并更新</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">currentTerm</font>**`<font style="color:rgb(64, 64, 64);">。</font>
3. **<font style="color:rgb(64, 64, 64);">成为 Leader 条件</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">获得超过半数投票后：</font>
        * <font style="color:rgb(64, 64, 64);">状态变为</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">LEADER</font>**`<font style="color:rgb(64, 64, 64);">。</font>
        * <font style="color:rgb(64, 64, 64);">清空</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">votedFor</font>**`<font style="color:rgb(64, 64, 64);">（新任期需重新投票）。</font>
        * <font style="color:rgb(64, 64, 64);">调用</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">becomeLeaderToDoThing()</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">初始化 Leader 状态。</font>

---

#### **<font style="color:rgb(64, 64, 64);">4. Leader 初始化流程</font>**
1. **<font style="color:rgb(64, 64, 64);">日志复制准备</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">初始化</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">nextIndex[]</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">和</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">matchIndex[]</font>**`<font style="color:rgb(64, 64, 64);">：</font>
        * `**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">nextIndex[peer] = lastLogIndex + 1</font>**`<font style="color:rgb(64, 64, 64);">（预期从下一个日志开始同步）。</font>
        * `**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">matchIndex[peer] = 0</font>**`<font style="color:rgb(64, 64, 64);">（记录已复制的最高日志索引）。</font>
2. **<font style="color:rgb(64, 64, 64);">提交空日志</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">预提交一条</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">term=currentTerm</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">的空日志（解决前任未提交日志）。</font>
    - <font style="color:rgb(64, 64, 64);">将该日志并行复制到所有节点（通过</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">replication()</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">方法）。</font>
3. **<font style="color:rgb(64, 64, 64);">日志复制结果处理</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - **<font style="color:rgb(64, 64, 64);">成功条件</font>**<font style="color:rgb(64, 64, 64);">：半数以上节点复制成功则提交日志到状态机。</font>
    - **<font style="color:rgb(64, 64, 64);">失败回滚</font>**<font style="color:rgb(64, 64, 64);">：若未成功半数，回滚日志并降级为 Follower。</font>

---

#### **<font style="color:rgb(64, 64, 64);">5. 异常与降级处理</font>**
+ **<font style="color:rgb(64, 64, 64);">收到更高任期的 RPC</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">若在投票或日志复制过程中收到更高任期的请求（如来自新 Leader 的 AppendEntries），立即退化为 Follower。</font>
+ **<font style="color:rgb(64, 64, 64);">选举超时</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">若选举未完成（未获得多数票），重置计时器重新发起选举。</font>

---

#### **<font style="color:rgb(64, 64, 64);">6. 关键实现细节</font>**
+ **<font style="color:rgb(64, 64, 64);">线程池与异步 RPC</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">使用</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">RaftThreadPool</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">并行发送 RPC，提高选举效率。</font>
+ **<font style="color:rgb(64, 64, 64);">日志匹配规则</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">通过</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastLogTerm</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">和</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastLogIndex</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">确保候选人日志足够新（Raft 安全性）。</font>
+ **<font style="color:rgb(64, 64, 64);">随机化超时</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">避免多个节点同时成为 Candidate，减少选举冲突。</font>

---

#### **<font style="color:rgb(64, 64, 64);">7. 代码中的 Raft 协议对照</font>**
+ **<font style="color:rgb(64, 64, 64);">选举限制</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">仅当候选人日志比跟随者新时，才会获得投票（通过</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastLogTerm</font>**`<font style="color:rgb(64, 64, 64);"> </font><font style="color:rgb(64, 64, 64);">比较）。</font>
+ **<font style="color:rgb(64, 64, 64);">Leader 心跳</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">成为 Leader 后通过</font><font style="color:rgb(64, 64, 64);"> </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">AppendEntries</font>**`<font style="color:rgb(64, 64, 64);">（含空日志）广播心跳。</font>
+ **<font style="color:rgb(64, 64, 64);">状态机安全性</font>**<font style="color:rgb(64, 64, 64);">：</font>
    - <font style="color:rgb(64, 64, 64);">仅提交当前任期的日志（</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">log[N].term == currentTerm</font>**`<font style="color:rgb(64, 64, 64);"> 时更新 </font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">commitIndex</font>**`<font style="color:rgb(64, 64, 64);">）。</font>

#### 8.简易流程图
```plain
[心跳超时]  
   → [成为 Candidate]  
   → [自增任期，发起投票]  
   → [等待多数投票]  
      → 成功 → [成为 Leader，提交空日志]  
      → 失败 → [重新选举]
```

### <font style="color:#080808;background-color:#ffffff;">becomeLeaderToDoThing()</font>
#### 初始化leader的nextIndex和mtachIndex
#### <font style="color:rgb(0, 0, 0);">对leader的commitIndex进行调整，找出之前领导者已经提交过的部分。假设存在 N 满足</font>`<font style="color:rgb(192, 52, 29);background-color:rgb(251, 229, 225);">N > commitIndex</font>`<font style="color:rgb(0, 0, 0);">，使得大多数follower的 </font>`<font style="color:rgb(192, 52, 29);background-color:rgb(251, 229, 225);">matchIndex[i] ≥ N</font>`<font style="color:rgb(0, 0, 0);">以及</font>`<font style="color:rgb(192, 52, 29);background-color:rgb(251, 229, 225);">log[N].term == currentTerm</font>`<font style="color:rgb(0, 0, 0);"> 成立，则令 </font>`<font style="color:rgb(192, 52, 29);background-color:rgb(251, 229, 225);">commitIndex = N</font>`<font style="color:rgb(0, 0, 0);">（++个人注： 大多数</font>`<font style="color:rgb(192, 52, 29);background-color:rgb(251, 229, 225);">matchIndex[i] ≥ N</font>`<font style="color:rgb(0, 0, 0);">说明大多follower上的日志条目及commitIndex值已至少更新至N，并且日志条目中最新term与follower的currentTerm一致，则说明大多数follower日志条目已更新（但未确定是否同步至状态机），则leader将commitIndex偏移至N++）（5.3 和 5.4 节）</font>


# 心跳
## 心跳发起者
## 心跳接收者
+ **<font style="color:rgb(64, 64, 64);">正确实践</font>**<font style="color:rgb(64, 64, 64);">：先按顺序应用所有需提交的日志（从</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastApplied + 1</font>**`<font style="color:rgb(64, 64, 64);">到</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">commitIndex</font>**`<font style="color:rgb(64, 64, 64);">），再更新</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastApplied</font>**`<font style="color:rgb(64, 64, 64);">至当前</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">commitIndex</font>**`<font style="color:rgb(64, 64, 64);">。</font>
+ **<font style="color:rgb(64, 64, 64);">性能优化</font>**<font style="color:rgb(64, 64, 64);">：可根据场景选择逐条或批量更新</font>`**<font style="color:rgb(64, 64, 64);background-color:rgb(236, 236, 236);">lastApplied</font>**`<font style="color:rgb(64, 64, 64);">，但必须保证更新的原子性和顺序性。（这里选择批量更新lastApplied，因为本项目注重于raft部分，具体实现做淡化）</font>
+ **<font style="color:rgb(64, 64, 64);">幂等性设计</font>**<font style="color:rgb(64, 64, 64);">：日志应用操作需支持重复执行，防止因崩溃导致的部分应用问题。</font>

## ✅ 领导人完整性特性安全性证明的思路
我们要 **用反证法** 来证明：

**一旦某条日志条目被提交（即被一个任期的领导人复制到多数派上），那么这条条目将会出现在之后所有任期的领导人日志中。**

---

### 🔁 反设
假设这个特性不成立，也就是说：

+ 任期 T 的领导人提交了一条日志条目（即大多数节点都复制了它），
+ 然而，后面某个任期 U 的领导人却**没有这条日志条目**。

我们要推导出矛盾。

---

### 🧩 基本事实与推理链条
#### ① 提交日志条目 => 大多数服务器拥有该日志
任期 T 的领导人要“提交”某条日志条目，必须要将该条目复制到多数派节点上。

即：

```plain
mathematica


复制编辑
条目 E 被提交 → 至少过半节点包含该条目（如 S1, S2, S3）
```

---

#### ② 任期 U 的领导人赢得选举 => 也获得多数节点的投票
领导人 U 被选上，说明它也获得了大多数节点的选票。

```plain
复制编辑
即有多数派（如 S3, S4, S5）把票投给了 U
```

由于任期 T 和任期 U 都获得了多数派，那么这两个多数派集合必定有**交集**。

所以：

至少存在一个节点（比如 S3）

1. 有来自 T 任期领导人的已提交日志条目
2. 又在 U 任期投票给了新的候选人 U

这个 S3 是核心的“矛盾触发点”。

---

#### ③ S3 投票规则（投票人必须日志不落后）
Raft 协议中有一个关键投票原则：

节点只有在候选人日志“至少一样新”时才会投票给它。

即：

```plain
复制编辑
候选人的 lastLogTerm > 本地 lastLogTerm
或
lastLogTerm 相同但 lastLogIndex ≥ 本地 lastLogIndex
```

而 S3 投票给了 U，那么 U 的日志必须**不落后于 S3**，甚至可能更新。

但是！S3 拥有那条来自 T 任期的已提交日志条目，如果 U 的日志没有这条条目，那就意味着：

+ 要么 U 的日志短（比 S3 落后）→ 违反了投票规则，矛盾。
+ 要么 U 的日志更长，但其最后一条日志的 term 比 T 大 → 那说明这条日志是由某个任期 > T 的领导人写入的。

---

#### ④ Raft 的日志追加特性
Raft 规定：

如果两个日志在某个 index 上 term 相同，那么之前的日志一定也一样（日志匹配原则）。

并且：

领导人不会覆盖已有日志条目。

所以，如果 U 的日志中**包含某个 index 条目的 term > T**，那么：

+ 写入那条日志的更早的领导人，日志中**一定已经包含了来自 T 的已提交条目**（因为我们假设 T 的条目已经被提交）。

---

#### ⑤ 结论：U 不可能没有该条目
无论哪种情况推理下去，都得出一个结论：

U 必然应该有来自 T 的那条已提交日志条目。

如果没有，就会出现以下任意一种矛盾：

+ 投票规则矛盾（落后的日志获得投票）
+ 日志覆盖矛盾（领导人日志覆盖了已提交日志）
+ 日志匹配矛盾（中间某个领导人不应该缺失该条目）

所以：

✅ 所有后续任期的领导人都必须包含任期 T 提交的日志条目。

---

### ✅ 状态机安全性的结论
上面的证明核心是：

+ 一旦某条日志被提交，它就“永不丢失”
+ 所有后续领导人一定会拥有它

结合 Raft 规定“只能提交当前任期的领导人提交的条目”+“按日志顺序应用到状态机”，就可以保证：

所有服务器的状态机最终都应用**相同顺序的日志条目集合**，从而实现状态一致性（State Machine Safety）。

## ✅ 日志匹配原则的成立是因为 Raft 的日志复制和冲突处理机制
### 🌟 1. 日志是通过领导人（Leader）同步给跟随者（Follower）的
+ 当 Leader 向 Follower 发送 `AppendEntries`（附加日志） RPC 时，会带上：
    - `prevLogIndex`: 前一个日志条目的索引
    - `prevLogTerm`: 该条目的任期号
    - `entries[]`: 要追加的新日志条目

### 🌟 2. 跟随者在接收日志前会做一致性检查：
+ 只有当 Follower 在自己的日志中找到了 `prevLogIndex` 和 `prevLogTerm` 都匹配的日志条目，它才会接受新日志条目并追加；
+ 否则它就会拒绝，Leader 会回退索引重试。

📌 这就意味着：一个日志条目之所以能追加成功，它前面的日志必须完全和 Leader 保持一致。

---

### 🔁 所以日志匹配原则是如何“强制维持”的？
想象一个跟随者的日志与 Leader 不一致：  
Follower 日志: [1, 2, 3(term=4), 4(term=4)]  
Leader 日志:   [1, 2, 3(term=3), 4(term=3), 5(term=5)]

如果 Leader 想要同步第 5 条（term=5）日志，会先发 `prevLogIndex=4, prevLogTerm=3`。

Follower 检查自己第 4 条是 term=4，不匹配，会拒绝。

然后 Leader 会➡️ 回退，发 `prevLogIndex=3, prevLogTerm=3`，仍然不匹配，直到找到共同点。

最终 Leader 会删除 Follower 中冲突的日志条目，再从那点重新同步。

---

✅ 因此一旦某个位置的 term 匹配，前面的日志就一定完全相同！

否则，AppendEntries 根本同步不过去！

这就是日志匹配原则成立的根本原因：**Raft 的日志追加过程本身强制要求日志在接入点之前完全一致。**

---

### 总结一句话：
**日志匹配原则不是自然推理出来的，而是由 Raft 的日志复制机制和附加日志的严格检查机制“硬性保证”的。**  
一条日志条目之所以能出现在某个 index 上，必须是前面的所有日志都和 Leader 完全一致。

## 


# 日志复制
## 日志发起者
## 日志接收者


