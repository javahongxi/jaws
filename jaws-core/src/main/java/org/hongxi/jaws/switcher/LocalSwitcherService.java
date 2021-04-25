package org.hongxi.jaws.switcher;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsFrameworkException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SpiMeta(name = "localSwitcherService")
public class LocalSwitcherService implements SwitcherService {

    private static ConcurrentMap<String, Switcher> switchers = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, List<SwitcherListener>> listenerMap = new ConcurrentHashMap<>();

    public static Switcher getSwitcherStatic(String name) {
        return switchers.get(name);
    }

    @Override
    public Switcher getSwitcher(String name) {
        return switchers.get(name);
    }

    @Override
    public List<Switcher> getAllSwitchers() {
        return new ArrayList<>(switchers.values());
    }

    public static void putSwitcher(Switcher switcher) {
        if (switcher == null) {
            throw new JawsFrameworkException("LocalSwitcherService addSwitcher Error: switcher is null");
        }

        switchers.put(switcher.getName(), switcher);
    }

    @Override
    public void initSwitcher(String switcherName, boolean initialValue) {
        setValue(switcherName, initialValue);
    }

    @Override
    public boolean isOpen(String switcherName) {
        Switcher switcher = switchers.get(switcherName);
        return switcher != null && switcher.isOn();
    }

    @Override
    public boolean isOpen(String switcherName, boolean defaultValue) {
        Switcher switcher = switchers.get(switcherName);
        if (switcher == null) {
            switchers.putIfAbsent(switcherName, new Switcher(switcherName, defaultValue));
            switcher = switchers.get(switcherName);
        }
        return switcher.isOn();
    }

    @Override
    public void setValue(String switcherName, boolean value) {
        putSwitcher(new Switcher(switcherName, value));

        List<SwitcherListener> listeners = listenerMap.get(switcherName);
        if (listeners != null) {
            for (SwitcherListener listener : listeners) {
                listener.onValueChanged(switcherName, value);
            }
        }
    }

    @Override
    public void registerListener(String switcherName, SwitcherListener listener) {
        List<SwitcherListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<SwitcherListener> preListeners= listenerMap.putIfAbsent(switcherName, listeners);
        if (preListeners == null) {
            listeners.add(listener);
        } else {
            preListeners.add(listener);
        }
    }

    @Override
    public void unRegisterListener(String switcherName, SwitcherListener listener) {
            List<SwitcherListener> listeners = listenerMap.get(switcherName);
            if (listener == null) {
                // keep empty listeners
                listeners.clear();
            } else {
                listeners.remove(listener);
            }
    }
}