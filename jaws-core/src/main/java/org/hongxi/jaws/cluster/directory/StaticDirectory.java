package org.hongxi.jaws.cluster.directory;

import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.URL;

import java.util.List;

/**
 * A static {@link org.hongxi.jaws.cluster.Directory} that holds a fixed list of references.
 * <p>
 * Used for direct connection scenarios where no registry is involved.
 *
 * @param <T> service type
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {

    public StaticDirectory(URL consumerUrl, List<Reference<T>> references) {
        super(consumerUrl);
        setReferences(references);
    }

    @Override
    public void init() {
        // no-op: references are already set in constructor
    }

    @Override
    public void destroy() {
        List<Reference<T>> refs = getReferences();
        if (refs != null) {
            for (Reference<T> ref : refs) {
                ref.destroy();
            }
        }
    }
}
