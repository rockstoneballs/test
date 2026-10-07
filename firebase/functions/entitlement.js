// Pure logic: what a Google Play subscription purchase means for a reader's account.
// Kept free of Firebase so it can be unit tested (see test/).

import { createHash } from "node:crypto";

export const PACKAGE_NAME = "app.sunnyside.news";
export const AD_FREE_PRODUCT = "ad_free";

/** States in which Play says the subscriber has paid for now. */
const ENTITLED_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  // Auto-renew turned off: still paid up until the expiry time.
  "SUBSCRIPTION_STATE_CANCELED",
]);

/**
 * The account id the app attaches to every purchase (Play's obfuscatedAccountId): a
 * one-way hash of the Firebase user id, as Google asks, never the id itself.
 * The app computes the same thing (see Accounts.kt).
 */
export function accountHash(uid) {
  return createHash("sha256").update(uid).digest("hex");
}

/** Purchase tokens are long; Firestore document ids are kept short and safe. */
export function tokenKey(token) {
  return createHash("sha256").update(token).digest("hex");
}

/**
 * Reads a SubscriptionPurchaseV2 (purchases.subscriptionsv2.get) for the ad-free product.
 * Returns { adFree, adFreeUntil (ms or null), state, owner (account hash or null), productIds, needsAck }.
 */
export function readPurchase(purchase, now = Date.now()) {
  const items = (purchase?.lineItems || []).filter((i) => i.productId === AD_FREE_PRODUCT);
  const expiries = items.map((i) => Date.parse(i.expiryTime)).filter((t) => Number.isFinite(t));
  const adFreeUntil = expiries.length ? Math.max(...expiries) : null;
  const state = purchase?.subscriptionState || "SUBSCRIPTION_STATE_UNSPECIFIED";
  const adFree = ENTITLED_STATES.has(state) && adFreeUntil !== null && adFreeUntil > now;
  return {
    adFree,
    adFreeUntil,
    state,
    owner: purchase?.externalAccountIdentifiers?.obfuscatedExternalAccountId || null,
    productIds: items.map((i) => i.productId),
    needsAck: purchase?.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING" && adFree,
    linkedPurchaseToken: purchase?.linkedPurchaseToken || null,
  };
}

/**
 * May this signed-in user claim this purchase? It must be for our product, made from
 * their account (when the app tagged it), and not already claimed by someone else.
 * Returns null if fine, or a reason.
 */
export function claimProblem(read, uid, existingOwnerUid) {
  if (!read.productIds.length) return "not-our-product";
  if (read.owner && read.owner !== accountHash(uid)) return "other-account";
  if (existingOwnerUid && existingOwnerUid !== uid) return "already-claimed";
  return null;
}
