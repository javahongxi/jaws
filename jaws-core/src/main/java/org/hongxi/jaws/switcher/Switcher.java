package org.hongxi.jaws.switcher;

/**
 * Created by shenhongxi on 2021/4/25.
 */
public class Switcher {
    private boolean on = true;
    private String name; // 开关名

    public Switcher(String name, boolean on) {
        this.name = name;
        this.on = on;
    }

    public String getName() {
        return name;
    }

    /**
     * isOn: true，服务可用; isOn: false, 服务不可用
     * 
     * @return
     */
    public boolean isOn() {
        return on;
    }

    /**
     * turn on switcher
     */
    public void onSwitcher() {
        this.on = true;
    }

    /**
     * turn off switcher
     */
    public void offSwitcher() {
        this.on = false;
    }
}