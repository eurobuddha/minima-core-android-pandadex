package com.eurobuddha.pandadex;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the maker's slot records + the confirmed book into the per-rung story the user could
 * never see in 0.2.6 ("armed a ladder, watched nothing happen for minutes"). Pure so the
 * wording rules are testable; MakerTab just renders the lines.
 */
public final class MakerStatus {

    public static final int OK = 0, WAIT = 1, FAIL = 2;

    public static final class Line {
        public final String slotId;   // "B1".."A6", or "" for the summary lines
        public final String text;
        public final int tone;

        Line(String slotId, String text, int tone) {
            this.slotId = slotId;
            this.text = text;
            this.tone = tone;
        }
    }

    private MakerStatus() {}

    /** MakerTab's existing book cache, also invalidated when either ownership factor changes. */
    static final class OwnedBook {
        private Map<String, Order5> bookSeen, mine;
        private Set<String> keysSeen, addressesSeen;
        Map<String, Order5> get(Map<String, Order5> book, Set<String> keys, Set<String> addresses) {
            if (book == bookSeen && mine != null && keys.equals(keysSeen) && addresses.equals(addressesSeen)) return mine;
            Map<String, Order5> found = new java.util.HashMap<>();
            for (Order5 order : book.values()) if (order.isMine(keys, addresses)) found.put(order.orderId, order);
            bookSeen = book;
            // KeySet exposes live unmodifiable views; retain copies, not aliases to those views.
            keysSeen = new java.util.HashSet<>(keys);
            addressesSeen = new java.util.HashSet<>(addresses);
            mine = java.util.Collections.unmodifiableMap(found);
            return mine;
        }
    }

    /**
     * One line per desired rung (plus any recorded rung being removed, plus a cancel summary).
     * {@code myBookByOrderId} must be MY confirmed book orders keyed by orderId.
     */
    public static List<Line> lines(List<MakerLadder.Slot> desired,
                                   Map<String, MakerConfig.SlotRec> slots,
                                   Map<String, ?> tombstones,
                                   Map<String, Order5> myBookByOrderId,
                                   long chainBlock, boolean ownershipAndBookReady) {
        List<Line> out = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();

        if (desired != null) {
            for (MakerLadder.Slot s : desired) {
                covered.add(s.id);
                MakerConfig.SlotRec r = slots == null ? null : slots.get(s.id);
                if (r == null) {
                    out.add(new Line(s.id, ownershipAndBookReady ? "waiting to post — next cycle"
                            : "no order recorded — wallet/book check pending", WAIT));
                    continue;
                }
                Order5 o = myBookByOrderId == null ? null : myBookByOrderId.get(r.orderId);
                if (!ownershipAndBookReady) {
                    out.add(new Line(s.id, o == null ? "order recorded — wallet/book check pending"
                            : "last seen " + PriceMath.fmtPrice(o.price()) + " × " + PriceMath.fmt(o.minimaAmount())
                            + " — wallet/book check pending", WAIT));
                    continue;
                }
                if (o == null) {
                    long blk = r.sentBlock > 0 ? Math.max(0, chainBlock - r.sentBlock) : 0;
                    out.add(new Line(s.id, "not seen in this book snapshot (" + blk + " blk since recorded)"
                            + (blk >= MakerEngine.PATIENCE_BLOCKS - 1 ? " — review if still absent" : ""),
                            WAIT));
                    continue;
                }
                BigDecimal funded=MakerPosition.baseline(r,o);
                if(funded==null) {
                    out.add(new Line(s.id,"original funding unknown — review before automatic repricing",WAIT));
                }else if(o.locked.compareTo(funded)>0) {
                    out.add(new Line(s.id,"funding differs from recorded intent — review this order",WAIT));
                }else if(o.locked.compareTo(funded)<0) {
                    out.add(new Line(s.id,"less funding remains — "+PriceMath.fmt(o.locked)
                            +" of "+PriceMath.fmt(funded)+(o.sell?" MINIMA":" MxUSD")+" locked",OK));
                } else if (r.lastActionBlock > 0
                        && chainBlock - r.lastActionBlock < MakerEngine.PATIENCE_BLOCKS) {
                    out.add(new Line(s.id, "reprice requested — outcome not yet verified", WAIT));
                } else {
                    out.add(new Line(s.id, "seen in latest book snapshot: " + PriceMath.fmtPrice(o.price()) + " × "
                            + PriceMath.fmt(o.minimaAmount()), OK));
                }
            }
        }

        // rungs we still have a record for but no longer want (being removed next cycles)
        if (slots != null) {
            for (Map.Entry<String, MakerConfig.SlotRec> e : slots.entrySet()) {
                if (!covered.contains(e.getKey())) {
                    out.add(new Line(e.getKey(), "no longer wanted — withdrawal still needed", WAIT));
                }
            }
        }

        if (tombstones != null && !tombstones.isEmpty()) {
            int visible = 0;
            if (myBookByOrderId != null) {
                for (String id : tombstones.keySet()) if (myBookByOrderId.containsKey(id)) visible++;
            }
            out.add(new Line("", "withdrawal instructions retained for " + tombstones.size() + " order"
                    + (tombstones.size() == 1 ? "" : "s")
                    + (visible > 0 ? " — " + visible + (ownershipAndBookReady ? " seen in latest snapshot" : " last seen; wallet/book check pending") : " — cancellation not proven by absence"), WAIT));
        }
        return out;
    }
}
