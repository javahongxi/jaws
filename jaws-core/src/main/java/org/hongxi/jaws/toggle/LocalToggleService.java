package org.hongxi.jaws.toggle;

import org.hongxi.jaws.common.extension.SpiMeta;
import org.hongxi.jaws.exception.JawsFrameworkException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SpiMeta(name = "localToggleService")
public class LocalToggleService implements ToggleService {

    private static final ConcurrentMap<String, Toggle> toggles = new ConcurrentHashMap<>();

    private ConcurrentHashMap<String, List<ToggleListener>> listenerMap = new ConcurrentHashMap<>();

    public static Toggle getToggleStatic(String name) {
        return toggles.get(name);
    }

    public static void putToggle(Toggle toggle) {
        if (toggle == null) {
            throw new JawsFrameworkException("LocalToggleService putToggle Error: toggle is null");
        }

        toggles.put(toggle.getName(), toggle);
    }

    @Override
    public Toggle getToggle(String name) {
        return toggles.get(name);
    }

    @Override
    public List<Toggle> getAllToggles() {
        return new ArrayList<>(toggles.values());
    }

    @Override
    public void initToggle(String toggleName, boolean initialValue) {
        setValue(toggleName, initialValue);
    }

    @Override
    public boolean isOpen(String toggleName) {
        Toggle toggle = toggles.get(toggleName);
        return toggle != null && toggle.isOn();
    }

    @Override
    public boolean isOpen(String toggleName, boolean defaultValue) {
        Toggle toggle = toggles.get(toggleName);
        if (toggle == null) {
            toggles.putIfAbsent(toggleName, new Toggle(toggleName, defaultValue));
            toggle = toggles.get(toggleName);
        }
        return toggle.isOn();
    }

    @Override
    public void setValue(String toggleName, boolean value) {
        putToggle(new Toggle(toggleName, value));

        List<ToggleListener> listeners = listenerMap.get(toggleName);
        if (listeners != null) {
            for (ToggleListener listener : listeners) {
                listener.onValueChanged(toggleName, value);
            }
        }
    }

    @Override
    public void registerListener(String toggleName, ToggleListener listener) {
        List<ToggleListener> listeners = Collections.synchronizedList(new ArrayList<>());
        List<ToggleListener> preListeners = listenerMap.putIfAbsent(toggleName, listeners);
        if (preListeners == null) {
            listeners.add(listener);
        } else {
            preListeners.add(listener);
        }
    }

    @Override
    public void unRegisterListener(String toggleName, ToggleListener listener) {
        List<ToggleListener> listeners = listenerMap.get(toggleName);
        if (listener == null) {
            // keep empty listeners
            listeners.clear();
        } else {
            listeners.remove(listener);
        }
    }
}
