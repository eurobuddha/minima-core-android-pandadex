package com.eurobuddha.pandadex;
import org.junit.Test;
import static org.junit.Assert.*;
public class TransactionProgressTest {
    @Test public void brokenPresentationCannotAbortFundsOperation() {
        DexTxn.Result callback=new DexTxn.Result(){public void onProgress(String message){throw new IllegalStateException("view gone");}
            public void onPosted(String id){}public void onFailed(String message){fail("progress must not change outcome");}};
        DexTxn.progress(callback,"Signing transaction");
    }
}
