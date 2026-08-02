package com.eurobuddha.pandadex;

import java.math.BigDecimal;

/** Builds the simple wallet self-send used to split funding UTXOs before publishing a ladder. */
final class SelfSplit {

    static final int COUNT = 10;

    private SelfSplit() {}

    static String command(String address, String tokenid, BigDecimal amount) {
        String amt = amount.stripTrailingZeros().toPlainString();
        return "send address:" + address + " amount:" + amt
                + (Util.isMinima(tokenid) ? "" : " tokenid:" + tokenid)
                + " split:" + COUNT;
    }
}
