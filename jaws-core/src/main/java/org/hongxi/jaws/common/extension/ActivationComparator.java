package org.hongxi.jaws.common.extension;

import java.util.Comparator;

/**
 * Compares SPI extensions annotated with {@link Activation} by their
 * {@link Activation#order()} value, similar to Dubbo's {@code @Activate}
 * ordering.
 * <p>
 * Instances without the annotation are sorted last (treated as lowest
 * priority), which keeps conditionally-activated extensions ahead of
 * unannotated ones.
 * <p>
 * Created by shenhongxi on 2020/7/25.
 */
public class ActivationComparator<T> implements Comparator<T> {

    @Override
    public int compare(T o1, T o2) {
        Activation p1 = o1.getClass().getAnnotation(Activation.class);
        Activation p2 = o2.getClass().getAnnotation(Activation.class);
        if (p1 == null) {
            return 1;
        } else if (p2 == null) {
            return -1;
        } else {
            return p1.order() - p2.order();
        }
    }
}
