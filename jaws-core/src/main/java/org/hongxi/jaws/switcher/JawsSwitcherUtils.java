package org.hongxi.jaws.switcher;

/**
 * 静态开关工具类。一般全局开关使用此类。 可以替换switcherService为不同实现
 *
 * Created by shenhongxi on 2021/4/25.
 */
public class JawsSwitcherUtils {
    private static SwitcherService switcherService = new LocalSwitcherService();

    public static void initSwitcher(String switcherName, boolean initialValue) {
        switcherService.initSwitcher(switcherName, initialValue);
    }

    /**
     * 检查开关是否开启。
     * 
     * @param switcherName
     * @return true ：设置了开关，并且开关值为true false：未设置开关或开关为false
     */
    public static boolean isOpen(String switcherName) {
        return switcherService.isOpen(switcherName);
    }

    /**
     * 检查开关是否开启，如果开关不存在则将开关置默认值，并返回。
     * 
     * @param switcherName
     * @param defaultValue
     * @return 开关存在时返回开关值，开关不存在时设置开关为默认值，并返回默认值。
     */
    public static boolean switcherIsOpenWithDefault(String switcherName, boolean defaultValue) {
        return switcherService.isOpen(switcherName, defaultValue);
    }

    /**
     * 设置开关状态。
     * 
     * @param switcherName
     * @param value
     * @return
     */
    public static void setSwitcherValue(String switcherName, boolean value) {
        switcherService.setValue(switcherName, value);
    }

    public static SwitcherService getSwitcherService() {
        return switcherService;
    }

    public static void setSwitcherService(SwitcherService switcherService) {
        JawsSwitcherUtils.switcherService = switcherService;
    }

    public static void registerSwitcherListener(String switcherName, SwitcherListener listener) {
        switcherService.registerListener(switcherName, listener);
    }
}