package rpc.impl;

import com.alipay.remoting.exception.RemotingException;
import lombok.extern.slf4j.Slf4j;
import rpc.RaftRemotingException;
import rpc.Request;
import rpc.Response;
import rpc.RpcClient;

import java.util.concurrent.TimeUnit;

@Slf4j
public class DefaultRpcClient implements RpcClient {

    private static final com.alipay.remoting.rpc.RpcClient CLIENT = new com.alipay.remoting.rpc.RpcClient();

    @Override
    public <R> R send(Request request) {
        return send(request, (int) TimeUnit.SECONDS.toMillis(5));
    }

    @Override
    public <R> R send(Request request, int timeout) {
        try {
            @SuppressWarnings("unchecked")
            Response<R> response = (Response<R>) CLIENT.invokeSync(request.getUrl(), request, timeout);
            return response != null ? response.getResult() : null;
        } catch (RemotingException e) {
            throw new RaftRemotingException("Rpc send failed.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态
            return null;
        }
    }

    @Override
    public void init() {
        CLIENT.init();
        log.info("RpcClient initialized.");
    }

    @Override
    public void destroy() {
        CLIENT.shutdown();
        log.info("RpcClient destroyed.");
    }
}
