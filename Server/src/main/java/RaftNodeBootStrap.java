import entity.NodeConfig;
import io.grpc.netty.shaded.io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import node.Node;
import node.impl.DefaultNode;
import stateMachine.StateMachineSaveType;

import java.util.Arrays;

import static constant.Constant.*;

/**
 * -DserverPort=8775
 * -DserverPort=8776
 * -DserverPort=8777
 * -DserverPort=8778
 * -DserverPort=8779
 */
@Slf4j
public class RaftNodeBootStrap {

    public static final String[] DEFAULT_PROCESS = new String[]{"localhost:8775", "localhost:8776", "localhost:8777", "localhost:8778", "localhost:8779"};

    public static void main(String[] args) throws Throwable {
        boot();
    }
    
    public static void boot() throws Throwable {
        String property = System.getProperty(CLUSTER_ADDR_LIST);
        String[] peerAddr;

        if (StringUtil.isNullOrEmpty(property)) {
            peerAddr = DEFAULT_PROCESS;
        } else {
            peerAddr = property.split(SPLIT);
        }

        NodeConfig config = new NodeConfig();

        // 自身节点
        config.setSelfPort(Integer.parseInt(System.getProperty(SERVER_PORT, "8779")));

        // 其他节点地址
        config.setPeerAddrs(Arrays.asList(peerAddr));
        config.setStateMachineSaveType(StateMachineSaveType.ROCKS_DB.getTypeName());

        Node node = DefaultNode.getInstance();
        node.setConfig(config);

        node.init();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (node) {
                node.notifyAll();
            }
        }));

        log.info("gracefully wait");

        synchronized (node) {
            node.wait();
        }

        log.info("gracefully stop");
        node.destroy();
    }

}
