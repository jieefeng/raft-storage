
package rpc.impl;


import changes.ClusterMembershipChanges;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.RpcServer;
import entity.AentryParam;
import entity.ClientKVReq;
import entity.Peer;
import entity.RvoteParam;
import lombok.extern.slf4j.Slf4j;
import node.impl.DefaultNode;
import rpc.Request;
import rpc.Response;
import rpc.RpcService;
import rpc.processor.RaftUserProcessor;

/**
 * Raft Server
 */
@Slf4j
public class DefaultRpcServiceImpl implements RpcService {

    private final DefaultNode node;

    private final RpcServer rpcServer;

    public DefaultRpcServiceImpl(int port, DefaultNode node) {
        rpcServer = new RpcServer(port, false, false);
        rpcServer.registerUserProcessor(new RaftUserProcessor<Request>() {

            @Override
            public Object handleRequest(BizContext bizCtx, Request request) {
                return handlerRequest(request);
            }
        });

        this.node = node;
    }


    @Override
    public Response<?> handlerRequest(Request request) {
        if (request.getCmd() == Request.R_VOTE) {
            return new Response<>(node.handlerRequestVote((RvoteParam) request.getObj()));
        } else if (request.getCmd() == Request.A_ENTRIES) {
            return new Response<>(node.handlerAppendEntries((AentryParam) request.getObj()));
        } else if (request.getCmd() == Request.CLIENT_REQ) {
            return new Response<>(node.handlerClientRequest((ClientKVReq) request.getObj()));
        } else if (request.getCmd() == Request.CHANGE_CONFIG_REMOVE) {
            return new Response<>(((ClusterMembershipChanges) node).removePeer((Peer) request.getObj()));
        } else if (request.getCmd() == Request.CHANGE_CONFIG_ADD) {
            return new Response<>(((ClusterMembershipChanges) node).addPeer((Peer) request.getObj()));
        }
        return null;
    }


    @Override
    public void init() {
        rpcServer.startup();
    }

    @Override
    public void destroy() {
        rpcServer.shutdown();
        log.info("destroy success");
    }
}
