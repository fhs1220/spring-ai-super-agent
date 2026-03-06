package com.fhs.aiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.boot.CommandLineRunner;
/**
 * spring ai 框架调用ai LLM
 */
@Component
public class SpringAiAiInvoke implements CommandLineRunner{
    @Resource
    private ChatModel dashscopeChatModel;
    @Override
    public void run(String... args) throws Exception{
        AssistantMessage assistantMessage = dashscopeChatModel.call(new Prompt("你好我是fhs"))
                .getResult()
                .getOutput();
        System.out.println(assistantMessage.getText());
    }
}
