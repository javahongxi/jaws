package org.hongxi.jaws.toggle;

/**
 * Static toggle utility class. Typically used for global feature toggles.
 * The underlying ToggleService implementation can be replaced via {@link #setToggleService(ToggleService)}.
 * <p>
 * Created by shenhongxi on 2021/4/25.
 */
public class JawsToggleUtils {
    private static ToggleService toggleService = new LocalToggleService();

    public static void initToggle(String toggleName, boolean initialValue) {
        toggleService.initToggle(toggleName, initialValue);
    }

    /**
     * check if toggle is open.
     *
     * @param toggleName
     * @return true: toggle exists and is on; false: toggle not set or is off
     */
    public static boolean isOpen(String toggleName) {
        return toggleService.isOpen(toggleName);
    }

    /**
     * check if toggle is open, set default value if toggle not exists.
     *
     * @param toggleName
     * @param defaultValue
     * @return current toggle value, or defaultValue if toggle was just created
     */
    public static boolean isOpenWithDefault(String toggleName, boolean defaultValue) {
        return toggleService.isOpen(toggleName, defaultValue);
    }

    /**
     * set toggle value.
     *
     * @param toggleName
     * @param value
     */
    public static void setToggleValue(String toggleName, boolean value) {
        toggleService.setValue(toggleName, value);
    }

    public static ToggleService getToggleService() {
        return toggleService;
    }

    public static void setToggleService(ToggleService toggleService) {
        JawsToggleUtils.toggleService = toggleService;
    }

    public static void registerToggleListener(String toggleName, ToggleListener listener) {
        toggleService.registerListener(toggleName, listener);
    }
}
