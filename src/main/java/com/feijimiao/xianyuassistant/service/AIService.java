package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.service.bo.RAGDataRespBO;
import com.feijimiao.xianyuassistant.service.bo.RAGReplyResult;
import org.antlr.v4.runtime.TokenStream;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @author IAMLZY
 * @date 2026/4/10 22:26
 * @description
 */

public interface AIService {

    Flux<String> chatByRAG(String msg,String goodsId);

    /**
     * RAG聊天并返回命中资料详情
     */
    RAGReplyResult chatByRAGWithDetails(String msg, String goodsId);

    /**
     * RAG聊天并返回命中资料详情（携带会话上下文）
     */
    RAGReplyResult chatByRAGWithDetails(String msg, String goodsId, String contextMessages);

    /**
     * RAG聊天并返回命中资料详情（携带固定资料和商品详情）
     */
    RAGReplyResult chatByRAGWithFixedMaterial(String msg, String goodsId, String fixedMaterial, String goodsDetail);

    /**
     * RAG聊天（流式）- 携带固定资料和商品详情
     */
    reactor.core.publisher.Flux<String> chatByRAGWithFixedMaterialStream(String msg, String goodsId, String fixedMaterial, String goodsDetail);

    /**
     * RAG聊天并返回命中资料详情（携带会话上下文、固定资料和商品详情）
     */
    RAGReplyResult chatByRAGWithFixedMaterial(String msg, String goodsId, String contextMessages, String fixedMaterial, String goodsDetail);

    /**
     * RAG聊天并返回命中资料详情（额外追加系统提示词段，用于议价等策略注入）
     */
    RAGReplyResult chatByRAGWithPolicy(String msg, String goodsId, String fixedMaterial, String goodsDetail, String extraSystemPrompt);

    void putDataToRAG(String content,String goodsId);

    List<RAGDataRespBO> queryRAGDataBygoodsId(String goodsId);

    void deleteRAGDataByDocumentId(String documentId);

    /**
     * 润色自动发货文案，不使用客服系统提示词或 RAG 上下文。
     */
    String polishDeliveryContent(String content);

    String simpleChat(String message);
}
