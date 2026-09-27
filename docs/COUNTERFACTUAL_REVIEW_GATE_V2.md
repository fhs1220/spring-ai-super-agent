# 反事实待审门禁 v2：跟随进楼场景

[开发 pilot v2](SYSTEM_ONE_LAUNCH_DEVELOPMENT_V2_REPORT.md) 的 `launch-case18` 描述“试图跟着进楼”，但待审门禁 v1 未命中安全关键词，导致 `COMPLETED` 而非 `REVIEW_REQUIRED`。

v2 为人工复核分流增加短语“堵门、不让我联系、夺走手机”，并识别“跟着／跟随／尾随”后八个字符内出现“进楼／进门／上楼／回家”的表达。命中时仅追加 `SAFETY_AND_SOURCE_SUPPORT_REVIEW_REQUIRED`，不自动裁定答案错误、不替代在线安全分类，也不改变历史标签。版本升为 `counterfactual-review-gate-v2`，回归覆盖原问题的同义表达。

该规则仍会漏掉其他说法，也可能把无风险的“孩子跟着进楼”送审；人工复核允许保守误报。正式上线前需要独立安全集及线上安全护栏验证，不能以此有限词表作为安全发布证明。
