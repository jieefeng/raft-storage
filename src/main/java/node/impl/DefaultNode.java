package node.impl;

import common.entity.*;
import common.status.NodeStatus;
import log.LogModule;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import node.Node;
import threadpool.RaftThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
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
            LogModule logModule;

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


    @Override
    public void init() throws Throwable {

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
                }, false);
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
                            lastLogIndex(LongConvert.convert(logModule.getLastIndex())).
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
}
