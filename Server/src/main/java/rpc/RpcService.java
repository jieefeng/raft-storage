package rpc;

import lifecycle.LifeCycle;

public interface RpcService extends LifeCycle {

    /**
     * 处理请求.
     * @param request 请求参数.
     * @return 返回值.
     */
    Response<?> handlerRequest(Request request);

}