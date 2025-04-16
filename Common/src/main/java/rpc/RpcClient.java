package rpc;

public interface RpcClient {

    <R> R send(Request request);

    <R> R send(Request request, int timeout);

    void init();

    void destroy();
}
