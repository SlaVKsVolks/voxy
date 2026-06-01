package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class UnifiedServiceThreadPool {
    public final ServiceManager serviceManager;
    public final MultiThreadPrioritySemaphore groupSemaphore;

    private final MultiThreadPrioritySemaphore.Block selfBlock;
    private final ThreadGroup dedicatedPool;
    private final List<Thread> threads = new ArrayList<>();
    private int threadId = 0;
    private int targetThreadCount = 0;
    private int retireRequests = 0;
    private boolean shutdownRequested = false;

    public UnifiedServiceThreadPool() {
        this.dedicatedPool = new ThreadGroup("Voxy Dedicated Service");
        this.serviceManager = new ServiceManager(this::release);
        this.groupSemaphore = new MultiThreadPrioritySemaphore(this.serviceManager::tryRunAJob);

        this.selfBlock = this.groupSemaphore.createBlock();
    }

    private final void release(int i) {
        this.groupSemaphore.pooledRelease(i);
        this.ensureTargetWorkerCount();
    }

    public boolean setNumThreads(int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("Thread count < 0");
        }
        synchronized (this.threads) {
            if (this.shutdownRequested) {
                throw new IllegalStateException("Cannot resize a shut down Voxy worker pool");
            }
            this.targetThreadCount = threads;
            int diff = threads - this.threads.size();
            if (diff==0) return false;//Already correct
            if (diff<0) {//Remove threads
                this.retireRequests += -diff;
                this.selfBlock.release(-diff);
            } else {//Add threads
                for (int i = 0; i < diff; i++) {
                    this.startWorkerLocked();
                }
            }
        }
        while (true) {
            synchronized (this.threads) {
                if (this.threads.size() == threads) return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void ensureTargetWorkerCount() {
        synchronized (this.threads) {
            if (this.shutdownRequested) {
                return;
            }
            int missing = this.targetThreadCount - this.threads.size();
            if (missing <= 0) {
                return;
            }
            Logger.warn("Voxy worker pool below target; respawning missing workers. target=", this.targetThreadCount,
                    " current=", this.threads.size(), " missing=", missing);
            for (int i = 0; i < missing; i++) {
                this.startWorkerLocked();
            }
        }
    }

    private void startWorkerLocked() {
        var t = new Thread(this.dedicatedPool, this::workerThread, "Dedicated Voxy Worker #" + (this.threadId++));
        t.setPriority(3);
        t.setDaemon(true);
        this.threads.add(t);
        t.start();
    }

    private void workerThread() {
        boolean removed = false;
        Throwable failure = null;
        try {
            while (true) {
                this.selfBlock.acquire();

                synchronized (this.threads) {
                    if (this.shutdownRequested || (this.retireRequests > 0 && this.threads.size() > this.targetThreadCount)) {
                        if (this.retireRequests > 0) {
                            this.retireRequests--;
                        }
                        Logger.info("Dedicated Voxy worker retiring: ", Thread.currentThread().getName(),
                                " remaining=", this.threads.size() - 1,
                                " target=", this.targetThreadCount,
                                " retire_requests=", this.retireRequests);
                        this.threads.remove(Thread.currentThread());
                        removed = true;
                        this.threads.notifyAll();
                        return;
                    }
                }
            }
        } catch (Throwable throwable) {
            failure = throwable;
            Logger.error("Dedicated Voxy worker failed: ", Thread.currentThread().getName(), throwable);
        } finally {
            if (!removed) {
                synchronized (this.threads) {
                    Logger.warn("Dedicated Voxy worker exited without an explicit retire request: ",
                            Thread.currentThread().getName(),
                            " remaining=", Math.max(0, this.threads.size() - 1),
                            " target=", this.targetThreadCount,
                            " retire_requests=", this.retireRequests,
                            " shutdown=", this.shutdownRequested,
                            " failure=", failure == null ? "none" : failure.getClass().getName());
                    this.threads.remove(Thread.currentThread());
                    this.threads.notifyAll();
                }
            }
        }
    }

    public void shutdown() {
        this.serviceManager.shutdown();
        synchronized (this.threads) {
            this.shutdownRequested = true;
            this.targetThreadCount = 0;
            this.retireRequests += this.threads.size();
            this.selfBlock.release(Math.max(1, this.threads.size()));
        }
        while (true) {
            synchronized (this.threads) {
                if (this.threads.isEmpty()) {
                    break;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        this.selfBlock.free();
    }

    public int getThreadCount() {
        synchronized (this.threads) {
            return this.threads.size();
        }
    }

    public int getTargetThreadCount() {
        synchronized (this.threads) {
            return this.targetThreadCount;
        }
    }

    public int getRetireRequests() {
        synchronized (this.threads) {
            return this.retireRequests;
        }
    }


    public static void main(String[] args) {
        var ustp = new UnifiedServiceThreadPool();

        AtomicInteger cc = new AtomicInteger();
        AtomicInteger cnt = new AtomicInteger();

        var s1 = ustp.serviceManager.createService(()->{
            AtomicBoolean cleaned = new AtomicBoolean();
            AtomicInteger a = new AtomicInteger();
            return new Pair<>(()->{
                if (cleaned.get()) {
                    System.err.println("TRIED EXECUTING CLEANED CTX");
                } else {
                    a.incrementAndGet();
                    cnt.incrementAndGet();
                }
            }, ()->{
                if (cleaned.getAndSet(true)) {
                    System.err.println("TRIED DOUBLE CLEANING A VALUE");
                } else {
                    System.out.println("Cleaned ref, exec: " + a.get());
                    cc.incrementAndGet();
                }
            });
        }, 1);

        for (int i = 0; i < 1000; i++) {
            s1.execute();
        }
        ustp.setNumThreads(1);
        ustp.setNumThreads(10);
        ustp.setNumThreads(0);
        ustp.setNumThreads(1);
        s1.blockTillEmpty();
        s1.shutdown();
        ustp.shutdown();
        System.out.println(cnt);
    }
}
