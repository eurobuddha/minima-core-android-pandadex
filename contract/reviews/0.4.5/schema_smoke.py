"""Host SQLite smoke test using DDL read from DexDb.java.

This validates the additive schema and SQLite rollback/reopen behavior. It does
not execute Android SQLiteOpenHelper or prove the Java wrapper on a device.
"""
from pathlib import Path
import json, re, sqlite3, tempfile

repo = Path(__file__).resolve().parents[3]
source = (repo/'app/src/main/java/com/eurobuddha/pandadex/DexDb.java').read_text()

def body(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    for i in range(opening + 1, len(source)):
        if source[i] == '{': depth += 1
        elif source[i] == '}': depth -= 1
        if depth == 0: return source[opening+1:i]
    raise AssertionError('Incomplete method')

def sql_statements(method):
    statements = []
    for expr in re.findall(r'db\.execSQL\((.*?)\);', body(method), re.S):
        fragments = re.findall(r'"(?:\\.|[^"\\])*"', expr)
        assert fragments and re.sub(r'"(?:\\.|[^"\\])*"|\+|\s', '', expr) == ''
        statements.append(''.join(json.loads(fragment) for fragment in fragments))
    return statements

ddl = sql_statements('void onCreate(')
migration = sql_statements('void createPendingFills(') + sql_statements('void createHistoryProgress(') + sql_statements('void createVerifiedSpends(') + sql_statements('void createChainChecks(') + sql_statements('void createReceiptAudit(') + sql_statements('void createTakerReceipts(')
assert 'if (oldV < 5) createPendingFills(db);' in body('void onUpgrade(')
assert 'if (oldV < 6) createHistoryProgress(db);' in body('void onUpgrade(')
with tempfile.TemporaryDirectory(prefix='pandadex-schema-045-') as tmp:
    path = Path(tmp)/'history.db'
    db = sqlite3.connect(path)
    for sql in ddl: db.execute(sql)
    # Pre-upgrade schema 4 data, including legacy records without verification fields.
    db.execute("INSERT INTO tape(spentcoin,timems,size) VALUES('old',123,'7')")
    db.execute("INSERT INTO mytrade(spentcoin,timems,size) VALUES('mine',125,'3')")
    db.execute("INSERT INTO meta(k,v) VALUES('sentinel','preserve')")
    db.commit()
    original = {table:list(db.execute('SELECT * FROM '+table)) for table in ('tape','mytrade','meta')}
    for _ in range(2):
        with db:
            for sql in migration: db.execute(sql)
    for table, rows in original.items(): assert list(db.execute('SELECT * FROM '+table)) == rows
    with db:
        for i in range(600): db.execute('INSERT INTO pendingfill(coinid,json,retry) VALUES(?,?,?)', ('coin'+str(i),'{}',i))
    db.execute("UPDATE pendingfill SET historical=1 WHERE coinid='coin599'")
    db.execute("INSERT INTO verifiedspend(coinid,txpowid,blockid,block) VALUES('verified','0xaabb','0xbeef',100)")
    db.execute("INSERT INTO historyprogress VALUES(0,'target',88)")
    db.execute("INSERT INTO historyprogress VALUES(1,'target',16)")
    db.commit()
    db.close()
    db = sqlite3.connect(path)
    assert db.execute('SELECT COUNT(*) FROM pendingfill').fetchone()[0] == 600
    assert db.execute("SELECT historical FROM pendingfill WHERE coinid='coin599'").fetchone()[0] == 1
    assert db.execute("SELECT txpowid FROM verifiedspend WHERE coinid='verified'").fetchone()[0] == '0xaabb'
    assert db.execute("SELECT offset FROM historyprogress WHERE scope=0 AND coinid='target'").fetchone()[0] == 88
    assert db.execute("SELECT offset FROM historyprogress WHERE scope=1 AND coinid='target'").fetchone()[0] == 16
    # The exact production selection/order SQL; persisted logical ordering survives reopen.
    batch_sql = re.search(r'"(SELECT coinid,json,historical FROM pendingfill[^"\n]+)"',source).group(1)
    batch = list(db.execute(batch_sql, (32,)))
    assert len(batch) == 32 and batch[0][0] == 'coin0'
    with db:
        seq = db.execute('SELECT COALESCE(MAX(retry),0)+1 FROM pendingfill').fetchone()[0]
        for coinid, *_ in batch:
            db.execute('UPDATE pendingfill SET retry=? WHERE coinid=?', (seq,coinid)); seq += 1
    assert db.execute(batch_sql,(1,)).fetchone()[0] == 'coin32'
    # Crash-equivalent rollback: uncommitted public insert cannot survive a failed personal write.
    db.execute("CREATE TRIGGER fail_personal BEFORE INSERT ON mytrade BEGIN SELECT RAISE(ABORT,'disk failure fixture'); END")
    db.commit()
    try:
        with db:
            db.execute("INSERT INTO tape(spentcoin) VALUES('atomic')")
            db.execute("INSERT INTO mytrade(spentcoin) VALUES('atomic')")
    except sqlite3.IntegrityError: pass
    else: raise AssertionError('Failure injection did not run')
    assert db.execute("SELECT COUNT(*) FROM tape WHERE spentcoin='atomic'").fetchone()[0] == 0
    db.close()
# Schema 6 already has queued receipts; adding the flag must keep their original bytes/order.
legacy = sqlite3.connect(':memory:')
legacy.execute('CREATE TABLE pendingfill(coinid TEXT PRIMARY KEY,json TEXT NOT NULL,retry INTEGER NOT NULL)')
legacy.execute("INSERT INTO pendingfill VALUES('old_candidate','original evidence',71)")
spec = re.search(r'addColumn\(db, "pendingfill", "([^"]+)"\)', body('void onUpgrade(')).group(1)
legacy.execute('ALTER TABLE pendingfill ADD COLUMN '+spec)
assert legacy.execute('SELECT * FROM pendingfill').fetchone() == ('old_candidate','original evidence',71,0)
legacy.close()
# Schema 8: original records remain unchanged; only the separate corroboration view changes.
review = sqlite3.connect(':memory:')
for sql in ddl: review.execute(sql)
for sql in migration: review.execute(sql)
review.execute("INSERT INTO tape(spentcoin,timems,price,size,buy,mine) VALUES('source',123,'0.01','100',1,1)")
review.execute("INSERT INTO mytrade(spentcoin,timems,block,price,size,buy,maker,txpowid,verification_status,verification_note,verified_block) VALUES('source',123,100,'0.01','100',1,1,'0xAABB','CHAIN_VERIFIED','original proof',100)")
review.execute("INSERT INTO verifiedspend(coinid,txpowid,blockid,block,sourcejson) VALUES('source','0xAABB','0xbeef',100,'original input JSON')")
original_trade = review.execute('SELECT * FROM mytrade').fetchone()
for sql in sql_statements('void createChainChecks('): review.execute(sql)
assert review.execute('SELECT COUNT(*) FROM chaincheck').fetchone()[0] == 1
assert review.execute('SELECT txpowid FROM chaincheck').fetchone()[0] == '0xaabb'
# Execute the real shared joins used by the production chart and receipt queries.
def returned_sql(signature):
    text=body(signature)
    expr=text[text.index('return ')+7:text.rindex(';')]
    return ''.join(json.loads(x) for x in re.findall(r'"(?:\\.|[^"\\])*"',expr))
tape_join=returned_sql('String tapeChecks(')
trade_select=returned_sql('String tradeChecks(')
chart='SELECT t.spentcoin'+tape_join+" WHERE t.settlement_kind='TRADE' AND (c.state IS NULL OR c.state<>'MISSING')"
assert review.execute(chart).fetchall() == [('source',)]
review.execute("UPDATE chaincheck SET state='MISSING',depth=-1,revision=1,checkedat=456 WHERE txpowid='0xaabb' AND revision=0")
assert review.execute(chart).fetchall() == []
assert len(review.execute('SELECT t.spentcoin'+tape_join).fetchall()) == 1
assert review.execute(trade_select).fetchone()[15] == 'MISSING'
assert review.execute('SELECT * FROM mytrade').fetchone() == original_trade
# Stale reply loses its compare-and-set race; an outage updates diagnostics, never restores missing proof.
review.execute("UPDATE chaincheck SET state='CURRENT',depth=4 WHERE txpowid='0xaabb' AND revision=0")
assert review.execute('SELECT state FROM chaincheck').fetchone()[0] == 'MISSING'
review.execute("UPDATE chaincheck SET error='offline',attemptedat=999,revision=2 WHERE txpowid='0xaabb' AND revision=1")
assert review.execute(chart).fetchall() == []
assert review.execute('SELECT checkedat,attemptedat FROM chaincheck').fetchone() == (456,999)
review.execute("UPDATE chaincheck SET state='CURRENT',depth=8,revision=3 WHERE txpowid='0xaabb' AND revision=2")
assert review.execute(chart).fetchall() == [('source',)]
assert review.execute('SELECT * FROM mytrade').fetchone() == original_trade
assert review.execute('SELECT sourcejson FROM verifiedspend').fetchone()[0] == 'original input JSON'
# The original v7 ledger gains only a source-evidence column, preserving its proof bytes.
v7=sqlite3.connect(':memory:');v7.execute('CREATE TABLE verifiedspend(coinid TEXT PRIMARY KEY,txpowid TEXT NOT NULL,blockid TEXT NOT NULL,block INTEGER NOT NULL)')
v7.execute("INSERT INTO verifiedspend VALUES('source','0xaabb','0xbeef',100)")
spec=re.search(r'addColumn\(db, "verifiedspend", "([^"]+)"\)',body('void onUpgrade(')).group(1)
v7.execute('ALTER TABLE verifiedspend ADD COLUMN '+spec)
assert v7.execute('SELECT * FROM verifiedspend').fetchone()==('source','0xaabb','0xbeef',100,'')
v7.close()
# Recent and old checks each receive slots; the actual query's durable revision order rotates.
for i in range(20):
    review.execute('INSERT INTO chaincheck(txpowid,block,revision) VALUES(?,?,?)',(f'0x{i+256:04x}',200 if i<10 else 20,i))
match=re.search(r'"SELECT txpowid,revision FROM chaincheck WHERE block" \+ comparison \+ "([^"]+)"',source)
assert match
for comparison in ('>=','<'):
    query='SELECT txpowid,revision FROM chaincheck WHERE block'+comparison+match.group(1)
    selected=review.execute(query,(100,)).fetchall();assert len(selected)==4
    first=selected[0][0]
    review.execute('UPDATE chaincheck SET revision=999 WHERE txpowid=?',(first,))
    assert review.execute(query,(100,)).fetchone()[0]!=first
# Atomic replacement rollback: archive, current rows, winner and candidate are inseparable.
review.execute("INSERT INTO pendingfill(coinid,json,retry) VALUES('source','retained candidate',1)")
review.commit()
prior={table:review.execute('SELECT * FROM '+table).fetchall() for table in ('tape','mytrade','verifiedspend','pendingfill','receiptaudit')}
review.execute("CREATE TRIGGER fail_correction BEFORE UPDATE ON mytrade BEGIN SELECT RAISE(ABORT,'correction failure fixture'); END")
review.commit()
def replace_receipt():
    snapshot=json.dumps({'personal_trade':dict(zip([x[0] for x in review.execute('SELECT * FROM mytrade').description],original_trade))})
    review.execute("INSERT INTO receiptaudit(coinid,oldtxpowid,newtxpowid,reason,correctedat,snapshot,summary) VALUES('source','0xaabb','0xdead','replacement',999,?,'{}')",(snapshot,))
    review.execute("UPDATE verifiedspend SET txpowid='0xdead',blockid='0xfeed',block=101 WHERE coinid='source'")
    review.execute("UPDATE tape SET size='40',price='0.02',timems=222 WHERE spentcoin='source'")
    review.execute("UPDATE mytrade SET size='40',price='0.02',timems=222,txpowid='0xdead' WHERE spentcoin='source'")
    review.execute("DELETE FROM pendingfill WHERE coinid='source'")
try:
    with review: replace_receipt()
except sqlite3.IntegrityError: pass
else: raise AssertionError('Correction failure injection did not run')
for table,rows in prior.items(): assert review.execute('SELECT * FROM '+table).fetchall()==rows
review.execute('DROP TRIGGER fail_correction');review.commit()
with review: replace_receipt()
assert review.execute("SELECT size,price,timems FROM mytrade WHERE spentcoin='source'").fetchone()==('40','0.02',222)
archived=json.loads(review.execute('SELECT snapshot FROM receiptaudit').fetchone()[0])
assert archived['personal_trade']['size']=='100' and archived['personal_trade']['timems']==123
assert review.execute("SELECT COUNT(*) FROM pendingfill WHERE coinid='source'").fetchone()[0]==0
# A refund correction remains visible but has no trade contribution; a later verified trade can restore it.
review.execute("UPDATE tape SET settlement_kind='NONTRADE' WHERE spentcoin='source'")
assert review.execute(chart).fetchall()==[]
assert review.execute('SELECT t.spentcoin'+tape_join).fetchall()==[('source',)]
review.execute("UPDATE tape SET settlement_kind='TRADE' WHERE spentcoin='source'")
assert review.execute(chart).fetchall()==[('source',)]
# A taker receipt may use its first source coin as the same key. Correcting the public
# source/maker row must NEVER rewrite that aggregate taker trade as a maker fill/refund.
maker_where=re.search(r'String MAKER_ROW = "([^"]+)"',source).group(1)
review.execute("INSERT INTO mytrade(spentcoin,timems,size,maker,verification_status) VALUES('taker_source',555,'900',0,'CHAIN_VERIFIED')")
taker_original=review.execute("SELECT * FROM mytrade WHERE spentcoin='taker_source'").fetchone()
review.execute("UPDATE mytrade SET verification_status='SUPERSEDED_NONTRADE' WHERE "+maker_where,('taker_source',))
review.execute("UPDATE mytrade SET size='10',timems=777 WHERE "+maker_where,('taker_source',))
assert review.execute("SELECT * FROM mytrade WHERE spentcoin='taker_source'").fetchone()==taker_original
assert review.execute('SELECT 1 FROM mytrade WHERE '+maker_where,('taker_source',)).fetchone() is None
review.close()

# Actual schema-8 rows gain only ordering/kind metadata when upgrading to schema 9.
v8=sqlite3.connect(':memory:')
v8.execute("CREATE TABLE chaincheck(txpowid TEXT PRIMARY KEY,state TEXT,proof_placeholder INTEGER)")
v8.execute("INSERT INTO chaincheck VALUES('0xaabb','MISSING',17)")
v8.execute("CREATE TABLE tape(spentcoin TEXT PRIMARY KEY,timems INTEGER,size TEXT)")
v8.execute("INSERT INTO tape VALUES('source',123,'100')")
for table,spec in re.findall(r'addColumn\(db, "(chaincheck|tape)", "([^"]+)"\)',body('void onUpgrade(')):
    v8.execute('ALTER TABLE '+table+' ADD COLUMN '+spec)
assert v8.execute('SELECT * FROM chaincheck').fetchone()==('0xaabb','MISSING',17,'',0)
assert v8.execute('SELECT * FROM tape').fetchone()==('source',123,'100','TRADE')
v8.close()
# Additive schema 10 creates a separate evidence store without inventing legacy expectations.
v9=sqlite3.connect(':memory:')
v9.execute("CREATE TABLE mytrade(spentcoin TEXT PRIMARY KEY,timems INTEGER,size TEXT)")
v9.execute("INSERT INTO mytrade VALUES('source',123,'100')")
for _ in range(2):
    for sql in sql_statements('void createTakerReceipts('):v9.execute(sql)
assert v9.execute('SELECT * FROM mytrade').fetchone()==('source',123,'100')
assert v9.execute('SELECT COUNT(*) FROM takerreceipt').fetchone()[0]==0
v9.execute("INSERT INTO takerreceipt(spentcoin,txpowid,block,blockid,json) VALUES('source','0xaabb',100,'0xbeef','original evidence')")
for sql in sql_statements('void createTakerReceipts('):v9.execute(sql)
assert v9.execute('SELECT json FROM takerreceipt').fetchone()[0]=='original evidence'
v9.close()
print(json.dumps({'schema_migration_preserves_existing_rows':True,
                  'migration_idempotent':True,'durable_candidates_after_reopen':600,
                  'independent_history_offsets_after_reopen':True,'batch_limit':32,'round_robin':True,'sqlite_failure_rollback':True,
                  'historical_flag_and_verified_spend_survive_reopen':True,'schema_6_candidates_preserved':True,
                  'schema_8_recheck_keeps_original_rows_visible':True,'missing_proof_excluded_from_chart':True,
                  'restored_proof_returns_to_chart':True,'stale_callback_rejected':True,'schema_7_ledger_preserved':True,'recent_and_old_checks_rotate':True,'correction_failure_rolls_back_all_records':True,'original_correction_snapshot_preserved':True,'nontrade_correction_visible_but_excluded':True,'schema_8_to_9_preserves_rows':True,'source_correction_cannot_overwrite_taker_receipt':True,
                  'schema_9_to_10_preserves_rows_and_does_not_invent_proof':True,'scope':'Host SQLite; not Android Java instrumentation'},indent=2))
