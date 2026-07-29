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

    /**
     * One line per desired rung (plus any recorded rung being removed, plus a cancel summary).
     * {@code myBookByOrderId} must be MY confirmed book orders keyed by orderId.
     */
    public static List<Line> lines(List<MakerLadder.Slot> desired,
                                   Map<String, MakerConfig.SlotRec> slots,
                                   Map<String, ?> tombstones,
                                   Map<String, Order5> myBookByOrderId,
                                   long chainBlock) {
        List<Line> out = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();

        if (desired != null) {
            for (MakerLadder.Slot s : desired) {
                covered.add(s.id);
                MakerConfig.SlotRec r = slots == null ? null : slots.get(s.id);
                if (r == null) {
                    out.add(new Line(s.id, "waiting to post — next cycle", WAIT));
                    continue;
                }
                Order5 o = myBookByOrderId == null ? null : myBookByOrderId.get(r.orderId);
                if (o == null) {
                    long blk = r.sentBlock > 0 ? Math.max(0, chainBlock - r.sentBlock) : 0;
                    out.add(new Line(s.id, "mining (" + blk + " blk, ~" + (blk * 50) + "s ago)"
                            + (blk >= MakerEngine.PATIENCE_BLOCKS - 1 ? " — retries if lost" : ""),
                            WAIT));
                    continue;
                }
                BigDecimal posted = r.size;
                if (posted != null && posted.signum() > 0
                        && o.minimaAmount().compareTo(posted) < 0) {
                    out.add(new Line(s.id, "part-filled — " + PriceMath.fmt(o.minimaAmount())
                            + " of " + PriceMath.fmt(posted) + " left working", OK));
                } else if (r.lastActionBlock > 0
                        && chainBlock - r.lastActionBlock < MakerEngine.PATIENCE_BLOCKS) {
                    out.add(new Line(s.id, "repricing — waiting for a block", WAIT));
                } else {
                    out.add(new Line(s.id, "live " + PriceMath.fmtPrice(o.price()) + " × "
                            + PriceMath.fmt(o.minimaAmount()), OK));
                }
            }
        }

        // rungs we still have a record for but no longer want (being removed next cycles)
        if (slots != null) {
            for (Map.Entry<String, MakerConfig.SlotRec> e : slots.entrySet()) {
                if (!covered.contains(e.getKey())) {
                    out.add(new Line(e.getKey(), "no longer wanted — cancelling", WAIT));
                }
            }
        }

        if (tombstones != null && !tombstones.isEmpty()) {
            int visible = 0;
            if (myBookByOrderId != null) {
                for (String id : tombstones.keySet()) if (myBookByOrderId.containsKey(id)) visible++;
            }
            out.add(new Line("", "cancelling " + tombstones.size() + " order"
                    + (tombstones.size() == 1 ? "" : "s")
                    + (visible > 0 ? " — gone when a block confirms" : " — confirming…"), WAIT));
        }
        return out;
    }
}
