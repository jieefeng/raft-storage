
package changes;

import entity.LogEntry;
import entity.Peer;
import node.impl.DefaultNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rpc.Request;

/**
 * 集群配置变更接口默认实现.
 *
 * @author 莫那·鲁道
 */
public class ClusterMembershipChangesImpl implements ClusterMembershipChanges {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterMembershipChangesImpl.class);


    private final DefaultNode node;

    public ClusterMembershipChangesImpl(DefaultNode node) {
        this.node = node;
    }

    /**
     * 必须是同步的,一次只能添加一个节点
     *
     * @param newPeer
     */
    @Override
    public synchronized Result addPeer(Peer newPeer) {
        // 已经存在
        if (node.peerSet.getPeersWithOutSelf().contains(newPeer)) {
            return new Result();
        }

        node.peerSet.getPeersWithOutSelf().add(newPeer);

        if (node.status == common.status.NodeStatus.LEADER) {
            node.getNextIndexs().put(newPeer, 0L);
            node.getMatchIndexs().put(newPeer, 0L);

            for (long i = 0; i < node.logModule.getLastIndex(); i++) {
                LogEntry entry = node.logModule.read(i);
                if (entry != null) {
                    node.replication(newPeer, entry);
                }
            }

            for (Peer ignore : node.peerSet.getPeersWithOutSelf()) {
                // TODO 同步到其他节点.
                Request request = Request.builder()
                        .cmd(Request.CHANGE_CONFIG_ADD)
                        .url(newPeer.getAddr())
                        .obj(newPeer)
                        .build();

                Result result = node.rpcClient.send(request);
                if (result != null && result.getStatus() == Result.Status.SUCCESS.getCode()) {
                    LOGGER.info("replication config success, peer : {}, newServer : {}", newPeer, newPeer);
                } else {
                    LOGGER.warn("replication config fail, peer : {}, newServer : {}", newPeer, newPeer);
                }
            }

        }

        return new Result();
    }


    /**
     * 必须是同步的,一次只能删除一个节点
     *
     * @param oldPeer
     */
    @Override
    public synchronized Result removePeer(Peer oldPeer) {
        node.peerSet.getPeersWithOutSelf().remove(oldPeer);
        node.getNextIndexs().remove(oldPeer);
        node.getMatchIndexs().remove(oldPeer);

        return new Result();
    }
}
