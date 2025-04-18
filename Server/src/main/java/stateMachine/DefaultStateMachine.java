
package stateMachine;


import com.alibaba.fastjson2.JSON;
import entity.Command;
import entity.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.File;

/**
 * 默认的状态机实现.
 *
 * @author 莫那·鲁道
 */
@Slf4j
public class DefaultStateMachine implements StateMachine {

    /** public just for test */
    public String dbDir;
    public String stateMachineDir;

    public RocksDB machineDb;


    private DefaultStateMachine() {
        dbDir = "./rocksDB-raft/" + System.getProperty("serverPort");

        stateMachineDir = dbDir + "/stateMachine";
        RocksDB.loadLibrary();

        File file = new File(stateMachineDir);
        boolean success = false;

        if (!file.exists()) {
            success = file.mkdirs();
        }
        if (success) {
            log.warn("make a new dir : " + stateMachineDir);
        }
        Options options = new Options();
        options.setCreateIfMissing(true);
        try {
            machineDb = RocksDB.open(options, stateMachineDir);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public static DefaultStateMachine getInstance() {
        return DefaultStateMachineLazyHolder.INSTANCE;
    }

    @Override
    public void init() throws Throwable {

    }

    @Override
    public void destroy() throws Throwable {
        machineDb.close();
        log.info("destroy success");
    }

    private static class DefaultStateMachineLazyHolder {

        private static final DefaultStateMachine INSTANCE = new DefaultStateMachine();
    }

    @Override
    public LogEntry get(String key) {
        try {
            byte[] result = machineDb.get(key.getBytes());
            if (result == null) {
                return null;
            }
            return JSON.parseObject(result, LogEntry.class);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getString(String key) {
        try {
            byte[] bytes = machineDb.get(key.getBytes());
            if (bytes != null) {
                return new String(bytes);
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
        return "";
    }

    @Override
    public void setString(String key, String value) {
        try {
            machineDb.put(key.getBytes(), value.getBytes());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void delString(String... key) {
        try {
            for (String s : key) {
                machineDb.delete(s.getBytes());
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void apply(LogEntry logEntry) {

        try {
            Command command = logEntry.getCommand();

            if (command == null) {
                // 忽略空日志
                log.warn("insert no-op log, logEntry={}", logEntry);
                return;
            }
            String key = command.getKey();
            machineDb.put(key.getBytes(), JSON.toJSONBytes(logEntry));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

}
