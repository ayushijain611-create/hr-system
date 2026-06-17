package com.hrapp.hrmcpserver.config;

import com.hrapp.hrmcpserver.tools.EmployeeTools;
import com.hrapp.hrmcpserver.tools.EvaluationTools;
import com.hrapp.hrmcpserver.tools.LeaveTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single entry point that exposes every {@code @Tool}-annotated method
 * on the three tool beans to the Spring AI MCP server.
 *
 * We expose tools as a {@code List<ToolCallback>} (the pattern used by
 * the official spring-ai-examples weather server) rather than a
 * {@code ToolCallbackProvider}. Functionally equivalent for tool
 * registration, but the {@code List<ToolCallback>} path avoids a layer
 * of provider indirection inside the auto-config pipeline that has
 * caused tool dispatch to silently no-op in some 1.1.x setups.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    List<ToolCallback> hrToolCallbacks(
            EmployeeTools employeeTools,
            LeaveTools leaveTools,
            EvaluationTools evaluationTools) {
        List<ToolCallback> all = new ArrayList<>();
        Collections.addAll(all, ToolCallbacks.from(employeeTools));
        Collections.addAll(all, ToolCallbacks.from(leaveTools));
        Collections.addAll(all, ToolCallbacks.from(evaluationTools));
        return all;
    }
}
