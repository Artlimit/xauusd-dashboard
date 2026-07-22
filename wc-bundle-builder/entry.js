// entry.js
// จุดเริ่มต้นสำหรับ esbuild — ดึงเฉพาะฟังก์ชัน/ค่าที่แอปใช้จริงจาก WalletConnect/Reown มารวมเป็นไฟล์เดียว
// หลัง build แล้วไฟล์ walletconnect-bundle.js จะมี window.WCBundle.createAppKit, .WagmiAdapter ฯลฯ ให้เรียกใช้ตรงๆ
// โดยไม่ต้องโหลดอะไรจากอินเทอร์เน็ต/CDN อีกเลยตอนแอปรัน (นอกจาก WalletConnect protocol เองตอนเชื่อมกระเป๋าจริงๆ
// ซึ่งจำเป็นต้องมีเน็ตเสมอ เพราะเป็นการสื่อสารข้ามอุปกรณ์กับ relay server ไม่เกี่ยวกับไฟล์นี้)

import { createAppKit } from '@reown/appkit';
import { WagmiAdapter } from '@reown/appkit-adapter-wagmi';
import { polygon, bsc, arbitrum, base } from '@reown/appkit/networks';
import { reconnect, getConnectorClient } from '@wagmi/core';

export {
  createAppKit,
  WagmiAdapter,
  polygon,
  bsc,
  arbitrum,
  base,
  reconnect,
  getConnectorClient
};
