import type { GoldieConfig } from "goldie/src/config.ts";

/**
 * Google Play 商店截图。iOS 那一档不在这里出——Vana-iOS 有自己的仓库。
 *
 * 截图跑在 `Vana_Pixel9Pro` 这个 AVD 上（1280 x 2856，goldie 的 Pixel 10 Pro 边框
 * 就是照这个几何切的）。屏幕上的内容是预置的演示数据，不是真跑模型出来的：
 * `scripts/seed-demo-data.py` 生成 JSON，`run-as` 写进 debug 包的 filesDir。
 * 换句话说这些图可复现，也不需要把任何 API key 带进流程。
 */

const APP_ROOT = "/Users/junyizhang/Git/Vana-Android";

const config: GoldieConfig = {
  appRoot: APP_ROOT,
  appPath: "",
  bundleId: "com.pinapia.vana",

  // debug 包，不是 release：演示数据靠 `run-as` 写进去，那要求包是 debuggable 的。
  // Compose 没有 LogBox 那类调试浮层，debug 和 release 在截图里看不出区别。
  android: {
    appPath: `${APP_ROOT}/app/build/outputs/apk/github/debug/app-github-debug.apk`,
    applicationId: "com.pinapia.vana",
  },

  devices: ["pixel-10-pro"],
  locales: ["zh-CN"],
  appearance: "light",

  // 这个 variant 是 iPhone 的边框art，Android 用的是内置的 Pixel 10 Pro 边框；
  // 但配置校验要求这个字段存在，所以留着。
  frame: { variant: "17-pro-blue" },

  theme: {
    // 和 1.0.9 的品牌色板同源：从 app 图标那朵蓝云取色，再压一档饱和度。
    background: "linear-gradient(160deg, #DCE8FB 0%, #F0F5FD 55%, #F7F9FD 100%)",
    headlineColor: "#1E2635",
    subheadColor: "#5A6E8C",
    // 中文标题必须走 Noto Sans SC，系统栈在渲染进程里会掉字。
    fontFamily: '"Noto Sans SC", system-ui, sans-serif',
    copyHeightRatio: 0.26,
    deviceWidthRatio: 0.82,
    // editorial：头两块是同一台斜置设备的全景切分（商店里并排连成一台大屏），
    // 后面回到正立。别整条都用 dynamic 那种斜置——中文在缩略图尺寸上会糊。
    template: "editorial",
    // 模板给隐私那张派的是 minimal，那个版式根本不画标题。
    layout: "classic",
  },

  store: {
    name: "Vana",
    subtitle: { "zh-CN": "看得懂的健康分析" },
    developer: "pinapia",
    category: "健康与健身",
    price: "免费",
    description: {
      "zh-CN":
        "拍一张化验单或药盒，Vana 用你自己的记录来解读它：上一次的数值、在吃的药、说过的家族史。" +
        "文字识别在本机完成，只有回答问题需要的内容才会发给你自己配置的模型服务，而且发之前会点名问你。",
    },
  },

  scenes: [
    {
      kind: "screenshot",
      id: "chat",
      flow: "store-01-chat",
      headline: { "zh-CN": "拍下化验单，得到看得懂的解读" },
      subhead: { "zh-CN": "不翻译数值，而是跟你上一次的数比。" },
    },
    {
      kind: "screenshot",
      id: "memory",
      flow: "store-02-memory",
      headline: { "zh-CN": "它记得你说过的事" },
      subhead: { "zh-CN": "家族史、不能吃的药、待复查，下次不用重讲。" },
    },
    {
      kind: "screenshot",
      id: "medications",
      flow: "store-03-medications",
      headline: { "zh-CN": "在吃的、不能吃的，分开记" },
      subhead: { "zh-CN": "「不能吃」那一组，开口之前一定先看。" },
    },
    {
      kind: "screenshot",
      id: "privacy",
      flow: "store-04-privacy",
      headline: { "zh-CN": "先问过你，才发出去" },
      subhead: { "zh-CN": "识别在本机跑，密钥不出这台设备。" },
      layout: "classic",
    },
  ],
};

export default config;
