package com.eurobuddha.pandadex;
import org.junit.Test;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class OwnerExportTest {
    @Test public void ownerEvidenceStreamsWithoutBecomingTradesOrChangingOriginalBytes()throws Exception {
        File root=Files.createTempDirectory("owner-export").toFile();TradeExportFiles.Prepared p=null;
        try {
            String original=" {\"version\":1,\"expected\":{\"submitMs\":1700000000001,\"note\":\"quote \\\" and 世界\"}} ";
            String exported=new JSONObject().put("original_receipt_json",original).put("last_saved_node_check",new JSONObject().put("state","MISSING").put("depth",-1)).toString();
            p=TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->sink.owner(exported),null);
            JSONObject entry=new JSONArray(TradeExportFilesTest.unpack(p.zip).get(TradeExport.FILE_OWNER_RECEIPTS)).getJSONObject(0);
            assertEquals(original,entry.getString("original_receipt_json"));assertEquals("MISSING",entry.getJSONObject("last_saved_node_check").getString("state"));
            assertEquals(0,p.report.tradeCount);assertEquals(0,p.report.totals.netMinima.signum());assertEquals(0,p.report.totals.netUsdt.signum());
            assertNull(p.report.ownerReceiptsJson);assertTrue(p.report.summaryTxt.contains("Export does not freshly check these operations"));
        }finally{if(p!=null)p.close();TradeExportFilesTest.cleanup(root);}
    }
    @Test public void ownerSnapshotFailureCannotPublishPartialEvidence()throws Exception {
        File root=Files.createTempDirectory("owner-export-fail").toFile();
        try {
            assertThrows(IOException.class,()->TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->{sink.owner("{\"saved\":true}");throw new IOException("snapshot interrupted");},null));
            assertEquals(0,new File(root,"trade-exports").list().length);
        }finally{TradeExportFilesTest.cleanup(root);}
    }
    @Test public void malformedTrailingOrOversizedOwnerPayloadIsNotSilentlyTruncated()throws Exception {
        File root=Files.createTempDirectory("owner-export-invalid").toFile();
        try {
            for(String raw:new String[]{"{} /* hidden */","{} garbage","[]","not-json","{\"data\":\""+"x".repeat(TradeExportFiles.MAX_RECORD_BYTES)+"\"}"})
                assertThrows(Exception.class,()->TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->sink.owner(raw),null));
            assertEquals(0,new File(root,"trade-exports").list().length);
        }finally{TradeExportFilesTest.cleanup(root);}
    }
    @Test public void ownerRowsAreNotLimitedByTheVisibleHistoryPage()throws Exception {
        File root=Files.createTempDirectory("owner-export-many").toFile();TradeExportFiles.Prepared p=null;
        try {
            p=TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->{for(int i=0;i<257;i++)sink.owner("{\"receipt\":"+i+"}");},null);
            JSONArray entries=new JSONArray(TradeExportFilesTest.unpack(p.zip).get(TradeExport.FILE_OWNER_RECEIPTS));
            assertEquals(257,entries.length());assertEquals(256,entries.getJSONObject(256).getInt("receipt"));assertEquals(0,p.report.tradeCount);
        }finally{if(p!=null)p.close();TradeExportFilesTest.cleanup(root);}
    }
    @Test public void legacySnapshotReportAndVerifiedCopyPreserveOwnerEvidence() {
        TradeExport.Snapshot snapshot=new TradeExport.Snapshot();snapshot.ownerReceiptsJson="[{\"original\":true}]";
        TradeExport.Snapshot copy=TradeExport.verifiedCopy(snapshot,id->{throw new AssertionError("owner proof must not trigger explorer checks");});
        assertEquals(snapshot.ownerReceiptsJson,copy.ownerReceiptsJson);assertEquals(snapshot.ownerReceiptsJson,TradeExport.build(copy).ownerReceiptsJson);
    }
}
