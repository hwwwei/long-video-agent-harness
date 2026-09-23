package io.github.hwwwei.video.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test void deniesUnapprovedAndRemoteMcpCalls() {
        assertFalse(ToolRegistry.allowed("critic", "transcript.read"));
        assertFalse(ToolRegistry.allowed("analysis", "shell.exec"));
        assertFalse(ToolRegistry.allowed("analysis", "mcp:external.read"));
        assertTrue(ToolRegistry.allowed("planner", "transcript.read"));
    }
}
