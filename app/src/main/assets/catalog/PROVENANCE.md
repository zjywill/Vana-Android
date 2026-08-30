# provider catalog 的来历

- 来源:https://github.com/zjywill/aikitswift.git @ `287398f2d5398ff390f6cf77a1fa0dbe2cb220f0`(上游由 models.dev 生成)
- 同步:`python3 scripts/sync-catalog.py`,只留 Android 实现了协议且可连的
  托管 provider,并剥掉 `CloudCatalog` 不读的字段。**不要手工编辑这些 JSON**,
  要改就改脚本重跑。
- 本次:保留 179 个,过滤 6 个,合计 2040KB
