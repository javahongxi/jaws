package org.hongxi.jaws.metadata;

import org.hongxi.jaws.rpc.URL;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provider-side in-memory metadata store, one entry per application — the data
 * source behind {@link MetadataService} in local metadata mode.
 * <p>
 * Each export/unexport publishes a new {@link MetadataInfo} with a recomputed
 * revision; the previous revision moves into a bounded LRU history so consumers
 * still holding an older instance snapshot can fetch their revision during a
 * rolling update. Nothing is persisted and nothing is reported to the registry:
 * when the process dies the metadata dies with it, and stale instances are
 * removed by the registry's own liveness mechanism.
 */
public final class ApplicationMetadataManager {

    /** Maximum number of historical revisions kept per application. */
    private static final int HISTORY_SIZE = 100;

    private static final ConcurrentMap<String, AppMetadata> APPS = new ConcurrentHashMap<>();

    private ApplicationMetadataManager() {
    }

    /** Per-application state; all reads/writes are guarded by the instance lock. */
    private static final class AppMetadata {
        MetadataInfo current;
        final Map<String, MetadataInfo> history = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Serial
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, MetadataInfo> eldest) {
                        return size() > HISTORY_SIZE;
                    }
                });
    }

    /**
     * Add (or replace) one service URL and publish the recomputed revision.
     *
     * @return the new current revision
     */
    public static String addServiceUrl(String application, URL serviceUrl) {
        AppMetadata app = APPS.computeIfAbsent(application, k -> new AppMetadata());
        synchronized (app) {
            MetadataInfo next = copyCurrent(app, application);
            next.addServiceUrl(serviceUrl);
            next.init();
            publish(app, next);
            return next.getRevision();
        }
    }

    /**
     * Remove one service URL and publish the recomputed revision.
     *
     * @return the new current revision, or null when the application has no
     *         services left (the caller should then deregister its instance)
     */
    public static String removeServiceUrl(String application, URL serviceUrl) {
        AppMetadata app = APPS.get(application);
        if (app == null) {
            return null;
        }
        synchronized (app) {
            if (app.current == null) {
                return null;
            }
            MetadataInfo next = copyCurrent(app, application);
            next.removeServiceUrl(serviceUrl);
            if (next.getServices().isEmpty()) {
                app.history.put(app.current.getRevision(), app.current);
                app.current = null;
                return null;
            }
            next.init();
            publish(app, next);
            return next.getRevision();
        }
    }

    /**
     * Pure in-memory lookup serving {@link MetadataService#getMetadataInfo(String)}:
     * current revision first, then the bounded history.
     */
    public static MetadataInfo getMetadataInfo(String application, String revision) {
        AppMetadata app = APPS.get(application);
        if (app == null || revision == null) {
            return null;
        }
        synchronized (app) {
            if (app.current != null && revision.equals(app.current.getRevision())) {
                return app.current;
            }
            return app.history.get(revision);
        }
    }

    /**
     * Whether the application currently exports at least one service.
     */
    public static boolean hasServices(String application) {
        AppMetadata app = APPS.get(application);
        if (app == null) {
            return false;
        }
        synchronized (app) {
            return app.current != null && !app.current.getServices().isEmpty();
        }
    }

    /** Drop all applications; for tests only. */
    public static void clear() {
        APPS.clear();
    }

    private static MetadataInfo copyCurrent(AppMetadata app, String application) {
        MetadataInfo next = new MetadataInfo(application);
        if (app.current != null) {
            next.getServices().putAll(app.current.getServices());
        }
        return next;
    }

    private static void publish(AppMetadata app, MetadataInfo next) {
        if (app.current != null) {
            app.history.put(app.current.getRevision(), app.current);
        }
        app.current = next;
    }
}