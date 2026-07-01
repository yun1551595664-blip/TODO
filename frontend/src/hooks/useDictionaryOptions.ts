import { useEffect, useMemo, useState } from "react";
import { message } from "antd";
import { issueApi } from "../api";
import type { DictionaryItem, DictionaryType } from "../types";

export const dictionaryTypeLabels: Record<DictionaryType, string> = {
  ISSUE_SOURCE: "问题来源",
  BUSINESS_SCENE: "业务场景",
  ISSUE_TYPE: "问题类型",
  IMPACT_SCOPE: "影响范围",
};

const emptyGrouped = {
  ISSUE_SOURCE: [],
  BUSINESS_SCENE: [],
  ISSUE_TYPE: [],
  IMPACT_SCOPE: [],
} satisfies Record<DictionaryType, DictionaryItem[]>;

export function toSelectOptions(items: DictionaryItem[]) {
  return items.map((item) => ({
    value: item.name,
    label: item.name,
  }));
}

export function useDictionaryOptions(enabledOnly = true) {
  const [items, setItems] =
    useState<Record<DictionaryType, DictionaryItem[]>>(emptyGrouped);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      setItems(await issueApi.dictionaries({ enabledOnly }));
    } catch (error) {
      message.error(error instanceof Error ? error.message : "字段配置加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [enabledOnly]);

  const options = useMemo(
    () => ({
      sources: toSelectOptions(items.ISSUE_SOURCE),
      businessScenes: toSelectOptions(items.BUSINESS_SCENE),
      issueTypes: toSelectOptions(items.ISSUE_TYPE),
      impactScopes: toSelectOptions(items.IMPACT_SCOPE),
    }),
    [items],
  );

  return { items, options, loading, reload: load };
}
