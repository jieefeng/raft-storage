package entity;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 *
 * 请求投票 RPC 返回值.
 *
 */
@Getter
@Setter
public class RvoteResult implements Serializable {

    /**
     * ✅ 候选人更新 term 的意义：
     * 防止基于过期的 term 继续竞选，避免竞争冲突。
     * 保证整个集群的 term 一致，防止多个 Leader 产生。
     * 如果 term 过时，候选人必须立刻回到 Follower 状态，重新等待 Leader。
     */
    /** 当前任期号，以便于候选人去更新自己的任期 */
    long term;

    /** 候选人赢得了此张选票时为真 */
    boolean voteGranted;

    public RvoteResult(boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    private RvoteResult(Builder builder) {
        setTerm(builder.term);
        setVoteGranted(builder.voteGranted);
    }

    public static RvoteResult fail() {
        return new RvoteResult(false);
    }

    public static RvoteResult ok() {
        return new RvoteResult(true);
    }

    public static Builder newBuilder() {
        return new Builder();
    }


    public static final class Builder {

        private long term;
        private boolean voteGranted;

        private Builder() {
        }

        public Builder term(long term) {
            this.term = term;
            return this;
        }

        public Builder voteGranted(boolean voteGranted) {
            this.voteGranted = voteGranted;
            return this;
        }

        public RvoteResult build() {
            return new RvoteResult(this);
        }
    }
}
