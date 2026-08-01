package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Vendored from PandaPools' pure Java core (app/src/main/java/com/eurobuddha/pandapools/Pool.java),
 * adapted only for the PandaDEX package. Keep byte/maths compatibility with PandaPools.
 */
public class Pool {

    public String address;
    public String mxaddress;
    public String opk, oadr, tok, kmin;
    public String covenantScript;
    public String tokName;
    public int tokDecimals = 8;

    public BigDecimal reserveM;
    public String coinidM;
    public BigDecimal reserveT;
    public String coinidT;
    public int reserveBlock = 0;

    public boolean funded() {
        return reserveM != null && reserveT != null && reserveM.signum() > 0 && reserveT.signum() > 0;
    }

    public BigDecimal k() {
        return funded() ? reserveM.multiply(reserveT) : BigDecimal.ZERO;
    }

    public BigDecimal spotPrice() {
        if (!funded()) return BigDecimal.ZERO;
        return reserveT.divide(reserveM, new MathContext(20, RoundingMode.DOWN));
    }
}
