package com.springragforge.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

//注册mcpToolProvider bean然后在AiService中配置使用mcp
//@Configuration
public class McpFilesystemConfig {

    private static final String FILESYSTEM_DIR = "E:\\dataaa";

    /**
     * Windows 上 npx 是 npx.cmd,ProcessBuilder 无法直接执行,必须用 cmd /c 包装。
     * @return McpClient mcp客户端实例，通信底层使用标准输入输出(stdio)传输MCP协议报文
     */
    @Bean(destroyMethod = "close")
    public McpClient filesystemMcpClient() {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("cmd", "/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", FILESYSTEM_DIR))
                // 开启事件日志，便于调试MCP协议交互报文
                .logEvents(true)
                .build();
        // 创建MCP客户端，绑定传输层，设置客户端唯一标识key
        return DefaultMcpClient.builder()
                .transport(transport)
                .key("filesystem")
                .build();
    }

    /**
     * 时间工具
     */
    @Bean(destroyMethod = "close")
    public McpClient timeMcpClient() {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("cmd", "/c", "npx", "-y", "@mcpcentral/mcp-time"))
                // 开启事件日志，便于调试MCP协议交互报文
                .logEvents(true)
                .build();
        // 创建MCP客户端，绑定传输层，设置客户端唯一标识key
        return DefaultMcpClient.builder()
                .transport(transport)
                .key("time")
                .build();
    }

    /**
     * 地图工具
     */
    @Bean(destroyMethod = "close")
    public McpClient mapMcpClient() {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("cmd", "/c", "npx", "-y", "@baidumap/mcp-server-baidu-map"))
                .environment(Map.of("BAIDU_MAP_API_KEY", "J5XSa0EMUX5shGjIDWEepbV3Zjw6x7ja"))
                // 开启事件日志，便于调试MCP协议交互报文
                .logEvents(true)
                .build();
        // 创建MCP客户端，绑定传输层，设置客户端唯一标识key
        return DefaultMcpClient.builder()
                .transport(transport)
                .key("map")
                .build();
    }

    /**
     * 将McpClient注册进McpToolProvider，LangChain4j会自动发现该客户端暴露的全部工具
     */
    @Bean
    public McpToolProvider mcpToolProvider(McpClient filesystemMcpClient, McpClient timeMcpClient, McpClient mapMcpClient) {
        return McpToolProvider.builder()
                .mcpClients(filesystemMcpClient, timeMcpClient, mapMcpClient)
                .build();
    }
}
