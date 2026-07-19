package org.hongxi.jaws.toggle;

/**
 * A binary on/off toggle for runtime feature control.
 *
 * Created by shenhongxi on 2021/4/25.
 */
public class Toggle {
    private boolean on = true;
    // toggle name
    private String name;

    public Toggle(String name, boolean on) {
        this.name = name;
        this.on = on;
    }

    public String getName() {
        return name;
    }

    /**
     * isOn: true, feature enabled; isOn: false, feature disabled
     *
     * @return
     */
    public boolean isOn() {
        return on;
    }

    /**
     * turn on toggle
     */
    public void onToggle() {
        this.on = true;
    }

    /**
     * turn off toggle
     */
    public void offToggle() {
        this.on = false;
    }
}
