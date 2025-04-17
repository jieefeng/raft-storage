package changes;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class Result {

    public static final int FAIL = 0;
    public static final int SUCCESS = 1;

    int status;

    String leaderHint;

    public Result() {
    }

    @Override
    public String toString() {
        return "Result{" +
            "status=" + status +
            ", leaderHint='" + leaderHint + '\'' +
            '}';
    }
    @Getter
    public enum Status {
        FAIL(0), SUCCESS(1);

        int code;

        Status(int code) {
            this.code = code;
        }

        public static Status value(int v) {
            for (Status i : values()) {
                if (i.code == v) {
                    return i;
                }
            }
            return null;
        }
    }
}
