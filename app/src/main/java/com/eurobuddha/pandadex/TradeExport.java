package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Exports original personal records and evidence; unresolved rechecks are excluded from totals. */
public final class TradeExport {

    public static final String FILE_SUMMARY = "summary.txt";
    public static final String FILE_TRADES = "confirmed_trades.csv";
    public static final String FILE_RECONCILIATION = "reconciliation.csv";
    public static final String FILE_VERIFICATION = "verification.csv";
    public static final String FILE_CORRECTIONS = "corrections.json";
    public static final String FILE_TAKER_RECEIPTS = "taker-receipts.json";
    public static final String FILE_OWNER_RECEIPTS = "owner-receipts.json";

    private TradeExport() {}

    public static final class TradeRow {
        public final String spentCoin;
        public final long timeMs;
        public final long block;
        public final BigDecimal price;
        public final BigDecimal sizeMinima;
        public final boolean buy;
        public final boolean maker;
        public final String orderId;
        public final String txpowid;
        public final String sourceKind;
        public final String sourceCoinids;
        public final String proceedsCoinid;
        public final String verificationStatus;
        public final String verificationNote;
        public final long verifiedBlock;

        public TradeRow(String spentCoin, long timeMs, long block, BigDecimal price,
                        BigDecimal sizeMinima, boolean buy, boolean maker, String orderId) {
            this(spentCoin, timeMs, block, price, sizeMinima, buy, maker, orderId,
                    "", maker ? "BOOK" : "", spentCoin, "", "LOCAL_VERIFIED", "", block);
        }

        public TradeRow(String spentCoin, long timeMs, long block, BigDecimal price,
                        BigDecimal sizeMinima, boolean buy, boolean maker, String orderId,
                        String txpowid, String sourceKind, String sourceCoinids,
                        String proceedsCoinid, String verificationStatus,
                        String verificationNote, long verifiedBlock) {
            this.spentCoin = spentCoin == null ? "" : spentCoin;
            this.timeMs = timeMs;
            this.block = block;
            this.price = price == null ? BigDecimal.ZERO : price;
            this.sizeMinima = sizeMinima == null ? BigDecimal.ZERO : sizeMinima;
            this.buy = buy;
            this.maker = maker;
            this.orderId = orderId == null ? "" : orderId;
            this.txpowid = txpowid == null ? "" : txpowid;
            this.sourceKind = sourceKind == null ? "" : sourceKind;
            this.sourceCoinids = sourceCoinids == null ? "" : sourceCoinids;
            this.proceedsCoinid = proceedsCoinid == null ? "" : proceedsCoinid;
            this.verificationStatus = verificationStatus == null ? "" : verificationStatus;
            this.verificationNote = verificationNote == null ? "" : verificationNote;
            this.verifiedBlock = verifiedBlock;
        }
    }

    static String timeLabel(TradeRow row) {
        return row != null && row.verificationNote.endsWith(ChainEvidence.BLOCK_TIME_NOTE)
                ? "Block time" : "Observed";
    }

    public static final class Snapshot {
        public long exportedAtMs;
        public String appVersion = "";
        public String windowLabel = "All time";
        public long fromMs = 0;
        public long toMs = Long.MAX_VALUE;
        public BigDecimal freeMinima = BigDecimal.ZERO;
        public BigDecimal pendingMinima = BigDecimal.ZERO;
        public BigDecimal lockedMinima = BigDecimal.ZERO;
        public BigDecimal freeUsdt = BigDecimal.ZERO;
        public BigDecimal pendingUsdt = BigDecimal.ZERO;
        public BigDecimal lockedUsdt = BigDecimal.ZERO;
        public BigDecimal bookMid;
        public final List<TradeRow> rows = new ArrayList<>();
        public String correctionsJson = "[]";
        public String takerReceiptsJson = "[]";
        public String ownerReceiptsJson = "[]";
    }

    public static final class Totals {
        public int excludedRechecks;
        public BigDecimal minimaBought = BigDecimal.ZERO;
        public BigDecimal minimaSold = BigDecimal.ZERO;
        public BigDecimal usdtPaid = BigDecimal.ZERO;
        public BigDecimal usdtReceived = BigDecimal.ZERO;
        public BigDecimal netMinima = BigDecimal.ZERO;
        public BigDecimal netUsdt = BigDecimal.ZERO;
        public BigDecimal holdingsMinima = BigDecimal.ZERO;
        public BigDecimal holdingsUsdt = BigDecimal.ZERO;
        public BigDecimal holdingsValueUsdt;
        public BigDecimal tradeValueUsdt;
        public BigDecimal impliedExternalUsdt;
    }

    public static final class Report {
        public String summaryTxt;
        public String tradesCsv;
        public String reconciliationCsv;
        public String verificationCsv;
        public String correctionsJson;
        public String takerReceiptsJson;
        public String ownerReceiptsJson;
        public int tradeCount;
        public Totals totals;
    }

    interface ExternalVerifier {
        ExplorerVerifier.Result lookup(String txpowid);
    }

    public static Report build(Snapshot s) {
        Report r = new Report();
        r.tradeCount = s.rows.size();
        r.totals = totals(s);
        r.tradesCsv = tradesCsv(s);
        r.reconciliationCsv = reconciliationCsv(s, r.totals);
        r.verificationCsv = verificationCsv(s);
        r.correctionsJson = s.correctionsJson;
        r.takerReceiptsJson = s.takerReceiptsJson;
        r.ownerReceiptsJson = s.ownerReceiptsJson;
        r.summaryTxt = summary(s, r.totals, r.tradeCount);
        return r;
    }

    public static Snapshot verifiedCopy(Snapshot s) {
        return verifiedCopy(s, new ExportChecks(ExplorerVerifier::lookup));
    }

    static Snapshot verifiedCopy(Snapshot s, ExternalVerifier verifier) {
        Snapshot out = new Snapshot();
        out.exportedAtMs = s.exportedAtMs;
        out.correctionsJson = s.correctionsJson;
        out.takerReceiptsJson = s.takerReceiptsJson;
        out.ownerReceiptsJson = s.ownerReceiptsJson;
        out.appVersion = s.appVersion;
        out.windowLabel = s.windowLabel;
        out.fromMs = s.fromMs;
        out.toMs = s.toMs;
        out.freeMinima = s.freeMinima;
        out.pendingMinima = s.pendingMinima;
        out.lockedMinima = s.lockedMinima;
        out.freeUsdt = s.freeUsdt;
        out.pendingUsdt = s.pendingUsdt;
        out.lockedUsdt = s.lockedUsdt;
        out.bookMid = s.bookMid;
        for (TradeRow row : s.rows) out.rows.add(verify(row, verifier));
        return out;
    }

    static TradeRow verify(TradeRow row, ExternalVerifier verifier) {
        if (row.txpowid == null || row.txpowid.isEmpty()) return row;
        ExplorerVerifier.Result v = verifier == null ? null : verifier.lookup(row.txpowid);
        if (v == null) return row;
        // External corroboration is separate evidence, never a rewrite of the node record.
        long block = row.verifiedBlock;
        boolean externalOk = v.confirms(row.txpowid);
        boolean heightConflict = externalOk && block > 0 && v.block != block;
        String baseStatus = row.verificationStatus == null || row.verificationStatus.isEmpty()
                ? "LOCAL_ONLY" : row.verificationStatus;
        String status = !externalOk ? baseStatus : baseStatus
                + (heightConflict ? "+EXPLORER_HEIGHT_CONFLICT" : "+EXPLORER_OK");
        String externalNote = v.note;
        if (heightConflict) externalNote = "Explorer inclusion height " + v.block
                + " disagrees with stored verification height " + block
                + "; original node/local evidence retained. " + (v.note == null ? "" : v.note);
        String note = externalOk
                ? appendNote(row.verificationNote, externalNote)
                : appendNote(row.verificationNote,
                        "Public explorer unavailable or unverified; retained PandaDEX local/node verification"
                                + (v.note == null || v.note.isEmpty() ? "" : " (" + v.note + ")"));
        return new TradeRow(row.spentCoin, row.timeMs, row.block, row.price, row.sizeMinima,
                row.buy, row.maker, row.orderId, row.txpowid, row.sourceKind, row.sourceCoinids,
                row.proceedsCoinid, status, note, block);
    }

    private static String appendNote(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        for (String suffix : new String[]{ChainEvidence.BLOCK_TIME_NOTE, ChainEvidence.OBSERVED_TIME_NOTE}) {
            if (a.endsWith(suffix)) return a.substring(0, a.length() - suffix.length()) + " | " + b + suffix;
        }
        return a + " | " + b;
    }

    private static Totals totals(Snapshot s) {
        Totals t = new Totals();
        for (TradeRow row : s.rows) include(t, row);
        finishTotals(s, t);
        return t;
    }
    static void include(Totals t, TradeRow row) {
        if (!ChainReview.accounted(row.verificationStatus)) { t.excludedRechecks++; return; }
        BigDecimal notional = usdtNotional(row);
        if (row.buy) {
            t.minimaBought = t.minimaBought.add(row.sizeMinima);
            t.usdtPaid = t.usdtPaid.add(notional);
        } else {
            t.minimaSold = t.minimaSold.add(row.sizeMinima);
            t.usdtReceived = t.usdtReceived.add(notional);
        }
    }
    static void finishTotals(Snapshot s, Totals t) {
        t.netMinima = t.minimaBought.subtract(t.minimaSold);
        t.netUsdt = t.usdtReceived.subtract(t.usdtPaid);
        t.holdingsMinima = s.freeMinima.add(s.pendingMinima).add(s.lockedMinima);
        t.holdingsUsdt = s.freeUsdt.add(s.pendingUsdt).add(s.lockedUsdt);
        if (s.bookMid != null && s.bookMid.signum() > 0) {
            t.holdingsValueUsdt = t.holdingsUsdt.add(cutUsdt(t.holdingsMinima.multiply(s.bookMid, PriceMath.MC)));
            t.tradeValueUsdt = t.netUsdt.add(cutUsdt(t.netMinima.multiply(s.bookMid, PriceMath.MC)));
            t.impliedExternalUsdt = t.holdingsValueUsdt.subtract(t.tradeValueUsdt);
        }
    }

    static final String TRADESCSV_HEADER = "timestamp_utc,block,spent_coin,side,role,source_kind,minima_delta,mxusdt_delta,"
                + "minima_amount,price,mxusdt_notional,order_id,txpowid,source_coinids,"
                + "proceeds_coinid,verification_status,verified_block,verification_note,explorer_url,block_explorer_url,accounting_included\n";
    private static String tradesCsv(Snapshot s) {
        StringBuilder sb = new StringBuilder(TRADESCSV_HEADER);
        for (TradeRow row : s.rows) sb.append(tradeCsvRow(row));
        return sb.toString();
    }
    static String tradeCsvRow(TradeRow row) {
        StringBuilder sb = new StringBuilder();
        BigDecimal notional = usdtNotional(row);
        BigDecimal md = row.buy ? row.sizeMinima : row.sizeMinima.negate();
        BigDecimal ud = row.buy ? notional.negate() : notional;
        sb.append(csv(utc(row.timeMs))).append(',')
                .append(row.block).append(',')
                .append(csvText(row.spentCoin)).append(',')
                .append(row.buy ? "BUY" : "SELL").append(',')
                .append(row.maker ? "MAKER" : "TAKER").append(',')
                .append(csvText(row.sourceKind)).append(',')
                .append(csv(amount(md))).append(',')
                .append(csv(amount(ud))).append(',')
                .append(csv(amount(row.sizeMinima))).append(',')
                .append(csv(row.price.toPlainString())).append(',')
                .append(csv(amount(notional))).append(',')
                .append(csvText(row.orderId)).append(',')
                .append(csvText(row.txpowid)).append(',')
                .append(csvText(row.sourceCoinids)).append(',')
                .append(csvText(row.proceedsCoinid)).append(',')
                .append(csvText(row.verificationStatus)).append(',')
                .append(row.verifiedBlock).append(',')
                .append(csvText(row.verificationNote)).append(',')
                .append(csv(explorerUrl(row.txpowid))).append(',')
                .append(csv(blockUrl(row.txpowid))).append(',')
                .append(ChainReview.accounted(row.verificationStatus)).append('\n');
        return sb.toString();
    }

    private static String reconciliationCsv(Snapshot s, Totals t) { return reconciliationCsv(s, t, s.rows.size()); }
    static String reconciliationCsv(Snapshot s, Totals t, int rowCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("metric,value,unit\n");
        metric(sb, "stored personal trade records", String.valueOf(rowCount), "rows");
        metric(sb, "records excluded pending node recheck", String.valueOf(t.excludedRechecks), "rows");
        metric(sb, "MINIMA bought", amount(t.minimaBought), "MINIMA");
        metric(sb, "MINIMA sold", amount(t.minimaSold), "MINIMA");
        metric(sb, "mxUSDT paid for buys", amount(t.usdtPaid), "mxUSDT");
        metric(sb, "mxUSDT received from sells", amount(t.usdtReceived), "mxUSDT");
        metric(sb, "net MINIMA from trades", amount(t.netMinima), "MINIMA");
        metric(sb, "net mxUSDT from trades", amount(t.netUsdt), "mxUSDT");
        metric(sb, "current MINIMA holding", amount(t.holdingsMinima), "MINIMA");
        metric(sb, "current mxUSDT holding", amount(t.holdingsUsdt), "mxUSDT");
        if (s.bookMid != null && s.bookMid.signum() > 0) {
            metric(sb, "book mid used for value", s.bookMid.toPlainString(), "mxUSDT/MINIMA");
            metric(sb, "current holding value", amount(t.holdingsValueUsdt), "mxUSDT");
            metric(sb, "net trade value at book mid", amount(t.tradeValueUsdt), "mxUSDT");
            metric(sb, "implied non-trade balance delta", amount(t.impliedExternalUsdt), "mxUSDT");
        } else {
            metric(sb, "book mid used for value", "unavailable", "");
        }
        return sb.toString();
    }

    static final String VERIFICATIONCSV_HEADER = "timestamp_utc,spent_coin,txpowid,source_kind,verification_status,verified_block,note,explorer_url,block_explorer_url\n";
    private static String verificationCsv(Snapshot s) {
        StringBuilder sb = new StringBuilder(VERIFICATIONCSV_HEADER);
        for (TradeRow row : s.rows) sb.append(verificationCsvRow(row));
        return sb.toString();
    }
    static String verificationCsvRow(TradeRow row) {
        StringBuilder sb = new StringBuilder();
        sb.append(csv(utc(row.timeMs))).append(',')
                .append(csvText(row.spentCoin)).append(',')
                .append(csvText(row.txpowid)).append(',')
                .append(csvText(row.sourceKind)).append(',')
                .append(csvText(row.verificationStatus)).append(',')
                .append(row.verifiedBlock).append(',')
                .append(csvText(row.verificationNote)).append(',')
                .append(csv(explorerUrl(row.txpowid))).append(',')
                .append(csv(blockUrl(row.txpowid))).append('\n');
        return sb.toString();
    }

    static String summary(Snapshot s, Totals t, int rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("PandaDEX personal trade export\n");
        sb.append("Exported: ").append(utc(s.exportedAtMs)).append('\n');
        if (s.appVersion != null && !s.appVersion.isEmpty()) sb.append("App version: ").append(s.appVersion).append('\n');
        sb.append("Period: ").append(s.windowLabel == null ? "All time" : s.windowLabel).append('\n');
        sb.append('\n');
        sb.append("This export contains stored personal trade rows. Read verification_status for each row; legacy records may only have local evidence. Public market tape rows are excluded.\n");
        sb.append("Each row is keyed by its spent/source coin. corrections.json preserves earlier personal receipt versions and the evidence used for corrections. taker-receipts.json retains available completed taker expectations and linked evidence; stored proof is not a fresh chain check.\n");
        sb.append("owner-receipts.json includes all saved completed owner operations, their latest verified receipt JSON and last saved node check; corrections.json retains earlier owner receipt versions and the replacement evidence, independent of the trade window. Export does not freshly check these operations or add them to trade totals.\n");
        sb.append('\n');
        sb.append("Stored trade rows: ").append(rows).append('\n');
        sb.append("Rows excluded from totals pending node recheck: ").append(t.excludedRechecks).append('\n');
        sb.append("Original rows and deltas remain in the CSV; accounting_included identifies those used in totals.\n");
        if (t.excludedRechecks > 0) sb.append("Reconciliation is provisional while rows are unresolved; the implied non-trade balance delta is not proof of transfers.\n");
        sb.append("MINIMA bought: ").append(amount(t.minimaBought)).append('\n');
        sb.append("MINIMA sold: ").append(amount(t.minimaSold)).append('\n');
        sb.append("mxUSDT paid: ").append(amount(t.usdtPaid)).append('\n');
        sb.append("mxUSDT received: ").append(amount(t.usdtReceived)).append('\n');
        sb.append("Net MINIMA from trades: ").append(amount(t.netMinima)).append('\n');
        sb.append("Net mxUSDT from trades: ").append(amount(t.netUsdt)).append('\n');
        sb.append('\n');
        sb.append("Current holding snapshot:\n");
        sb.append("MINIMA: ").append(amount(t.holdingsMinima)).append(" (available ")
                .append(amount(s.freeMinima)).append(", confirming ").append(amount(s.pendingMinima))
                .append(", in orders ").append(amount(s.lockedMinima)).append(")\n");
        sb.append("mxUSDT: ").append(amount(t.holdingsUsdt)).append(" (available ")
                .append(amount(s.freeUsdt)).append(", confirming ").append(amount(s.pendingUsdt))
                .append(", in orders ").append(amount(s.lockedUsdt)).append(")\n");
        if (s.bookMid != null && s.bookMid.signum() > 0) {
            sb.append("Book mid: ").append(s.bookMid.toPlainString()).append(" mxUSDT/MINIMA\n");
            sb.append("Holding value: ").append(amount(t.holdingsValueUsdt)).append(" mxUSDT\n");
            sb.append("Net trade value: ").append(amount(t.tradeValueUsdt)).append(" mxUSDT\n");
            sb.append("Implied non-trade balance delta: ").append(amount(t.impliedExternalUsdt)).append(" mxUSDT\n");
        } else {
            sb.append("Book mid unavailable, so holding value reconciliation is omitted.\n");
        }
        return sb.toString();
    }

    static BigDecimal usdtNotional(TradeRow row) {
        return cutUsdt(row.sizeMinima.multiply(row.price, PriceMath.MC));
    }

    private static BigDecimal cutUsdt(BigDecimal v) {
        return v.setScale(PriceMath.USDT_DP, RoundingMode.DOWN);
    }

    private static String amount(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    private static void metric(StringBuilder sb, String name, String value, String unit) {
        sb.append(csv(name)).append(',').append(csv(value)).append(',').append(csv(unit)).append('\n');
    }

    static String filename(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "pandadex-trades-" + f.format(new Date(ms)) + ".zip";
    }

    private static String explorerUrl(String txpowid) {
        if (txpowid == null || txpowid.isEmpty()) return "";
        return "https://explorer.minima.global/transactions/" + txpowid;
    }

    private static String blockUrl(String txpowid) {
        if (txpowid == null || txpowid.isEmpty()) return "";
        return "https://block.minima.global/transactions/" + txpowid;
    }

    private static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ENGLISH);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    /** PandaPools PoolStatement.text, extended to leading whitespace/control characters. */
    static String csvText(String s) {
        if (s == null) s = "";
        String trimmed = s.trim();
        if (!trimmed.isEmpty()) {
            char c = trimmed.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@') s = "'" + s;
        }
        return csv(s);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
