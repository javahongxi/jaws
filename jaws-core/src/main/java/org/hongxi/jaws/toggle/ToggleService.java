package org.hongxi.jaws.toggle;

import org.hongxi.jaws.common.extension.Scope;
import org.hongxi.jaws.common.extension.Spi;

import java.util.List;

/**
 * Created by shenhongxi on 2021/4/25.
 */
@Spi(scope = Scope.SINGLETON)
public interface ToggleService {
    /**
     * get toggle by name
     *
     * @param name
     * @return
     */
    Toggle getToggle(String name);

    /**
     * get all toggles
     *
     * @return
     */
    List<Toggle> getAllToggles();

    /**
     * init toggle with initial value.
     *
     * @param toggleName
     * @param initialValue
     */
    void initToggle(String toggleName, boolean initialValue);

    /**
     * check if toggle is open.
     *
     * @param toggleName
     * @return true: toggle exists and is on; false: toggle not set or is off
     */
    boolean isOpen(String toggleName);

    /**
     * check if toggle is open, set default value if toggle not exists.
     *
     * @param toggleName
     * @param defaultValue
     * @return current toggle value, or defaultValue if toggle was just created
     */
    boolean isOpen(String toggleName, boolean defaultValue);

    /**
     * set toggle value.
     *
     * @param toggleName
     * @param value
     */
    void setValue(String toggleName, boolean value);

    /**
     * register a listener for toggle value change, register a listener twice will only fire once
     *
     * @param toggleName
     * @param listener
     */
    void registerListener(String toggleName, ToggleListener listener);

    /**
     * unregister a listener
     *
     * @param toggleName
     * @param listener     the listener to be unregistered, null for all listeners for this toggleName
     */
    void unRegisterListener(String toggleName, ToggleListener listener);

}
