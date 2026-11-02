package com.feijimiao.xianyuassistant.controller.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品详情请求参数校验测试。
 */
class ItemDetailReqDTOTest {

    /**
     * 前端只传商品 ID 时，服务端应使用商品关联账号，不应被可选 cookieId 阻断。
     */
    @Test
    void acceptsRequestWithoutCookieId() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ItemDetailReqDTO request = new ItemDetailReqDTO();
        request.setXyGoodId("1044264630710");

        assertThat(validator.validate(request)).isEmpty();
    }

    /**
     * 商品 ID 仍是详情接口唯一必填字段。
     */
    @Test
    void rejectsRequestWithoutGoodsId() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ItemDetailReqDTO request = new ItemDetailReqDTO();

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("xyGoodId");
    }
}
