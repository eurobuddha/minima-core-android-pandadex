package org.minimarex.minimaapi;
import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MinimaAPIResponseTest {
    @Test public void transportFailuresCannotClaimNodeRejection() {
        JSONObject r=MinimaAPIResponse.failure("read failed");
        assertFalse(r.has("status")); assertFalse(r.has("enabled")); assertTrue(r.has("transporterror"));
    }
    @Test public void completeNodeReplyIsPreservedIncludingRealRejection() {
        JSONObject r=MinimaAPIResponse.parse(" {\"status\":false,\"error\":\"rejected\"} ");
        assertEquals(Boolean.FALSE,r.opt("status"));assertEquals("rejected",r.optString("error"));
        assertEquals("0xaa",MinimaAPIResponse.parse("{\"status\":true,\"txpowid\":\"0xaa\"}").optString("txpowid"));
    }
    @Test public void incompleteMalformedAndTrailingPayloadsStayUncertain() {
        for(String s:new String[]{"{\"status\":true}\0junk",null,"","{invalid","[]","true","{\"status\":true}garbage","{\"status\":true}{\"status\":false}"}) {
            JSONObject r=MinimaAPIResponse.parse(s);assertFalse(r.has("status"));assertTrue(r.has("transporterror"));
        }
    }
    @Test public void trailingCommentsAndNonJsonWhitespaceCannotClaimAValidNodeReply() {
        for(String suffix:new String[]{" // hidden"," /* hidden */"," # hidden","\f","\u0001"}) {
            JSONObject r=MinimaAPIResponse.parse("{\"status\":true}"+suffix);
            assertFalse(r.has("status"));assertTrue(r.has("transporterror"));
        }
        assertEquals(Boolean.TRUE,MinimaAPIResponse.parse("{\"status\":true} \t\r\n").opt("status"));
    }
    @Test public void streamingLimitIncludesExactBoundary() throws Exception {
        int limit=MinimaAPIResponse.MAX_BYTES;
        assertEquals(limit,MinimaAPIResponse.read(new ByteArrayInputStream(new byte[limit])).length());
        assertThrows(IOException.class,()->MinimaAPIResponse.read(new ByteArrayInputStream(new byte[limit+1])));
        InputStream broken=new InputStream(){public int read() throws IOException {throw new IOException("broken");}};
        assertThrows(IOException.class,()->MinimaAPIResponse.read(broken));
    }
    @Test public void bothInlineCharactersAndUtf8BytesAreBounded() throws Exception {
        String prefix="{\"value\":\"", suffix="\"}";
        String exact=prefix+"x".repeat(MinimaAPIResponse.MAX_BYTES-prefix.length()-suffix.length())+suffix;
        assertTrue(MinimaAPIResponse.parse(exact).has("value"));
        assertTrue(MinimaAPIResponse.parse(exact+" ").has("transporterror"));
        String multibyte=prefix+"€".repeat(MinimaAPIResponse.MAX_BYTES/3)+suffix;
        assertTrue(multibyte.length()<MinimaAPIResponse.MAX_BYTES);
        assertTrue(MinimaAPIResponse.parse(multibyte).has("transporterror"));
    }
    @Test public void pairingIdsAcceptExistingCaseButNotPartialCorruption() {
        assertTrue(MinimaAPI.validPairingId("0x"+"abCD".repeat(8)));
        for(String s:new String[]{"{\"status\":true}\0junk",null,"","0xaa","0x"+"gg".repeat(16),"0x"+"aa".repeat(17)})
            assertFalse(MinimaAPI.validPairingId(s));
    }
}
