package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.AutoDeliveryRecordRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionDTO;
import com.feijimiao.xianyuassistant.controller.dto.MsgSessionRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.TodoRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.review.entity.ReviewRecord;
import com.feijimiao.xianyuassistant.review.service.ReviewService;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.AutoDeliveryService;
import com.feijimiao.xianyuassistant.service.ChatMessageService;
import com.feijimiao.xianyuassistant.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoServiceImplTest {

    @Mock
    private RbacService rbacService;
    @Mock
    private AccountService accountService;
    @Mock
    private OrderService orderService;
    @Mock
    private ChatMessageService chatMessageService;
    @Mock
    private AutoDeliveryService autoDeliveryService;
    @Mock
    private ReviewService reviewService;

    private TodoServiceImpl todoService;

    @BeforeEach
    void setUp() {
        todoService = new TodoServiceImpl(rbacService, accountService, orderService,
                chatMessageService, autoDeliveryService, reviewService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void aggregatesAllFourCategoriesForAdmin() {
        UserContext.set(1L, "admin");

        XianyuAccount account = new XianyuAccount();
        account.setId(10L);
        account.setAccountNote("Main");
        account.setNickname("nick");
        lenient().when(accountService.listAll()).thenReturn(List.of(account));
        lenient().when(accountService.getById(10L)).thenReturn(account);

        // admin: rbac returns null -> all accounts
        when(rbacService.getCommandVisibleAccountIds(1L)).thenReturn(null);

        // pending shipment: one order
        Map<String, Object> order = Map.of(
                "commonData", Map.of("orderId", "O1", "itemId", "G1", "orderStatus", "WAIT_BUYER_PAY", "createTime", "2026-11-07"),
                "buyerInfoVO", Map.of("userNick", "buyer1"),
                "itemVO", Map.of("title", "Goods Title"),
                "priceVO", Map.of("totalPrice", "99.00")
        );
        lenient().when(orderService.queryPendingOrders(10L)).thenReturn(List.of(order));

        // pending reply: one session with pendingReply
        MsgSessionDTO session = new MsgSessionDTO();
        session.setSid("s1@goofish");
        session.setBuyerName("buyer2");
        session.setLastContent("hello");
        session.setXyGoodsId("G2");
        session.setLastTime(1730000000000L);
        session.setPendingReply(true);
        MsgSessionRespDTO sessionResp = new MsgSessionRespDTO();
        sessionResp.setList(List.of(session));
        lenient().when(chatMessageService.getSessions(any())).thenReturn(ResultObject.success(sessionResp));

        // pending review: one delivery record state=0
        AutoDeliveryRecordDTO record = new AutoDeliveryRecordDTO();
        record.setId(5L);
        record.setXianyuAccountId(10L);
        record.setGoodsTitle("Goods A");
        record.setBuyerUserName("buyer3");
        record.setOrderId("O3");
        record.setXyGoodsId("G3");
        record.setState(0);
        record.setCreateTime("2026-11-07 10:00");
        AutoDeliveryRecordRespDTO recordResp = new AutoDeliveryRecordRespDTO();
        recordResp.setRecords(List.of(record));
        lenient().when(autoDeliveryService.getAutoDeliveryRecords(any())).thenReturn(recordResp);

        // pending evaluation: one record WAITING_CONFIRM
        ReviewRecord reviewRecord = new ReviewRecord();
        reviewRecord.setId(9L);
        reviewRecord.setAccountId(10L);
        reviewRecord.setOrderId("O9");
        reviewRecord.setBuyerUserId("buyer9");
        reviewRecord.setStatus("WAITING_CONFIRM");
        lenient().when(reviewService.listRecords(eq(10L), eq("WAITING_CONFIRM"), eq(50))).thenReturn(List.of(reviewRecord));

        TodoRespDTO resp = todoService.overview(20);

        assertThat(resp.getSummary().getTotal()).isEqualTo(4);
        assertThat(resp.getSummary().getPendingShipmentCount()).isEqualTo(1);
        assertThat(resp.getSummary().getPendingReplyCount()).isEqualTo(1);
        assertThat(resp.getSummary().getPendingReviewCount()).isEqualTo(1);
        assertThat(resp.getSummary().getPendingEvaluationCount()).isEqualTo(1);

        assertThat(resp.getPendingShipment()).hasSize(1);
        assertThat(resp.getPendingShipment().get(0).getType()).isEqualTo("pendingShipment");
        assertThat(resp.getPendingShipment().get(0).getTitle()).isEqualTo("Goods Title");
        assertThat(resp.getPendingShipment().get(0).getAccountName()).isEqualTo("Main");

        assertThat(resp.getPendingReply()).hasSize(1);
        assertThat(resp.getPendingReply().get(0).getId()).isEqualTo("s1@goofish");
        assertThat(resp.getPendingReply().get(0).getTitle()).isEqualTo("buyer2");

        assertThat(resp.getPendingReview()).hasSize(1);
        assertThat(resp.getPendingReview().get(0).getTitle()).isEqualTo("Goods A");

        assertThat(resp.getPendingEvaluation()).hasSize(1);
        assertThat(resp.getPendingEvaluation().get(0).getTitle()).isEqualTo("O9");
    }

    @Test
    void nonAdminScopedToAllowedAccounts() {
        UserContext.set(2L, "op");

        XianyuAccount account = new XianyuAccount();
        account.setId(20L);
        account.setNickname("Store B");
        lenient().when(accountService.getById(20L)).thenReturn(account);
        lenient().when(accountService.listAll()).thenReturn(List.of(account));

        // non-admin: returns explicit list [20L]
        when(rbacService.getCommandVisibleAccountIds(2L)).thenReturn(List.of(20L));

        // no data -> empty aggregation
        lenient().when(orderService.queryPendingOrders(20L)).thenReturn(List.of());
        lenient().when(chatMessageService.getSessions(any())).thenReturn(ResultObject.success(new MsgSessionRespDTO()));
        lenient().when(autoDeliveryService.getAutoDeliveryRecords(any()))
                .thenReturn(new AutoDeliveryRecordRespDTO());
        lenient().when(reviewService.listRecords(eq(20L), eq("WAITING_CONFIRM"), eq(50))).thenReturn(List.of());

        TodoRespDTO resp = todoService.overview(20);

        assertThat(resp.getSummary().getTotal()).isZero();
    }
}