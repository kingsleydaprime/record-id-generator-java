package com.itc.model;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Transaction {
    private String id;
    private String sourceHash;
    private String paymentTypeId;
    private String sourceId;
    private String thirdpartyId;
    private LocalDateTime sourceDateCreated;
    private String sourceAccountNo;
    private String sourceTransId;
    private String channelId;
    private String terminalId;
    private String merchantId;
    private String productId;
    private String subMerchantId;
    private String accountref;
    private String accountname;
    private String paymentmsisdn;
    private String narration;
    private String currency;
    private BigDecimal amount;
    private BigDecimal fees;
    private int year;
    private String processor;
    private String country;
    private String transtype;
    private String month;
}
