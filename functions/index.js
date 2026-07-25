/**
 * ai-gateway-functions/index.js
 * ------------------------------------------------------------------
 * เกตเวย์กลางสำหรับผู้ช่วย AI ของ PuppyPocket
 *
 * แอปจะไม่เรียก Anthropic API ตรงจากเครื่องผู้ใช้อีกต่อไป (ซึ่งต้องฝัง API Key ไว้ในแอป เสี่ยงหลุด)
 * แต่จะเรียกฟังก์ชันนี้แทน — ฟังก์ชันนี้ถือ API Key ไว้อย่างปลอดภัยฝั่งเซิร์ฟเวอร์ (ไม่มีวันหลุดไปถึงเครื่องผู้ใช้)
 * แล้วค่อยส่งต่อคำขอไป Anthropic ให้ พร้อมมีการเช็คสิทธิ์ + จำกัดการใช้งานต่อวันกันการใช้เกิน
 *
 * ก่อน deploy ต้องตั้งค่า API Key ก่อน (รันครั้งเดียว):
 *   firebase functions:config:set anthropic.key="sk-ant-xxxxxxxx"
 * ------------------------------------------------------------------
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

const db = admin.firestore();

// จำกัดจำนวนครั้งที่เรียกผู้ช่วย AI ได้ต่อผู้ใช้ 1 คนต่อวัน กันการใช้เกิน/ป้องกันบิลบาน
// ปรับตัวเลขนี้ได้ตามความเหมาะสม
const DAILY_MESSAGE_LIMIT = 200;

exports.aiGateway = functions
  .runWith({ timeoutSeconds: 60, memory: "256MB" })
  .https.onCall(async (data, context) => {
    // 1) ต้องผ่านการล็อกอินก่อน (แอปทำ signInAnonymously() ให้อัตโนมัติอยู่แล้วตอนเปิดแอป)
    if (!context.auth) {
      throw new functions.https.HttpsError(
        "unauthenticated",
        "กรุณาเปิดแอปใหม่อีกครั้ง (ยังไม่ได้ยืนยันตัวตน)"
      );
    }
    const uid = context.auth.uid;

    // 2) ตรวจข้อมูลที่ส่งมาว่าครบถ้วน/รูปแบบถูกต้องหรือไม่
    const { messages, system, maxTokens } = data || {};
    if (!Array.isArray(messages) || messages.length === 0) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "ข้อมูลบทสนทนาที่ส่งมาไม่ถูกต้อง"
      );
    }
    if (typeof system !== "string") {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "system prompt ไม่ถูกต้อง"
      );
    }

    // 3) เช็ค + อัปเดตจำนวนครั้งที่ใช้ไปวันนี้ (กันเรียกเกิน)
    const todayKey = new Date().toISOString().slice(0, 10); // เช่น "2026-07-24"
    const usageRef = db.collection("ai_usage").doc(uid + "_" + todayKey);

    const allowed = await db.runTransaction(async (tx) => {
      const doc = await tx.get(usageRef);
      const count = doc.exists ? doc.data().count || 0 : 0;
      if (count >= DAILY_MESSAGE_LIMIT) return false;
      tx.set(usageRef, { count: count + 1, updatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
      return true;
    });

    if (!allowed) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "วันนี้ใช้ผู้ช่วย AI ครบโควต้าแล้ว กรุณาลองใหม่พรุ่งนี้"
      );
    }

    // 4) เรียก Anthropic API จริง ด้วย API Key ที่เก็บไว้ฝั่งเซิร์ฟเวอร์ (ไม่มีทางหลุดไปถึงแอป)
    const apiKey = functions.config().anthropic && functions.config().anthropic.key;
    if (!apiKey) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "เซิร์ฟเวอร์ยังไม่ได้ตั้งค่า API Key (ผู้ดูแลระบบต้องตั้งค่าก่อน)"
      );
    }

    let response;
    try {
      response = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": apiKey,
          "anthropic-version": "2023-06-01",
        },
        body: JSON.stringify({
          model: "claude-sonnet-5",
          max_tokens: maxTokens || 1024,
          system: system,
          messages: messages,
        }),
      });
    } catch (networkErr) {
      throw new functions.https.HttpsError(
        "unavailable",
        "เชื่อมต่อ Anthropic API ไม่สำเร็จ: " + networkErr.message
      );
    }

    if (!response.ok) {
      const errText = await response.text();
      throw new functions.https.HttpsError(
        "internal",
        "Anthropic API error " + response.status + ": " + errText
      );
    }

    const result = await response.json();
    return result; // ส่งกลับให้แอปในรูปแบบเดียวกับที่ Anthropic ตอบมาเป๊ะๆ ไม่ต้องแปลงอะไรเพิ่ม
  });
