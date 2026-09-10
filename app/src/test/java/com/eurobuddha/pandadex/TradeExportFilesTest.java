package com.eurobuddha.pandadex;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public class TradeExportFilesTest {
    private TradeExport.TradeRow row(int n){return new TradeExport.TradeRow("0x"+n,1700000000000L+n,100+n,
        new BigDecimal("0.125000000"),new BigDecimal("10.25"),n%2==0,n%3==0,"=formula,\"世界\"","0xaabb","BOOK","0xaa 0xbb","0xcc",
        n==1?"RECHECK_REQUIRED":"CHAIN_VERIFIED","line one\nline two"+ChainEvidence.BLOCK_TIME_NOTE,100+n);}
    static Map<String,String> unpack(File zip) throws Exception {
        Map<String,String> out=new LinkedHashMap<>();
        try(ZipInputStream in=new ZipInputStream(new FileInputStream(zip))){ZipEntry e;while((e=in.getNextEntry())!=null){ByteArrayOutputStream b=new ByteArrayOutputStream();TradeExportFiles.copy(in,b);out.put(e.getName(),b.toString("UTF-8"));}}
        return out;
    }
    static void cleanup(File root) throws Exception {try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
    @Test public void streamedArchiveMatchesTestedReportAndKeepsAllEvidence() throws Exception {
        File root=Files.createTempDirectory("export-files-test").toFile();TradeExportFiles.Prepared prepared=null;
        try {
            TradeExport.Snapshot s=new TradeExport.Snapshot();s.exportedAtMs=1700000000000L;s.appVersion="test";s.freeMinima=new BigDecimal("40");s.bookMid=new BigDecimal("0.1");
            for(int i=0;i<4;i++)s.rows.add(row(i));s.correctionsJson="[{\"original\":\"retain\"}]";s.takerReceiptsJson="[{\"full\":\"evidence\"}]";
            TradeExport.ExternalVerifier verifier=id->{ExplorerVerifier.Result r=new ExplorerVerifier.Result();r.status="EXPLORER_OK";r.txpowid=id;r.block=100;r.note="Confirmed fixture";return r;};
            TradeExport.Report expected=TradeExport.build(TradeExport.verifiedCopy(s,verifier));
            prepared=TradeExportFiles.prepare(root,s,(metadata,sink)->{for(TradeExport.TradeRow r:s.rows)sink.trade(r);sink.correction("{\"original\":\"retain\"}");sink.taker("{\"full\":\"evidence\"}");},verifier);
            Map<String,String> files=unpack(prepared.zip);assertEquals(7,files.size());
            assertEquals(expected.summaryTxt,files.get(TradeExport.FILE_SUMMARY));assertEquals(expected.tradesCsv,files.get(TradeExport.FILE_TRADES));
            assertEquals(expected.verificationCsv,files.get(TradeExport.FILE_VERIFICATION));assertEquals(expected.reconciliationCsv,files.get(TradeExport.FILE_RECONCILIATION));
            assertEquals(expected.correctionsJson,files.get(TradeExport.FILE_CORRECTIONS));assertEquals(expected.takerReceiptsJson,files.get(TradeExport.FILE_TAKER_RECEIPTS));
            assertEquals(4,prepared.report.tradeCount);assertEquals(expected.totals.netMinima,prepared.report.totals.netMinima);
            assertNull(prepared.report.tradesCsv);assertNull(prepared.report.takerReceiptsJson);assertNull(prepared.report.ownerReceiptsJson);
            assertEquals("[]",files.get(TradeExport.FILE_OWNER_RECEIPTS));
            assertEquals(1,prepared.directory.list().length); // only finished ZIP waits for picker
            File directory=prepared.directory;prepared.close();assertFalse(directory.exists());
        }finally{if(prepared!=null)prepared.close();cleanup(root);}
    }
    @Test public void privateRowCodecPreservesAllFieldsAndRejectsTruncation() throws Exception {
        TradeExport.TradeRow original=row(2),copy=TradeExportFiles.decode(TradeExportFiles.encode(original));
        assertEquals(TradeExport.tradeCsvRow(original),TradeExport.tradeCsvRow(copy));assertEquals(TradeExport.verificationCsvRow(original),TradeExport.verificationCsvRow(copy));
        assertThrows(Exception.class,()->TradeExportFiles.decode("[]"));
        assertThrows(IOException.class,()->TradeExportFiles.nextRecord(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0}))));
        assertThrows(IOException.class,()->TradeExportFiles.nextRecord(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0,0,2,1}))));
        assertThrows(IOException.class,()->TradeExportFiles.nextRecord(new DataInputStream(new ByteArrayInputStream(new byte[]{127,0,0,0}))));
    }
    @Test public void snapshotFailureNeverPublishesPartialZip() throws Exception {
        File root=Files.createTempDirectory("export-failure").toFile();
        try {
            assertThrows(IOException.class,()->TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->{sink.trade(row(0));sink.correction("{\"old\":true}");throw new IOException("injected source failure");},null));
            assertEquals(0,new File(root,"trade-exports").list().length);
        }finally{cleanup(root);}
    }
    @Test public void activePreparedFileSurvivesOtherBuildsAndDefersCleanupDuringSave() throws Exception {
        File root=Files.createTempDirectory("export-owner").toFile();TradeExportFiles.Prepared a=null,b=null;
        try {
            a=TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->sink.trade(row(0)),null);
            b=TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->{},null);
            assertTrue(a.zip.isFile());assertTrue(b.zip.isFile());a.claim();a.close();assertTrue(a.zip.isFile());
            a.saved();assertFalse(a.directory.exists());assertTrue(b.zip.exists());
        }finally{if(a!=null)a.close();if(b!=null)b.close();cleanup(root);}
    }
    @Test public void malformedEvidenceAndExtremeDecimalsFailWithoutSubstitution() throws Exception {
        File root=Files.createTempDirectory("export-invalid").toFile();
        try {
            assertThrows(Exception.class,()->TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->sink.taker("not json"),null));
            TradeExport.TradeRow bad=new TradeExport.TradeRow("source",0,0,new BigDecimal("1e2147483647"),BigDecimal.ONE,true,true,"");
            assertThrows(IOException.class,()->TradeExportFiles.prepare(root,new TradeExport.Snapshot(),(s,sink)->sink.trade(bad),null));
            assertEquals(0,new File(root,"trade-exports").list().length);
        }finally{cleanup(root);}
    }
    @Test public void largeArchiveStreamsWithoutKeepingRowsOrEvidenceInReport() throws Exception {
        if(Boolean.getBoolean("pandadex.memoryaudit"))assertTrue(Runtime.getRuntime().maxMemory()<=64L*1024*1024);
        System.out.println("EXPORT_MEMORY_MAX_HEAP="+Runtime.getRuntime().maxMemory()+"; rows=30000; correction_payloads=60MiB");
        File root=Files.createTempDirectory("export-large").toFile();TradeExportFiles.Prepared p=null;
        try {
            TradeExport.Snapshot metadata=new TradeExport.Snapshot();String evidence="{\"proof\":\""+"x".repeat(1024*1024)+"\"}";
            p=TradeExportFiles.prepare(root,metadata,(s,sink)->{for(int i=0;i<30000;i++)sink.trade(row(i));for(int i=0;i<60;i++)sink.correction(evidence);},null);
            assertEquals(30000,p.report.tradeCount);assertTrue(metadata.rows.isEmpty());assertNull(p.report.tradesCsv);assertNull(p.report.correctionsJson);
            long uncompressed=0;int entries=0;
            try(ZipInputStream in=new ZipInputStream(new FileInputStream(p.zip))){byte[] buffer=new byte[8192];while(in.getNextEntry()!=null){entries++;int n;while((n=in.read(buffer))!=-1)uncompressed+=n;}}
            assertEquals(7,entries);assertTrue(uncompressed>60L*1024*1024);
        }finally{if(p!=null)p.close();cleanup(root);}
    }
}
