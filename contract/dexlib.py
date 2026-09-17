"""PandaDEX V5 contract toolkit — talks to a PRIVATE -solo node (never a live node).

Adapted from the proven PandaPools harness (pplib.py). Blocks on the solo chain advance when
a transaction is posted, so we mine on demand by self-sending; coins need ~3 confirmations
before they are spendable.

Solo node: java -jar minima-core/jar/minima.jar -solo -data <dir> -port 19101 -rpc 19105 -rpcenable
(ports offset from the pandapools harness so the two can't collide).
"""
import json, os, string, time, urllib.parse, urllib.request

RPC = os.environ.get("PANDADEX_TEST_RPC", "")
HERE = os.path.dirname(os.path.abspath(__file__))
V5_TPL = string.Template(open(os.path.join(HERE, "v5script.tpl")).read())

# Mainnet values (frozen at release; tests substitute their own token + short expiry)
MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"
EXPIRY = 600


def rpc(cmd, timeout=180):
    if os.environ.get("PANDADEX_DISPOSABLE_SOLO") != "YES" or not RPC:
        raise RuntimeError("Refusing node access: explicitly set PANDADEX_TEST_RPC and PANDADEX_DISPOSABLE_SOLO=YES for your disposable solo node")
    parsed = urllib.parse.urlparse(RPC)
    if parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise RuntimeError("Disposable harness requires a loopback RPC endpoint")
    url = RPC.rstrip("/") + "/" + urllib.parse.quote(cmd)
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def ok(cmd, **kw):
    r = rpc(cmd, **kw)
    if not r.get("status"):
        raise RuntimeError("cmd failed: " + cmd[:120] + " -> " + str(r.get("error") or r.get("response")))
    return r["response"]


def oneline(s):
    return " ".join(s.split())


def v5_script(tok=MXUSD, exp=EXPIRY):
    return oneline(V5_TPL.substitute(TOK=tok, EXP=str(exp)))


def clean_script(script):
    """Return (clean_script_string, address_0x, mxaddress, parseok)."""
    r = ok("runscript script:" + json.dumps(oneline(script)))
    sc = r["script"]
    return sc["script"], sc["address"], sc["mxaddress"], bool(sc.get("parseok", r.get("parseok", True)))


def block():
    return int(ok("block")["block"])


def getaddress():
    r = ok("getaddress")
    return r["address"], r["miniaddress"], r["publickey"]


def newaddress():
    r = ok("newaddress")
    return r["address"], r["miniaddress"], r["publickey"]


def advance(n=1):
    """Force n blocks by self-sending dust (solo chain mines on tx)."""
    _, mx, _ = getaddress()
    start = block()
    for _ in range(n):
        try:
            rpc("send amount:0.0001 address:" + mx, timeout=180)
        except Exception:
            pass
    for _ in range(60):
        if block() >= start + n:
            return block()
        time.sleep(1)
    return block()


def coins(address, extra=""):
    r = rpc("coins address:" + address + (" " + extra if extra else ""))
    resp = r.get("response")
    return resp if isinstance(resp, list) else []


def coin_by_id(coinid):
    r = rpc("coins coinid:" + coinid)
    resp = r.get("response")
    return (resp or [None])[0] if isinstance(resp, list) else None


def send_to(address, amount, tokenid="0x00", state=None):
    cmd = f"send amount:{amount} address:{address}"
    if tokenid != "0x00":
        cmd += " tokenid:" + tokenid
    if state:
        cmd += " state:" + json.dumps(state)
    return ok(cmd)


def bal(tokenid="0x00"):
    r = rpc("balance tokenid:" + tokenid)
    resp = r.get("response")
    if isinstance(resp, list) and resp:
        return resp[0]
    return resp


def state_of(coin):
    """Coin state as {port:int -> value:str}; handles map and array forms."""
    st = coin.get("state")
    out = {}
    if isinstance(st, dict):
        for k, v in st.items():
            out[int(k)] = str(v)
    elif isinstance(st, list):
        for e in st:
            out[int(e.get("port"))] = str(e.get("data"))
    return out


# ---- txn construction helpers ----

def txn(txid, steps, cleanup=True):
    """Run a txncreate chain; on any failure txndelete and raise. Returns the last response."""
    try:
        ok("txncreate id:" + txid)
        last = None
        for s in steps:
            last = ok(s.replace("$ID", txid))
        return last
    except Exception:
        if cleanup:
            try:
                rpc("txndelete id:" + txid)
            except Exception:
                pass
        raise
    finally:
        if cleanup:
            try:
                rpc("txndelete id:" + txid)
            except Exception:
                pass


def txn_expect_fail(txid, steps, failing_step_hint=""):
    """Run a chain expecting SOME step (typically txnpost/txncheck) to fail. Returns the error text."""
    try:
        txn(txid, steps)
    except RuntimeError as e:
        return str(e)
    raise AssertionError("ADVERSARIAL VECTOR ACCEPTED (should have been rejected): " + failing_step_hint)


def statejson(ports):
    """txnstate-ready dict {port:str}."""
    return {str(k): str(v) for k, v in ports.items()}
