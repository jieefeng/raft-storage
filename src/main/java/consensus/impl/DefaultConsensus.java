package consensus.impl;



import common.entity.*;
import consensus.Consensus;
import common.status.NodeStatus;
import lombok.Getter;
import lombok.Setter;
import node.impl.DefaultNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 * 默认的一致性模块实现.
 *
 * @author 莫那·鲁道
 */
@Setter
@Getter
public class DefaultConsensus implements Consensus {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConsensus.class);


    public final DefaultNode node;

    public final ReentrantLock voteLock = new ReentrantLock();
    public final ReentrantLock appendLock = new ReentrantLock();

    public DefaultConsensus(DefaultNode node) {
        this.node = node;
    }

    /**
     * 请求投票 RPC
     *
     * 接收者实现：
     *      如果term < currentTerm返回 false （5.2 节）
     *      如果 votedFor 为空或者就是 candidateId，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）
     */
    @Override
    public RvoteResult requestVote(RvoteParam param) {
        try {
            RvoteResult.Builder builder = RvoteResult.newBuilder();
            if (!voteLock.tryLock()) {
                return builder.term(node.getCurrentTerm()).voteGranted(false).build();
            }

            // 1. 如果对方的任期小于当前节点的任期，拒绝投票
            if (param.getTerm() < node.getCurrentTerm()) {
                return builder.term(node.getCurrentTerm()).voteGranted(false).build();
            }

            // 2. 如果请求的任期比当前节点新，更新当前节点的任期，并重置 `votedFor`
            if (param.getTerm() > node.getCurrentTerm()) {
                node.setCurrentTerm(param.getTerm());
                node.setVotedFor(null);  // 任期变了，清空之前的投票记录
                node.status = NodeStatus.FOLLOWER;  // 切换为 FOLLOWER
            }

            LOGGER.info("node {} current vote for [{}], param candidateId : {}",
                    node.peerSet.getSelf(),
                    node.getVotedFor(),
                    param.getCandidateId());
            LOGGER.info("node {} current term {}, peer term : {}",
                    node.peerSet.getSelf(),
                    node.getCurrentTerm(),
                    param.getTerm());

            // 3. 检查是否可以投票（必须未投票 或 已经投票给相同候选人）
            if (node.getVotedFor() == null || node.getVotedFor().equals(param.getCandidateId())) {

                // 4. 检查候选人日志是否至少和自己一样新
                LogEntry lastLog = node.getLogModule().getLast();
                if (lastLog != null) {
                    // 4.1 如果对方日志的 `term` 旧于当前节点的日志，拒绝投票
                    if (lastLog.getTerm() > param.getLastLogTerm()) {
                        return builder.term(node.getCurrentTerm()).voteGranted(false).build();
                    }
                    // 4.2 如果 `term` 相同，但 `index` 比自己短，拒绝投票
                    if (lastLog.getTerm() == param.getLastLogTerm() && lastLog.getIndex() > param.getLastLogIndex()) {
                        return builder.term(node.getCurrentTerm()).voteGranted(false).build();
                    }
                }

                // 5. 记录投票
                node.setVotedFor(param.getCandidateId());
                node.setCurrentTerm(param.getTerm());  // 可能已经在第 2 步更新过
                node.status = NodeStatus.FOLLOWER;
                node.peerSet.setLeader(new Peer(param.getCandidateId()));

                return builder.term(node.getCurrentTerm()).voteGranted(true).build();
            }

            return builder.term(node.getCurrentTerm()).voteGranted(false).build();

        } finally {
            voteLock.unlock();
        }
    }



    /**
     * 附加日志(多个日志,为了提高效率) RPC
     *
     * 接收者实现：
     *    如果 term < currentTerm 就返回 false （5.1 节）
     *    如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false （5.3 节）
     *    如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的 （5.3 节）
     *    附加任何在已有的日志中不存在的条目
     *    如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
     */
    @Override
    public AentryResult appendEntries(AentryParam param) {
        AentryResult result = AentryResult.fail();
        try {
            if (!appendLock.tryLock()) {
                return result;
            }

            result.setTerm(node.getCurrentTerm());
            // 不够格
            if (param.getTerm() < node.getCurrentTerm()) {
                return result;
            }

            node.preHeartBeatTime = System.currentTimeMillis();
            node.preElectionTime = System.currentTimeMillis();
            node.peerSet.setLeader(new Peer(param.getLeaderId()));

            // 够格
            if (param.getTerm() >= node.getCurrentTerm()) {
                LOGGER.debug("node {} become FOLLOWER, currentTerm : {}, param Term : {}, param serverId = {}",
                    node.peerSet.getSelf(), node.currentTerm, param.getTerm(), param.getServerId());
                // 认怂
                node.status = NodeStatus.FOLLOWER;
            }
            // 使用对方的 term.
            node.setCurrentTerm(param.getTerm());

            //心跳
            if (param.getEntries() == null || param.getEntries().length == 0) {
                return handlerHeartBeatTime(param);
            }

            // 真实日志
            // 第一次
            if (node.getLogModule().getLastIndex() != 0 && param.getPrevLogIndex() != 0) {
                LogEntry logEntry;
                if ((logEntry = node.getLogModule().read(param.getPrevLogIndex())) != null) {
                    // 如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false
                    // 需要减小 nextIndex 重试.
                    if (logEntry.getTerm() != param.getPreLogTerm()) {
                        return result;
                    }
                } else {
                    // index 不对, 需要递减 nextIndex 重试.
                    return result;
                }

            }

            // 如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的
            LogEntry existLog = node.getLogModule().read(((param.getPrevLogIndex() + 1)));
            if (existLog != null && existLog.getTerm() != param.getEntries()[0].getTerm()) {
                // 删除这一条和之后所有的, 然后写入日志和状态机.
                node.getLogModule().removeOnStartIndex(param.getPrevLogIndex() + 1);
            } else if (existLog != null) {
                // 已经有日志了, 不能重复写入.
                result.setSuccess(true);
                return result;
            }

            // 写进日志
            for (LogEntry entry : param.getEntries()) {
                node.getLogModule().write(entry);
                result.setSuccess(true);
            }

            // 下一个需要提交的日志的索引（如有）
            long nextCommit = node.getCommitIndex() + 1;

            //如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
            if (param.getLeaderCommit() > node.getCommitIndex()) {
                int commitIndex = (int) Math.min(param.getLeaderCommit(), node.getLogModule().getLastIndex());
                node.setCommitIndex(commitIndex);
                node.setLastApplied(commitIndex);
            }

            while (nextCommit <= node.getCommitIndex()){
                // 提交之前的日志
                node.stateMachine.apply(node.logModule.read(nextCommit));
                nextCommit++;
            }

            result.setTerm(node.getCurrentTerm());

            node.status = NodeStatus.FOLLOWER;
            // TODO, 是否应当在成功回复之后, 才正式提交? 防止 leader "等待回复"过程中 挂掉.
            return result;
        } finally {
            appendLock.unlock();
        }
    }

    private AentryResult handlerHeartBeatTime(AentryParam param) {
        LOGGER.info("node {} append heartbeat success , he's term : {}, my term : {}",
            param.getLeaderId(), param.getTerm(), node.getCurrentTerm());

        // 处理 leader 已提交但未应用到状态机的日志

        // 下一个需要提交的日志的索引（如有）
        long nextCommit = node.getCommitIndex() + 1;

        //如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
        if (param.getLeaderCommit() > node.getCommitIndex()) {
            int commitIndex = (int) Math.min(param.getLeaderCommit(), node.getLogModule().getLastIndex());
            node.setCommitIndex(commitIndex);
            node.setLastApplied(commitIndex);
        }

        while (nextCommit <= node.getCommitIndex()){
            // 提交之前的日志
            node.stateMachine.apply(node.logModule.read(nextCommit));
            nextCommit++;
        }
        return AentryResult.newBuilder().term(node.getCurrentTerm()).success(true).build();
    }

}
