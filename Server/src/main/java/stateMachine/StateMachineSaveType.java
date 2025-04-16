
package stateMachine;


import lombok.Getter;

/**
 *
 * 快照存储类型
 *
 * @author rensailong
 */
@Getter
public enum StateMachineSaveType {
    /** sy */
//    REDIS("redis", "redis存储", RedisStateMachine.getInstance()),
    ROCKS_DB("RocksDB", "RocksDB本地存储", DefaultStateMachine.getInstance())
    ;

    public StateMachine getStateMachine() {
        return this.stateMachine;
    }

    private String typeName;

    private String desc;

    private StateMachine stateMachine;

    StateMachineSaveType(String typeName, String desc, StateMachine stateMachine) {
        this.typeName = typeName;
        this.desc = desc;
        this.stateMachine = stateMachine;
    }

    public static StateMachineSaveType getForType(String typeName) {
        for (StateMachineSaveType value : values()) {
            if (value.getTypeName().equals(typeName)) {
                return value;
            }
        }

        return null;
    }

}
