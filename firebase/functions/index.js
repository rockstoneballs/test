// Sunnyside's Cloud Functions. Deployed by .github/workflows/firebase.yml.
//
//   verifyPlaySubscription  callable: the app sends a Play purchase token after buying
//                           "Ad-free"; we check it with Google and mark the account.
//   playBillingNotifications Pub/Sub: Play's Real-time Developer Notifications (renewals,
//                           cancellations, refunds) keep accounts up to date.
//   deleteAccount           callable: deletes the reader's data and their account.

import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { androidpublisher, auth as googleAuth } from "@googleapis/androidpublisher";
import { setGlobalOptions, logger } from "firebase-functions/v2";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onMessagePublished } from "firebase-functions/v2/pubsub";
import { claimPurchase, deleteUserData, onPlayNotification } from "./subscriptions.js";

initializeApp();
setGlobalOptions({ region: "europe-west2", maxInstances: 10 });

const db = getFirestore();

/** The Play Developer API, signed in as the functions' own service account. */
const play = (() => {
  let client;
  const api = () => {
    client ??= androidpublisher({
      version: "v3",
      auth: new googleAuth.GoogleAuth({ scopes: ["https://www.googleapis.com/auth/androidpublisher"] }),
    });
    return client;
  };
  return {
    async getSubscription(packageName, token) {
      return (await api().purchases.subscriptionsv2.get({ packageName, token })).data;
    },
    async acknowledge(packageName, subscriptionId, token) {
      await api().purchases.subscriptions.acknowledge({ packageName, subscriptionId, token, requestBody: {} });
    },
  };
})();

export const verifyPlaySubscription = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const token = request.data?.purchaseToken;
  if (typeof token !== "string" || token.length < 10 || token.length > 4096) {
    throw new HttpsError("invalid-argument", "Missing purchase token.");
  }
  try {
    return await claimPurchase({ db, play, uid, token });
  } catch (e) {
    if (e.code === "permission-denied" || e.code === "invalid-argument") throw new HttpsError(e.code, e.message);
    logger.error("Could not verify a Play purchase", e);
    throw new HttpsError("unavailable", "Couldn't check the purchase with Google Play. Try again soon.");
  }
});

export const playBillingNotifications = onMessagePublished("play-billing", async (event) => {
  const message = event.data.message.json;
  if (message?.testNotification) {
    logger.info("Play test notification received");
    return;
  }
  const uid = await onPlayNotification({ db, play, message });
  logger.info("Play notification", { type: message?.subscriptionNotification?.notificationType, linked: Boolean(uid) });
});

export const deleteAccount = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  await deleteUserData({ db, uid });
  await getAuth().deleteUser(uid);
  return { deleted: true };
});
