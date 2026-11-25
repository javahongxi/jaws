package org.hongxi.jaws.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SYNC_FLUSH via group commit: requests queue up behind a swap list, and one
 * fsync up to the batch's max offset satisfies every request at or below it.
 * The fsync cost is amortized across the whole batch — the reason sync flush
 * can still sustain high throughput.
 *
 * <p>Offsets are globally monotonic, so "flushed past my end" is a sufficient
 * durability proof for any earlier request; no per-request fsync is needed.
 */
public class GroupCommitService extends FlushService {

    private static class GroupCommitRequest {
        final long nextOffset;
        final CompletableFuture<Void> future = new CompletableFuture<>();

        GroupCommitRequest(long nextOffset) {
            this.nextOffset = nextOffset;
        }
    }

    private final int timeoutMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasRequests = lock.newCondition();
    private List<GroupCommitRequest> requestsWrite = new ArrayList<>();
    private List<GroupCommitRequest> requestsRead = new ArrayList<>();

    public GroupCommitService(CommitLog commitLog, int timeoutMillis) {
        super(commitLog, "StoreGroupCommit");
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public CompletableFuture<Void> submitFlush(long nextOffset) {
        GroupCommitRequest request = new GroupCommitRequest(nextOffset);
        lock.lock();
        try {
            requestsWrite.add(request);
            hasRequests.signal();
        } finally {
            lock.unlock();
        }
        return request.future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void runLoop() {
        while (!isStopped()) {
            lock.lock();
            try {
                while (requestsWrite.isEmpty() && !isStopped()) {
                    hasRequests.await(10, TimeUnit.MILLISECONDS);
                }
                List<GroupCommitRequest> tmp = requestsWrite;
                requestsWrite = requestsRead;
                requestsRead = tmp;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                lock.unlock();
            }
            if (requestsRead.isEmpty()) {
                continue;
            }
            long flushedOffset = commitLog.flush();
            for (GroupCommitRequest request : requestsRead) {
                if (request.nextOffset <= flushedOffset) {
                    request.future.complete(null);
                } else {
                    // flushed frontier moved backwards is impossible; requeue
                    // defensively so the request joins the next batch
                    lock.lock();
                    try {
                        requestsWrite.add(request);
                    } finally {
                        lock.unlock();
                    }
                }
            }
            requestsRead.clear();
        }
    }
}