package node.impl;


import entity.*;
import node.Node;
import common.status.NodeStatus;
import consensus.impl.DefaultConsensus;
import log.LogModule;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import rpc.RaftRemotingException;
import rpc.Request;
import rpc.RpcClient;
import rpc.impl.DefaultRpcClient;
import rpc.impl.DefaultRpcServiceImpl;
import stateMachine.StateMachine;
import threadpool.RaftThreadPool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Getter
@Setter
@Slf4j
public class DefaultNode implements Node {

    /* ============ 选举部分 ============= */

        /* ============ 心跳部分 ============= */
        /** 上次一心跳时间戳 */
        public volatile long preHeartBeatTime = 0;
        /** 心跳间隔基数 */
        public final long heartBeatTick = 5 * 100;

        private HeartBeatTask heartBeatTask = new HeartBeatTask();

        /* ============ 心跳部分 ============= */

    /** 选举时间间隔基数 */
    public volatile long baseElectionTimeout  = 15 * 1000;

    /** 上一次选举时间 */
    public volatile long preElectionTime = 0;

    /* ============ 选举部分 ============= */


    /* ============ 节点当前状态 ============= */

    public volatile int status = NodeStatus.FOLLOWER;

    public PeerSet peerSet;

    volatile boolean running = false;

    /* ============ 节点当前状态 ============= */


    /* ============ 所有服务器上持久存在的 ============= */

    /** 服务器最后一次知道的任期号（初始化为 0，持续递增） */
    public volatile long currentTerm = 0;

    /** 在当前获得选票的候选人的 Id */
    volatile String votedFor;

        /* ============ 日志部分 ============= */

            /** 日志条目集；每一个条目包含一个用户状态机执行的指令，和收到时的任期号 */
            public LogModule logModule;

        /* ============ 日志部分 ============= */

    /* ============ 所有服务器上持久存在的 ============= */


    /* ============ 所有服务器上经常变的 ============= */

    /** 已知的最大的已经被提交的日志条目的索引值 */
    volatile long commitIndex;

    /** 最后被应用到状态机的日志条目索引值（初始化为 0，持续递增) */
    volatile long lastApplied = 0;

    /* ============ 所有服务器上经常变的 ============= */


    /* ========== 在领导人里经常改变的(选举后重新初始化) ================== */

    /** 对于每一个服务器，需要发送给他的下一个日志条目的索引值（初始化为领导人最后索引值加一） */
    Map<Peer, Long> nextIndexs;

    /** 对于每一个服务器，已经复制给他的日志的最高索引值 */
    Map<Peer, Long> matchIndexs;

    /* ========== 在领导人里经常改变的(选举后重新初始化) ================== */


    /* ========== 一致性模块 ================== */

    DefaultConsensus defaultConsensus;

    /* ========== 一致性模块 ================== */

    public RpcClient rpcClient = new DefaultRpcClient();
    private DefaultRpcServiceImpl rpcServer;


    /* ========== 状态机模块 ================== */


    public StateMachine stateMachine;


    /* ========== 状态机模块 ================== */


    @Override
    public void init() throws Throwable {

    }

    @Override
    public void destroy() throws Throwable {

    }

    @Override
    public void setConfig(NodeConfig config) {
        this.config = config;
        stateMachine = StateMachineSaveType.getForType(config.getStateMachineSaveType()).getStateMachine();
        logModule = DefaultLogModule.getInstance();

        peerSet = PeerSet.getInstance();
        for (String s : config.getPeerAddrs()) {
            Peer peer = new Peer(s);
            peerSet.addPeer(peer);
            if (s.equals("localhost:" + config.getSelfPort())) {
                peerSet.setSelf(peer);
            }
        }

        rpcServer = new DefaultRpcServiceImpl(config.selfPort, this);
    }

    @Override
    public RvoteResult handlerRequestVote(RvoteParam param) {
        return defaultConsensus.requestVote(param);
    }

    @Override
    public AentryResult handlerAppendEntries(AentryParam param) {
        return defaultConsensus.appendEntries(param);
    }

    @Override
    public ClientKVAck handlerClientRequest(ClientKVReq request) {
        return null;
    }

    @Override
    public ClientKVAck redirect(ClientKVReq request) {
        return null;
    }

    /**
     * 心跳
     */
    class HeartBeatTask implements Runnable {

        @Override
        public void run() {

            if (status != NodeStatus.LEADER) {
                return;
            }

            long current = System.currentTimeMillis();
            if (current - preHeartBeatTime < heartBeatTick) {
                return;
            }
            log.info("=========== NextIndex =============");
            for (Peer peer : peerSet.getPeersWithOutSelf()) {
                log.info("Peer {} nextIndex={}", peer.getAddr(), nextIndexs.get(peer));
            }

            preHeartBeatTime = System.currentTimeMillis();

            // 心跳只关心 term 和 leaderID
            for (Peer peer : peerSet.getPeersWithOutSelf()) {

                AentryParam param = AentryParam.builder()
                        .entries(null)// 心跳,空日志.
                        .leaderId(peerSet.getSelf().getAddr())
                        .serverId(peer.getAddr())
                        .term(currentTerm)
                        .leaderCommit(commitIndex) // 心跳时与跟随者同步 commit index
                        .build();

                Request request = new Request(
                        Request.A_ENTRIES,
                        param,
                        peer.getAddr());

                RaftThreadPool.execute(() -> {
                    try {
                        AentryResult aentryResult = getRpcClient().send(request);
                        long term = aentryResult.getTerm();

                        if (term > currentTerm) {
                            log.error("self will become follower, he's term : {}, my term : {}", term, currentTerm);
                            currentTerm = term;
                            votedFor = "";
                            status = NodeStatus.FOLLOWER;
                        }
                    } catch (Exception e) {
                        log.error("HeartBeatTask RPC Fail, request URL : {} ", request.getUrl());
                    }
                });
            }
        }
    }

    /**
     * 1. 在转变成候选人后就立即开始选举过程
     * 自增当前的任期号（currentTerm）
     * 给自己投票
     * 重置选举超时计时器
     * 发送请求投票的 RPC 给其他所有服务器
     * 2. 如果接收到大多数服务器的选票，那么就变成领导人
     * 3. 如果接收到来自新的领导人的附加日志 RPC，转变成跟随者
     * 4. 如果选举过程超时，再次发起一轮选举
     */
    class ElectionTask implements Runnable {

        @Override
        public void run() {

            if (status == NodeStatus.LEADER) {
                return;
            }

            long current = System.currentTimeMillis();
            //1.判断心跳是否超时
            if(current - preHeartBeatTime < heartBeatTick){
                //1.未超时 返回
                return;
            }
            // 基于 RAFT 的随机时间,解决冲突.
            long electionTime = baseElectionTimeout  + ThreadLocalRandom.current().nextInt(50);
            if (current - preElectionTime < electionTime) {
                return;
            }
            status = NodeStatus.CANDIDATE;
            log.error("node {} will become CANDIDATE and start election leader, current term : [{}], LastEntry : [{}]",
                    peerSet.getSelf(), currentTerm, logModule.getLast());

            preElectionTime = System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(200) + 150;

            currentTerm = currentTerm + 1;
            // 推荐自己.
            votedFor = peerSet.getSelf().getAddr();

            List<Peer> peers = peerSet.getPeersWithOutSelf();

            ArrayList<Future<RvoteResult>> futureArrayList = new ArrayList<>();

            log.info("peerList size : {}, peer list content : {}", peers.size(), peers);

            // 发送请求
            for (Peer peer : peers) {

                futureArrayList.add(RaftThreadPool.submit(() -> {
                    long lastTerm = 0L;
                    LogEntry last = logModule.getLast();
                    if (last != null) {
                        lastTerm = last.getTerm();
                    }

                    RvoteParam param = RvoteParam.builder().
                            term(currentTerm).
                            candidateId(peerSet.getSelf().getAddr()).
                            lastLogIndex(logModule.getLastIndex()).
                            lastLogTerm(lastTerm).
                            build();

                    Request request = Request.builder()
                            .cmd(Request.R_VOTE)
                            .obj(param)
                            .url(peer.getAddr())
                            .build();

                    try {
                        return getRpcClient().<RvoteResult>send(request);
                    } catch (RaftRemotingException e) {
                        log.error("ElectionTask RPC Fail , URL : " + request.getUrl());
                        return null;
                    }
                }));
            }

            AtomicInteger success2 = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(futureArrayList.size());

            log.info("futureArrayList.size() : {}", futureArrayList.size());
            // 等待结果.
            for (Future<RvoteResult> future : futureArrayList) {
                RaftThreadPool.submit(() -> {
                    try {
                        RvoteResult result = future.get(3000, MILLISECONDS);
                        if (result == null) {
                            return -1;
                        }
                        boolean isVoteGranted = result.isVoteGranted();

                        if (isVoteGranted) {
                            success2.incrementAndGet();
                        } else {
                            // 更新自己的任期.
                            long resTerm = result.getTerm();
                            if (resTerm >= currentTerm) {
                                currentTerm = resTerm;
                            }
                        }
                        return 0;
                    } catch (Exception e) {
                        log.error("future.get exception , e : ", e);
                        return -1;
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                // 稍等片刻
                latch.await(3500, MILLISECONDS);
            } catch (InterruptedException e) {
                log.warn("InterruptedException By Master election Task");
            }

            int success = success2.get();
            log.info("node {} maybe become leader , success count = {} , status : {}", peerSet.getSelf(), success, NodeStatus.Enum.value(status));
            // 如果投票期间,有其他服务器发送 appendEntry , 就可能变成 follower ,这时,应该停止.
            if (status == NodeStatus.FOLLOWER) {
                return;
            }
            // 加上自身.
            if (success >= peers.size() / 2) {
                log.warn("node {} become leader ", peerSet.getSelf());
                status = NodeStatus.LEADER;
                peerSet.setLeader(peerSet.getSelf());
                /**
                 * Raft 需要保证每个任期内，节点的投票行为是独立且符合选举规则的。votedFor 置空的作用：
                 *
                 * Leader 不需要再投票，置空无影响。
                 * 避免 Leader 降级为 Follower 后投票错误。
                 * 保证新一轮选举时，节点能正确投票，而不是受上一个任期的状态影响。
                 * 防止因网络分区导致的错误选举，保持 Raft 选举的正确性。
                 * 这符合 Raft 算法的 安全性（Safety） 设计，避免了错误的投票行为，保证了一致性。
                 */
                votedFor = "";
                becomeLeaderToDoThing();
            } else {
                // else 重新选举
                votedFor = "";
            }
            // 再次更新选举时间
            preElectionTime = System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(200) + 150;

        }
    }

    /**
     * 初始化所有的 nextIndex 值为自己的最后一条日志的 index + 1. 如果下次 RPC 时, 跟随者和leader 不一致,就会失败.
     * 那么 leader 尝试递减 nextIndex 并进行重试.最终将达成一致.
     */
    private void becomeLeaderToDoThing() {
        nextIndexs = new ConcurrentHashMap<>();
        matchIndexs = new ConcurrentHashMap<>();
        for (Peer peer : peerSet.getPeersWithOutSelf()) {
            nextIndexs.put(peer, logModule.getLastIndex() + 1);
            matchIndexs.put(peer, 0L);
        }

        // 创建[空日志]并提交，用于处理前任领导者未提交的日志
        LogEntry logEntry = LogEntry.builder()
                .command(null)
                .term(currentTerm)
                .build();

        // 预提交到本地日志, TODO 预提交
        logModule.write(logEntry);
        log.info("write logModule success, logEntry info : {}, log index : {}", logEntry, logEntry.getIndex());

        final AtomicInteger success = new AtomicInteger(0);

        List<Future<Boolean>> futureList = new ArrayList<>();

        int count = 0;
        //  复制到其他机器
        for (Peer peer : peerSet.getPeersWithOutSelf()) {
            // TODO check self and RaftThreadPool
            count++;
            // 并行发起 RPC 复制.
            futureList.add(replication(peer, logEntry));
        }

        CountDownLatch latch = new CountDownLatch(futureList.size());
        List<Boolean> resultList = new CopyOnWriteArrayList<>();

        getRPCAppendResult(futureList, latch, resultList);

        try {
            latch.await(4000, MILLISECONDS);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }

        for (Boolean aBoolean : resultList) {
            if (aBoolean) {
                success.incrementAndGet();
            }
        }

        // 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，
        // 并且log[N].term == currentTerm成立，那么令 commitIndex 等 于这个 N （5.3 和 5.4 节）
        List<Long> matchIndexList = new ArrayList<>(matchIndexs.values());
        // 小于 2, 没有意义
        int median = 0;
        if (matchIndexList.size() >= 2) {
            Collections.sort(matchIndexList);
            median = matchIndexList.size() / 2;
        }
        Long N = matchIndexList.get(median);
        if (N > commitIndex) {
            LogEntry entry = logModule.read(N);
            if (entry != null && entry.getTerm() == currentTerm) {
                commitIndex = N;
            }
        }

        //  响应客户端(成功一半)
        if (success.get() >= (count / 2)) {
            // 更新
            commitIndex = logEntry.getIndex();
            //  应用到状态机
            getStateMachine().apply(logEntry);
            lastApplied = commitIndex;

            log.info("success apply local state machine,  logEntry info : {}", logEntry);
        } else {
            // 回滚已经提交的日志
            logModule.removeOnStartIndex(logEntry.getIndex());
            log.warn("fail apply local state  machine,  logEntry info : {}", logEntry);

            // 无法提交空日志，让出领导者位置
            log.warn("node {} becomeLeaderToDoThing fail ", peerSet.getSelf());
            status = NodeStatus.FOLLOWER;
            peerSet.setLeader(null);
            votedFor = "";
        }
    }

    /** 复制到其他机器 */
    public Future<Boolean> replication(Peer peer, LogEntry entry) {

        return RaftThreadPool.submit(() -> {

            long start = System.currentTimeMillis(), end = start;

            // 20 秒重试时间
            while (end - start < 20 * 1000L) {

                AentryParam aentryParam = AentryParam.builder().build();
                aentryParam.setTerm(currentTerm);
                aentryParam.setServerId(peer.getAddr());
                aentryParam.setLeaderId(peerSet.getSelf().getAddr());

                aentryParam.setLeaderCommit(commitIndex);

                // 以我这边为准, 这个行为通常是成为 leader 后,首次进行 RPC 才有意义.
                Long nextIndex = nextIndexs.get(peer);
                LinkedList<LogEntry> logEntries = new LinkedList<>();
                if (entry.getIndex() >= nextIndex) {
                    for (long i = nextIndex; i <= entry.getIndex(); i++) {
                        LogEntry l = logModule.read(i);
                        if (l != null) {
                            logEntries.add(l);
                        }
                    }
                } else {
                    logEntries.add(entry);
                }
                // 最小的那个日志.
                LogEntry preLog = getPreLog(logEntries.getFirst());
                aentryParam.setPreLogTerm(preLog.getTerm());
                aentryParam.setPrevLogIndex(preLog.getIndex());

                aentryParam.setEntries(logEntries.toArray(new LogEntry[0]));

                Request request = Request.builder()
                        .cmd(Request.A_ENTRIES)
                        .obj(aentryParam)
                        .url(peer.getAddr())
                        .build();

                try {
                    AentryResult result = getRpcClient().send(request);
                    if (result == null) {
                        return false;
                    }
                    if (result.isSuccess()) {
                        log.info("append follower entry success , follower=[{}], entry=[{}]", peer, aentryParam.getEntries());
                        // update 这两个追踪值
                        nextIndexs.put(peer, entry.getIndex() + 1);
                        matchIndexs.put(peer, entry.getIndex());
                        return true;
                    } else if (!result.isSuccess()) {
                        // 对方比我大
                        if (result.getTerm() > currentTerm) {
                            log.warn("follower [{}] term [{}] than more self, and my term = [{}], so, I will become follower",
                                    peer, result.getTerm(), currentTerm);
                            currentTerm = result.getTerm();
                            // 认怂, 变成跟随者
                            status = NodeStatus.FOLLOWER;
                            return false;
                        } // 没我大, 却失败了,说明 index 不对.或者 term 不对.
                        else {
                            // 递减
                            if (nextIndex == 0) {
                                nextIndex = 1L;
                            }
                            nextIndexs.put(peer, nextIndex - 1);
                            log.warn("follower {} nextIndex not match, will reduce nextIndex and retry RPC append, nextIndex : [{}]", peer.getAddr(),
                                    nextIndex);
                            // 重来, 直到成功.
                        }
                    }

                    end = System.currentTimeMillis();

                } catch (Exception e) {
                    log.warn(e.getMessage(), e);
                    // TODO 到底要不要放队列重试?
//                        ReplicationFailModel model =  ReplicationFailModel.newBuilder()
//                            .callable(this)
//                            .logEntry(entry)
//                            .peer(peer)
//                            .offerTime(System.currentTimeMillis())
//                            .build();
//                        replicationFailQueue.offer(model);
                    return false;
                }
            }
            // 超时了,没办法了
            return false;
        });
    }

    private void getRPCAppendResult(List<Future<Boolean>> futureList, CountDownLatch latch, List<Boolean> resultList) {
        for (Future<Boolean> future : futureList) {
            RaftThreadPool.execute(() -> {
                try {
                    resultList.add(future.get(3000, MILLISECONDS));
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    resultList.add(false);
                } finally {
                    latch.countDown();
                }
            });
        }
    }


    private LogEntry getPreLog(LogEntry logEntry) {
        LogEntry entry = logModule.read(logEntry.getIndex() - 1);

        if (entry == null) {
            log.warn("get perLog is null , parameter logEntry : {}", logEntry);
            entry = LogEntry.builder().index(0L).term(0).command(null).build();
        }
        return entry;
    }
}
