package com.fhs.aiagent.app;

import com.fhs.aiagent.advisor.MyLoggerAdvisor;
import com.fhs.aiagent.advisor.ReReadingAdvisor;
import com.fhs.aiagent.rag.AgenticRagService;
import com.fhs.aiagent.rag.multiagent.MultiAgentRoutingMode;
import com.fhs.aiagent.rag.AgentProgressListener;
import com.fhs.aiagent.rag.AppRagCustomAdvisorFactory;
import com.fhs.aiagent.rag.QueryRewriter;
import com.fhs.aiagent.rl.model.AgenticRagResult;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import com.fhs.aiagent.ChatMemory.FileBasedChatMemory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.List;
@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    public LoveApp(ChatModel dashscopeChatModel) {
        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor()
//                        new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * AI 报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport: {}", loveReport);
        return loveReport;
    }

    // AI 知识库问答功能
    @Resource
    private VectorStore loveAppVectorStore;
    @Resource
    private VectorStore pgVectorVectorStore;
    @Resource
    private Advisor appRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;
    /**
     * 和 RAG 知识库进行对话（默认使用 Agentic RAG）。
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 最终答案
     */
    public String doChatWithRag(String message, String chatId) {
        return doChatWithAgenticRag(message, chatId);
    }

    /**
     * 传统单次检索 RAG，仅保留用于效果对比和回归。
     */
    public String doChatWithTraditionalRag(String message, String chatId) {
        //查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                //应用RAG知识库问答
                .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(appRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))

                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        AppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(
//                                loveAppVectorStore, "已婚"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // Agentic RAG：规划 -> 检索 -> 验证 -> 追问 -> 修正
    @Resource
    private AgenticRagService agenticRagService;

    /**
     * 和 RAG 知识库进行对话（Agentic RAG 版本）
     * <p>
     * 流程：规划(拆解子查询) -> 检索(向量检索去重合并) -> 验证(评估上下文充分性)
     * -> 追问(不充分时补充检索，迭代) -> 修正(答案忠实性审查与重写)
     *
     * @param message 用户消息
     * @param chatId  会话 ID
     * @return 最终答案
     */
    public String doChatWithAgenticRag(String message, String chatId) {
        return doChatWithAgenticRagTrace(message, chatId).answer();
    }

    public AgenticRagResult doChatWithAgenticRagTrace(String message, String chatId) {
        AgenticRagResult result = agenticRagService.doAgenticRagWithTrace(message, chatId, SYSTEM_PROMPT);
        log.info("agentic rag content: {}, trajectoryId: {}", result.answer(), result.trajectoryId());
        return result;
    }

    public AgenticRagResult doChatWithAgenticRagTrace(String message,
                                                      String chatId,
                                                      AgentProgressListener progressListener) {
        AgenticRagResult result = agenticRagService.doAgenticRagWithTrace(
                message, chatId, SYSTEM_PROMPT, progressListener);
        log.info("streaming agentic rag content: {}, trajectoryId: {}",
                result.answer(), result.trajectoryId());
        return result;
    }

    /**
     * 执行不写入会话记忆和训练轨迹的隔离基准评测。
     */
    public AgenticRagResult doChatWithAgenticRagEvaluation(
            String message,
            String chatId,
            MultiAgentRoutingMode routingMode) {
        return agenticRagService.doAgenticRagWithTrace(
                message,
                chatId,
                SYSTEM_PROMPT,
                AgentProgressListener.NONE,
                AgenticRagService.RunOptions.evaluation(routingMode)
        );
    }

    // AI 调用工具能力
    @Resource
    private ToolCallback[] allTools;

    /**
     * AI 恋爱报告功能（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    // AI 调用 MCP 服务

    @Autowired
    private ObjectProvider<ToolCallbackProvider> toolCallbackProviderProvider;

    /**
     * AI 报告功能（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ToolCallbackProvider toolCallbackProvider = toolCallbackProviderProvider.getIfAvailable();
        if (toolCallbackProvider == null) {
            throw new IllegalStateException("MCP client is disabled or unavailable");
        }
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
