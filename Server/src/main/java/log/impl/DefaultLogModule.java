package log.impl;

import com.alibaba.fastjson2.JSON;
import entity.LogEntry;
import log.LogModule;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
public class DefaultLogModule implements LogModule {

    /** public just for test */
    public String dbDir;
    public String logsDir;

    private RocksDB logDb;

    public final static byte[] LAST_INDEX_KEY = "LAST_INDEX_KEY".getBytes();
    private ReentrantLock lock = new ReentrantLock();

    // volatile 保证可见性和禁止指令重排序
    private static volatile DefaultLogModule instance;

    private DefaultLogModule() {
        if (dbDir == null) {
            dbDir = "./rocksDB-raft/" + System.getProperty("serverPort");
        }
        if (logsDir == null) {
            logsDir = dbDir + "/logModule";
        }
        RocksDB.loadLibrary();
        Options options = new Options();
        options.setCreateIfMissing(true);

        File file = new File(logsDir);
        boolean success = false;
        if (!file.exists()) {
            success = file.mkdirs();
        }
        if (success) {
            log.warn("make a new dir : " + logsDir);
        }
        try {
            logDb = RocksDB.open(options, logsDir);
        } catch (RocksDBException e) {
            log.warn(e.getMessage());
        }
    }

    @Override
    public void init() throws Throwable {

    }

    @Override
    public void destroy() throws Throwable {
        logDb.close();
        log.info("destroy success");
    }

    @Override
    public void write(LogEntry logEntry) {
        boolean success = false;
        boolean result;
        try {
            result = lock.tryLock(3000, MILLISECONDS);
            if (!result) {
                throw new RuntimeException("write fail, tryLock fail.");
            }
            logEntry.setIndex(getLastIndex() + 1);
            logDb.put(logEntry.getIndex().toString().getBytes(), JSON.toJSONBytes(logEntry));
            success = true;
            log.info("DefaultLogModule write rocksDB success, logEntry info : [{}]", logEntry);
        } catch (RocksDBException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (success) {
                updateLastIndex(logEntry.getIndex());
            }
            lock.unlock();
        }
    }

    @Override
    public LogEntry read(Long index) {
        try {
            byte[] result = logDb.get(convert(index));
            if (result == null) {
                return null;
            }
            return JSON.parseObject(result, LogEntry.class);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] convert(Long key) {
        return key.toString().getBytes();
    }

    @Override
    public void removeOnStartIndex(Long startIndex) {
        boolean success = false;
        int count = 0;
        boolean tryLock;
        try {
            tryLock = lock.tryLock(3000, MILLISECONDS);
            if (!tryLock) {
                throw new RuntimeException("tryLock fail, removeOnStartIndex fail");
            }
            for (long i = startIndex; i <= getLastIndex(); i++) {
                logDb.delete(String.valueOf(i).getBytes());
                ++count;
            }
            success = true;
            log.warn("rocksDB removeOnStartIndex success, count={} startIndex={}, lastIndex={}", count, startIndex, getLastIndex());
        } catch (InterruptedException | RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            if (success) {
                updateLastIndex(getLastIndex() - count);
            }
            lock.unlock();
        }
    }

    @Override
    public LogEntry getLast() {
        try {
            byte[] result = logDb.get(convert(getLastIndex()));
            if (result == null) {
                return null;
            }
            return JSON.parseObject(result, LogEntry.class);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Long getLastIndex() {
        byte[] lastIndex;
        try {
            lastIndex = logDb.get(LAST_INDEX_KEY);
            if (lastIndex == null) {
                lastIndex = "0".getBytes();
            }
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
        return Long.valueOf(new String(lastIndex));
    }

    private void updateLastIndex(Long index) {
        try {
            // overWrite
            logDb.put(LAST_INDEX_KEY, index.toString().getBytes());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public static DefaultLogModule getInstance() {
        if(instance == null){
            synchronized (DefaultLogModule.class){
                if(instance == null){
                    instance = new DefaultLogModule();
                }
            }
        }
        return  instance;
    }
}
