// Keeps users/{uid} in step with their Google Play subscription. Firestore and the Play
// Developer API are passed in, so tests can use fakes.

import { PACKAGE_NAME, claimProblem, readPurchase, tokenKey } from "./entitlement.js";

/**
 * Checks a purchase token with Google Play and records the result on the user's account.
 * Called by the app right after a purchase, and again whenever it finds an active
 * subscription. Throws Error with .code = "permission-denied" | "invalid-argument".
 */
export async function claimPurchase({ db, play, uid, token, now = Date.now() }) {
  const purchase = await play.getSubscription(PACKAGE_NAME, token);
  const read = readPurchase(purchase, now);
  const tokenDoc = db.doc(`playTokens/${tokenKey(token)}`);
  const existing = (await tokenDoc.get()).data();
  const problem = claimProblem(read, uid, existing?.uid);
  if (problem) {
    const err = new Error(problem);
    err.code = problem === "not-our-product" ? "invalid-argument" : "permission-denied";
    throw err;
  }
  if (read.needsAck) {
    await play.acknowledge(PACKAGE_NAME, read.productIds[0], token);
  }
  await tokenDoc.set({ uid, updatedAt: now });
  if (read.linkedPurchaseToken) {
    // A resubscribe or plan change replaces the old token.
    await db.doc(`playTokens/${tokenKey(read.linkedPurchaseToken)}`).delete();
  }
  await writeUser(db, uid, read, now);
  return { adFree: read.adFree, adFreeUntil: read.adFreeUntil };
}

/**
 * Handles a Real-time Developer Notification from Play (renewed, cancelled, expired,
 * refunded…): re-reads the subscription and updates whoever claimed it.
 * Returns the uid updated, or null if the token isn't linked to an account yet.
 */
export async function onPlayNotification({ db, play, message, now = Date.now() }) {
  const sub = message?.subscriptionNotification;
  if (!sub?.purchaseToken || (message.packageName && message.packageName !== PACKAGE_NAME)) return null;
  const owner = (await db.doc(`playTokens/${tokenKey(sub.purchaseToken)}`).get()).data();
  if (!owner?.uid) return null; // the app links it when the reader next opens it
  const read = readPurchase(await play.getSubscription(PACKAGE_NAME, sub.purchaseToken), now);
  await writeUser(db, owner.uid, read, now);
  return owner.uid;
}

async function writeUser(db, uid, read, now) {
  await db.doc(`users/${uid}`).set(
    {
      adFree: read.adFree,
      adFreeUntil: read.adFreeUntil,
      subscriptionState: read.state,
      updatedAt: now,
    },
    { merge: true },
  );
}

/** Deletes everything stored about a reader (their account itself is deleted by the caller). */
export async function deleteUserData({ db, uid }) {
  const tokens = await db.collection("playTokens").where("uid", "==", uid).get();
  await Promise.all(tokens.docs.map((d) => d.ref.delete()));
  await db.doc(`users/${uid}`).delete();
}
