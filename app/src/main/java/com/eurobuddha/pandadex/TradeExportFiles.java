package com.eurobuddha.pandadex;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** One-record-at-a-time snapshot/CSV/ZIP pipeline. Source receipts are never truncated. */
final class TradeExportFiles {
    static final int MAX_RECORD_BYTES=4*1024*1024;
    private static final Set<File> LIVE=new HashSet<>();
    private static final String[] ENTRIES={TradeExport.FILE_SUMMARY,TradeExport.FILE_TRADES,
            TradeExport.FILE_RECONCILIATION,TradeExport.FILE_VERIFICATION,
            TradeExport.FILE_CORRECTIONS,TradeExport.FILE_TAKER_RECEIPTS,TradeExport.FILE_OWNER_RECEIPTS};
    interface Sink {
        void trade(TradeExport.TradeRow row) throws Exception;
        void correction(String json) throws Exception;
        void taker(String json) throws Exception;
        void owner(String json) throws Exception;
    }
    interface Source {void capture(TradeExport.Snapshot metadata,Sink sink) throws Exception;}
    static final class Prepared implements AutoCloseable {
        final File directory,zip;
        final String filename;
        final TradeExport.Report report;
        private boolean closed,saving;
        Prepared(File directory,String filename,TradeExport.Report report){
            this.directory=directory;zip=new File(directory,"report.zip");this.filename=filename;this.report=report;
        }
        synchronized void claim(){if(closed||saving)throw new IllegalStateException("Export is no longer available");saving=true;}
        synchronized void saved(){saving=false;close();}
        @Override public synchronized void close(){if(closed||saving)return;closed=true;discard(directory);}
    }
    private TradeExportFiles(){}
    static Prepared prepare(File cache,TradeExport.Snapshot metadata,Source source,TradeExport.ExternalVerifier verifier) throws Exception {
        File root=new File(cache,"trade-exports");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Could not create private export folder");
        final File directory;
        synchronized(LIVE){
            File[] stale=root.listFiles();
            if(stale!=null)for(File f:stale)if(f.isDirectory()&&f.getName().matches("job-[0-9]+")&&!LIVE.contains(f))discard(f);
            directory=Files.createTempDirectory(root.toPath(),"job-").toFile();LIVE.add(directory);
        }
        boolean success=false;
        try {
            // Release the coherent database snapshot before any external lookup or save provider.
            try(DataOutputStream rows=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(directory,"rows.bin"))));
                Writer corrections=writer(directory,TradeExport.FILE_CORRECTIONS);
                Writer takers=writer(directory,TradeExport.FILE_TAKER_RECEIPTS);
                Writer owners=writer(directory,TradeExport.FILE_OWNER_RECEIPTS)) {
                corrections.write('[');takers.write('[');owners.write('[');boolean[] first={true,true,true};
                source.capture(metadata,new Sink(){
                    public void trade(TradeExport.TradeRow row) throws Exception {
                        validNumber(row.price);validNumber(row.sizeMinima);
                        byte[] bytes=encode(row).getBytes(StandardCharsets.UTF_8);
                        if(bytes.length>MAX_RECORD_BYTES)throw new IOException("A stored trade is too large to export safely; source retained");
                        rows.writeInt(bytes.length);rows.write(bytes);
                    }
                    public void correction(String json) throws Exception {arrayRecord(corrections,json,first,0);}
                    public void taker(String json) throws Exception {arrayRecord(takers,json,first,1);}
                    public void owner(String json) throws Exception {arrayRecord(owners,json,first,2);}
                });
                corrections.write(']');takers.write(']');owners.write(']');
            }
            TradeExport.ExternalVerifier checks=verifier==null?null:new ExportChecks(verifier);
            TradeExport.Report report=new TradeExport.Report();report.totals=new TradeExport.Totals();
            try(DataInputStream rows=new DataInputStream(new BufferedInputStream(new FileInputStream(new File(directory,"rows.bin"))));
                Writer trades=writer(directory,TradeExport.FILE_TRADES);Writer verified=writer(directory,TradeExport.FILE_VERIFICATION)) {
                trades.write(TradeExport.TRADESCSV_HEADER);verified.write(TradeExport.VERIFICATIONCSV_HEADER);
                byte[] bytes;
                while((bytes=nextRecord(rows))!=null){
                    TradeExport.TradeRow row=TradeExport.verify(decode(new String(bytes,StandardCharsets.UTF_8)),checks);
                    TradeExport.include(report.totals,row);trades.write(TradeExport.tradeCsvRow(row));verified.write(TradeExport.verificationCsvRow(row));
                    report.tradeCount=Math.incrementExact(report.tradeCount);
                }
            }
            TradeExport.finishTotals(metadata,report.totals);
            report.summaryTxt=TradeExport.summary(metadata,report.totals,report.tradeCount);
            report.reconciliationCsv=TradeExport.reconciliationCsv(metadata,report.totals,report.tradeCount);
            try(Writer w=writer(directory,TradeExport.FILE_SUMMARY)){w.write(report.summaryTxt);}
            try(Writer w=writer(directory,TradeExport.FILE_RECONCILIATION)){w.write(report.reconciliationCsv);}
            File partial=new File(directory,"report.part"),zip=new File(directory,"report.zip");
            try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))){
                for(String name:ENTRIES){out.putNextEntry(new ZipEntry(name));try(InputStream in=new FileInputStream(new File(directory,name))){copy(in,out);}out.closeEntry();}
            }
            if(!partial.renameTo(zip))throw new IOException("Could not finish the private export");
            // Keep only the finished ZIP while the save picker is open.
            for(String name:ENTRIES)new File(directory,name).delete();new File(directory,"rows.bin").delete();
            success=true;return new Prepared(directory,TradeExport.filename(metadata.exportedAtMs),report);
        }finally{if(!success)discard(directory);}
    }
    private static Writer writer(File dir,String name) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir,name)),StandardCharsets.UTF_8));
    }
    private static void arrayRecord(Writer writer,String json,boolean[] first,int index) throws Exception {
        if(json==null||json.length()>MAX_RECORD_BYTES||json.getBytes(StandardCharsets.UTF_8).length>MAX_RECORD_BYTES)
            throw new IOException("Stored receipt evidence is too large to export safely; source retained");
        // Reuse the existing archive's object validation, one record at a time.
        String object=MakerConfig.storedObject(json).toString();
        if(!first[index])writer.write(',');first[index]=false;writer.write(object);
    }
    static byte[] nextRecord(DataInputStream in) throws IOException {
        int first=in.read();if(first<0)return null;
        int length=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
        if(length<1||length>MAX_RECORD_BYTES)throw new IOException("Invalid private export record length");
        byte[] bytes=new byte[length];in.readFully(bytes);return bytes;
    }
    static String encode(TradeExport.TradeRow r) {
        return new JSONArray().put(r.spentCoin).put(r.timeMs).put(r.block).put(r.price.toString()).put(r.sizeMinima.toString())
                .put(r.buy).put(r.maker).put(r.orderId).put(r.txpowid).put(r.sourceKind).put(r.sourceCoinids)
                .put(r.proceedsCoinid).put(r.verificationStatus).put(r.verificationNote).put(r.verifiedBlock).toString();
    }
    static TradeExport.TradeRow decode(String text) throws Exception {
        JSONArray a=new JSONArray(text);if(a.length()!=15)throw new IOException("Invalid private export row");
        BigDecimal price=new BigDecimal(a.getString(3)),size=new BigDecimal(a.getString(4));validNumber(price);validNumber(size);
        return new TradeExport.TradeRow(a.getString(0),a.getLong(1),a.getLong(2),price,size,a.getBoolean(5),a.getBoolean(6),a.getString(7),
                a.getString(8),a.getString(9),a.getString(10),a.getString(11),a.getString(12),a.getString(13),a.getLong(14));
    }
    private static void validNumber(BigDecimal v) throws IOException {
        // Same decimal bound as Util/DexTxn, without substituting a guessed amount on failure.
        if(v==null||Math.abs((long)v.scale())>44||v.precision()>44)throw new IOException("Stored trade has an unsupported amount; source retained");
    }
    static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
    }
    private static void discard(File directory){
        synchronized(LIVE){LIVE.remove(directory);}
        for(String name:ENTRIES)new File(directory,name).delete();
        for(String name:new String[]{"rows.bin","report.part","report.zip"})new File(directory,name).delete();
        directory.delete();
    }
}
