package com.droidx;

public final class BuildResult {
    public final boolean ok;
    public final int exitCode;
    public final String output;

    public BuildResult(boolean ok, int exitCode, String output) {
        this.ok = ok;
        this.exitCode = exitCode;
        this.output = output;
    }
}
