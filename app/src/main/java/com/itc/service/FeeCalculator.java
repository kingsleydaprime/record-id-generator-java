package com.itc.service;

import com.itc.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FeeCalculator {

    private static final BigDecimal RATE_MTN     = new BigDecimal("0.0075");
    private static final BigDecimal RATE_AIRTEL  = new BigDecimal("0.01");
    private static final BigDecimal RATE_TELECEL = new BigDecimal("0.013");
    private static final BigDecimal RATE_DEFAULT = new BigDecimal("0.01");

    public void calculate(Transaction t) {
        if (!"inflow".equalsIgnoreCase(t.getTranstype())) {
            // network/ITC fee split only applies to inflows
            t.setNetworkFee(null);
            t.setItcFee(null);
            return;
        }

        BigDecimal rate = rateFor(t.getSourceId());
        BigDecimal networkFee = t.getAmount()
                .multiply(rate)
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal itcFee = t.getFees()
                .subtract(networkFee)
                .setScale(6, RoundingMode.HALF_UP);

        t.setNetworkFee(networkFee);
        t.setItcFee(itcFee);
    }

    private BigDecimal rateFor(String sourceId) {
        if (sourceId == null) return RATE_DEFAULT;
        switch (sourceId.trim().toUpperCase()) {
            case "MTN":     return RATE_MTN;
            case "AIRTEL":  return RATE_AIRTEL;
            case "TELECEL": return RATE_TELECEL;
            default:        return RATE_DEFAULT;
        }
    }
}
