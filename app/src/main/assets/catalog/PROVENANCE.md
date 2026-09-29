# provider catalog 的来历

- 来源:https://github.com/zjywill/aikitswift.git @ `1d9db3af804c98bb5fd70cd516e70d951864a062`(上游由 models.dev 生成)
- 同步:`python3 scripts/sync-catalog.py`,只留 Android 实现了协议且可连的
  托管 provider,并剥掉 `CloudCatalog` 不读的字段。**不要手工编辑这些 JSON**,
  要改就改脚本重跑。
- 本次:保留 197 个,过滤 6 个,合计 2234KB
