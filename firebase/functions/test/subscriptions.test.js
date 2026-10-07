import { test } from "node:test";
import assert from "node:assert/strict";
import { accountHash, readPurchase, tokenKey } from "../entitlement.js";
import { claimPurchase, deleteUserData, onPlayNotification } from "../subscriptions.js";

const NOW = Date.parse("2026-10-07T12:00:00Z");
const DAY = 86_400_000;

/** Just enough of Firestore for the code under test. */
function fakeDb() {
  const docs = new Map();
  const ref = (path) => ({
    path,
    async get() { return { data: () => docs.get(path) }; },
    async set(value, opts) { docs.set(path, opts?.merge ? { ...docs.get(path), ...value } : value); },
    async delete() { docs.delete(path); },
  });
  return {
    docs,
    doc: ref,
    collection: (name) => ({
      where: (field, _op, value) => ({
        async get() {
          const hits = [...docs].filter(([p, v]) => p.startsWith(name + "/") && v[field] === value);
          return { docs: hits.map(([p]) => ({ ref: ref(p) })) };
        },
      }),
    }),
  };
}

function fakePlay(purchases) {
  const acked = [];
  return {
    acked,
    async getSubscription(_pkg, token) {
      if (!purchases[token]) throw new Error("unknown token");
      return purchases[token];
    },
    async acknowledge(_pkg, productId, token) { acked.push([productId, token]); },
  };
}

const purchase = ({ state = "SUBSCRIPTION_STATE_ACTIVE", expires = NOW + 30 * DAY, owner = accountHash("alice"), ack = "ACKNOWLEDGEMENT_STATE_PENDING", product = "ad_free" } = {}) => ({
  subscriptionState: state,
  acknowledgementState: ack,
  externalAccountIdentifiers: owner ? { obfuscatedExternalAccountId: owner } : undefined,
  lineItems: [{ productId: product, expiryTime: new Date(expires).toISOString() }],
});

test("active, grace period and cancelled-but-paid-up subscriptions are ad-free", () => {
  assert.equal(readPurchase(purchase(), NOW).adFree, true);
  assert.equal(readPurchase(purchase({ state: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" }), NOW).adFree, true);
  assert.equal(readPurchase(purchase({ state: "SUBSCRIPTION_STATE_CANCELED" }), NOW).adFree, true);
});

test("expired, on hold, paused and lapsed subscriptions are not", () => {
  for (const state of ["SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_PENDING"]) {
    assert.equal(readPurchase(purchase({ state }), NOW).adFree, false, state);
  }
  assert.equal(readPurchase(purchase({ state: "SUBSCRIPTION_STATE_CANCELED", expires: NOW - 1 }), NOW).adFree, false);
});

test("claiming a purchase acknowledges it and marks the account ad-free", async () => {
  const db = fakeDb();
  const play = fakePlay({ tok1: purchase() });
  const result = await claimPurchase({ db, play, uid: "alice", token: "tok1", now: NOW });
  assert.deepEqual(result, { adFree: true, adFreeUntil: NOW + 30 * DAY });
  assert.deepEqual(play.acked, [["ad_free", "tok1"]]);
  assert.equal(db.docs.get("users/alice").adFree, true);
  assert.equal(db.docs.get(`playTokens/${tokenKey("tok1")}`).uid, "alice");
});

test("an already-acknowledged purchase isn't acknowledged again", async () => {
  const play = fakePlay({ tok1: purchase({ ack: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" }) });
  await claimPurchase({ db: fakeDb(), play, uid: "alice", token: "tok1", now: NOW });
  assert.deepEqual(play.acked, []);
});

test("someone else's purchase can't be claimed", async () => {
  const db = fakeDb();
  const play = fakePlay({ tok1: purchase(), tok2: purchase({ owner: null }) });
  await assert.rejects(claimPurchase({ db, play, uid: "mallory", token: "tok1", now: NOW }), { code: "permission-denied" });
  await claimPurchase({ db, play, uid: "bob", token: "tok2", now: NOW }); // untagged: first claimer keeps it
  await assert.rejects(claimPurchase({ db, play, uid: "mallory", token: "tok2", now: NOW }), { code: "permission-denied" });
  assert.equal(db.docs.get("users/mallory"), undefined);
});

test("other products are refused", async () => {
  const play = fakePlay({ tok1: purchase({ product: "something_else" }) });
  await assert.rejects(claimPurchase({ db: fakeDb(), play, uid: "alice", token: "tok1", now: NOW }), { code: "invalid-argument" });
});

test("a cancellation notification turns ads back on once the paid period ends", async () => {
  const db = fakeDb();
  const purchases = { tok1: purchase() };
  const play = fakePlay(purchases);
  await claimPurchase({ db, play, uid: "alice", token: "tok1", now: NOW });
  purchases.tok1 = purchase({ state: "SUBSCRIPTION_STATE_EXPIRED", expires: NOW - DAY });
  const uid = await onPlayNotification({
    db, play, now: NOW,
    message: { packageName: "app.sunnyside.news", subscriptionNotification: { notificationType: 13, purchaseToken: "tok1" } },
  });
  assert.equal(uid, "alice");
  assert.equal(db.docs.get("users/alice").adFree, false);
});

test("notifications for tokens nobody has claimed are ignored", async () => {
  const uid = await onPlayNotification({
    db: fakeDb(), play: fakePlay({}), now: NOW,
    message: { subscriptionNotification: { notificationType: 4, purchaseToken: "unknown" } },
  });
  assert.equal(uid, null);
});

test("deleting an account removes the user and their purchase links", async () => {
  const db = fakeDb();
  await claimPurchase({ db, play: fakePlay({ tok1: purchase() }), uid: "alice", token: "tok1", now: NOW });
  await deleteUserData({ db, uid: "alice" });
  assert.equal(db.docs.size, 0);
});
