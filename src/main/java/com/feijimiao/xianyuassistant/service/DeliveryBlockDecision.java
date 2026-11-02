package com.feijimiao.xianyuassistant.service;

public record DeliveryBlockDecision(boolean blocked, String ruleCode, String reason, String buyerMessage) {

    public static DeliveryBlockDecision allow() {
        return new DeliveryBlockDecision(false, null, null, null);
    }

    public static DeliveryBlockDecision block(String ruleCode, String reason, String buyerMessage) {
        return new DeliveryBlockDecision(true, ruleCode, reason, buyerMessage);
    }
}
