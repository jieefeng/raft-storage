
package rpc;


import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;


@Getter
@Setter
public class Response<T> implements Serializable {


    private T result;

    public Response(T result) {
        this.result = result;
    }

    public static Response<String> ok() {
        return new Response<>("ok");
    }

    public static Response<String> fail() {
        return new Response<>("fail");
    }

    @Override
    public String toString() {
        return "Response{" +
            "result=" + result +
            '}';
    }
}
