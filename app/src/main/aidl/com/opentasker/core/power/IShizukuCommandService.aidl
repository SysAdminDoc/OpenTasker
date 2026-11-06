package com.opentasker.core.power;

interface IShizukuCommandService {
    String execute(String actionId, in String[] argv, out int[] exitCode);
    int captureScreenshot(String actionId, String path);
    String runScript(String path, String expectedSha256, in String[] args, long timeoutMs, out int[] outcome, out String[] stderr);
    void destroy();
}
