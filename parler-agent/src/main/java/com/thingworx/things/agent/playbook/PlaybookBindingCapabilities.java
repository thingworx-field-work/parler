package com.thingworx.things.agent.playbook;

/** Snapshot of Playbook expression binding support on the installed runtime. */
public final class PlaybookBindingCapabilities {

    private final boolean table;
    private final boolean infotable;
    private final boolean infotableForInvokeService;

    public PlaybookBindingCapabilities(boolean table, boolean infotable, boolean infotableForInvokeService) {
        this.table = table;
        this.infotable = infotable;
        this.infotableForInvokeService = infotableForInvokeService;
    }

    public static PlaybookBindingCapabilities v1() {
        return new PlaybookBindingCapabilities(true, true, false);
    }

    public boolean table() {
        return table;
    }

    public boolean infotable() {
        return infotable;
    }

    public boolean infotableForInvokeService() {
        return infotableForInvokeService;
    }
}
