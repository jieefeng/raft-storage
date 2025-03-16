package threadpool;

import java.util.concurrent.*;

public class RaftThreadPool {
    // 创建线程池，指定核心线程数、最大线程数和线程池的工作队列

    private static final int CPU = Runtime.getRuntime().availableProcessors();
    private static final int MAX_POOL_SIZE = CPU * 2;
    private static final int QUEUE_SIZE = 1024;
    private static final long KEEP_TIME = 1000 * 60;
    private static final TimeUnit KEEP_TIME_UNIT = TimeUnit.MILLISECONDS;
    private static final ExecutorService executorService = getThreadPoolExecutor();

    private static ScheduledExecutorService scheduledExecutorService = getScheduled();

    public static void execute(Runnable r) {
        executorService.execute(r);
    }

    public static <T> Future<T> submit(Callable r) {
        return executorService.submit(r);
    }


    private static ThreadPoolExecutor getThreadPoolExecutor() {
        return new ThreadPoolExecutor(
                CPU,
                MAX_POOL_SIZE,
                KEEP_TIME,
                KEEP_TIME_UNIT,
                new LinkedBlockingQueue<>(QUEUE_SIZE),
                new NameThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static ScheduledExecutorService getScheduled() {
        return new ScheduledThreadPoolExecutor(CPU, new NameThreadFactory());
    }

    static class NameThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new RaftThread("Raft thread", r);
            t.setDaemon(true);
            t.setPriority(5);
            return t;
        }
    }

}
