package org.hongxi.jaws.cluster.loadbalance;

import org.hongxi.jaws.cluster.LoadBalance;
import org.hongxi.jaws.common.util.JawsFrameworkUtils;
import org.hongxi.jaws.exception.JawsServiceException;
import org.hongxi.jaws.rpc.Reference;
import org.hongxi.jaws.rpc.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Created by shenhongxi on 2021/4/23.
 */
public abstract class AbstractLoadBalance<T> implements LoadBalance<T> {

    public static final int MAX_REFERENCE_COUNT = 10;
    private static final Logger log = LoggerFactory.getLogger(AbstractLoadBalance.class);
    private List<Reference<T>> references;

    @Override
    public void onRefresh(List<Reference<T>> references) {
        Collections.shuffle(references);
        // 只能引用替换，不能进行references update。
        this.references = references;
    }

    @Override
    public Reference<T> select(Request request) {
        List<Reference<T>> references = this.references;
        if (references == null) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call request:" + request);
        }
        Reference<T> ref = null;
        if (references.size() > 1) {
            ref = doSelect(request);

        } else if (references.size() == 1) {
            ref = references.get(0).isAvailable() ? references.get(0) : null;
        }

        if (ref != null) {
            return ref;
        }
        throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call request:" + request);
    }

    @Override
    public void selectToHolder(Request request, List<Reference<T>> refersHolder) {
        List<Reference<T>> references = this.references;

        if (references == null) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call : references_size= 0 "
                    + JawsFrameworkUtils.toString(request));
        }

        if (references.size() > 1) {
            doSelectToHolder(request, refersHolder);

        } else if (references.size() == 1 && references.get(0).isAvailable()) {
            refersHolder.add(references.get(0));
        }
        if (refersHolder.isEmpty()) {
            throw new JawsServiceException(this.getClass().getSimpleName() + " No available references for call : references_size="
                    + references.size() + " " + JawsFrameworkUtils.toString(request));
        }
    }

    protected List<Reference<T>> getReferences() {
        return references;
    }

    @Override
    public void setWeightString(String weightString) {
        log.info("ignore weightString: {}", weightString);
    }

    protected abstract Reference<T> doSelect(Request request);

    protected abstract void doSelectToHolder(Request request, List<Reference<T>> refersHolder);
}
