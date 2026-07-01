package com.company.issueops.service;

import com.company.issueops.domain.DictionaryItem;
import com.company.issueops.domain.Issue;
import com.company.issueops.repository.DictionaryItemRepository;
import com.company.issueops.repository.IssueRepository;
import com.company.issueops.web.DictionaryRequest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DictionaryService {

  public static final String ISSUE_SOURCE = "ISSUE_SOURCE";
  public static final String BUSINESS_SCENE = "BUSINESS_SCENE";
  public static final String ISSUE_TYPE = "ISSUE_TYPE";
  public static final String IMPACT_SCOPE = "IMPACT_SCOPE";

  private static final Set<String> DICT_TYPES = Set.of(
    ISSUE_SOURCE,
    BUSINESS_SCENE,
    ISSUE_TYPE,
    IMPACT_SCOPE
  );
  private static final Pattern CODE_PATTERN = Pattern.compile("[^A-Z0-9_]+");

  private final DictionaryItemRepository dictionaries;
  private final IssueRepository issues;

  public List<DictionaryItem> list(String type, boolean enabledOnly) {
    String dictType = normalizeType(type);
    if (enabledOnly) {
      return dictionaries.findByDictTypeAndEnabledTrueAndDeletedFalseOrderBySortOrderAscIdAsc(
        dictType
      );
    }
    return dictionaries.findByDictTypeAndDeletedFalseOrderBySortOrderAscIdAsc(
      dictType
    );
  }

  public Map<String, List<DictionaryItem>> grouped(boolean enabledOnly) {
    return DICT_TYPES
      .stream()
      .sorted()
      .collect(
        Collectors.toMap(
          type -> type,
          type -> list(type, enabledOnly),
          (left, right) -> left,
          LinkedHashMap::new
        )
      );
  }

  @Transactional
  public DictionaryItem create(DictionaryRequest request) {
    String dictType = normalizeType(request.dictType());
    String name = normalizeName(request.name());
    String code = normalizeCode(request.code(), name);
    dictionaries
      .findByDictTypeAndCodeAndDeletedFalse(dictType, code)
      .ifPresent(existing -> {
        throw new IllegalArgumentException("同类型下已存在相同编码：" + code);
      });
    DictionaryItem item = new DictionaryItem();
    item.setDictType(dictType);
    item.setCode(code);
    item.setName(name);
    item.setDescription(trimToNull(request.description()));
    item.setSortOrder(request.sortOrder() == null ? nextSortOrder(dictType) : request.sortOrder());
    item.setEnabled(request.enabled() == null || request.enabled());
    item.setSystemBuiltin(false);
    return dictionaries.save(item);
  }

  @Transactional
  public DictionaryItem update(Long id, DictionaryRequest request) {
    DictionaryItem item = get(id);
    String dictType = normalizeType(request.dictType());
    String name = normalizeName(request.name());
    String code = normalizeCode(request.code(), name);
    boolean changesReferencedValue =
      !Objects.equals(item.getDictType(), dictType) || !Objects.equals(item.getName(), name);
    if (changesReferencedValue && Boolean.TRUE.equals(usage(id).get("used"))) {
      throw new IllegalArgumentException(
        "该选项已被历史问题使用，不能修改类型或名称；可停用后新增新选项"
      );
    }
    dictionaries
      .findByDictTypeAndCodeAndDeletedFalse(dictType, code)
      .filter(existing -> !Objects.equals(existing.getId(), id))
      .ifPresent(existing -> {
        throw new IllegalArgumentException("同类型下已存在相同编码：" + code);
      });
    item.setDictType(dictType);
    item.setCode(code);
    item.setName(name);
    item.setDescription(trimToNull(request.description()));
    if (request.sortOrder() != null) item.setSortOrder(request.sortOrder());
    if (request.enabled() != null) item.setEnabled(request.enabled());
    return dictionaries.save(item);
  }

  @Transactional
  public DictionaryItem enabled(Long id, boolean enabled) {
    DictionaryItem item = get(id);
    item.setEnabled(enabled);
    return dictionaries.save(item);
  }

  @Transactional
  public void delete(Long id) {
    DictionaryItem item = get(id);
    Map<String, Object> usage = usage(id);
    if (Boolean.TRUE.equals(usage.get("used"))) {
      throw new IllegalArgumentException("该选项已被历史问题使用，只能停用，不能删除");
    }
    item.setDeleted(true);
    item.setEnabled(false);
    dictionaries.save(item);
  }

  public Map<String, Object> usage(Long id) {
    DictionaryItem item = get(id);
    long issueCount = issues
      .findAll()
      .stream()
      .filter(issue -> !Boolean.TRUE.equals(issue.getDeleted()))
      .filter(issue -> matchesIssueField(issue, item.getDictType(), item.getName()))
      .count();
    return Map.of(
      "used",
      issueCount > 0,
      "issueCount",
      issueCount,
      "canDelete",
      issueCount == 0
    );
  }

  private DictionaryItem get(Long id) {
    return dictionaries
      .findById(id)
      .filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
      .orElseThrow(() -> new NoSuchElementException("字典项不存在"));
  }

  private boolean matchesIssueField(Issue issue, String dictType, String name) {
    return switch (dictType) {
      case ISSUE_SOURCE -> Objects.equals(issue.getSource(), name);
      case BUSINESS_SCENE -> Objects.equals(issue.getBusinessScene(), name);
      case ISSUE_TYPE -> Objects.equals(issue.getIssueType(), name);
      case IMPACT_SCOPE -> Objects.equals(issue.getImpactScope(), name);
      default -> false;
    };
  }

  private int nextSortOrder(String dictType) {
    return dictionaries
      .findByDictTypeAndDeletedFalseOrderBySortOrderAscIdAsc(dictType)
      .stream()
      .map(DictionaryItem::getSortOrder)
      .filter(Objects::nonNull)
      .max(Integer::compareTo)
      .orElse(0) +
    10;
  }

  private String normalizeType(String type) {
    String normalized = trimToNull(type);
    if (normalized == null || !DICT_TYPES.contains(normalized)) {
      throw new IllegalArgumentException(
        "不支持的字典类型，请使用：" + String.join("、", DICT_TYPES)
      );
    }
    return normalized;
  }

  private String normalizeName(String name) {
    String normalized = trimToNull(name);
    if (normalized == null) throw new IllegalArgumentException("字典名称不能为空");
    return normalized;
  }

  private String normalizeCode(String rawCode, String name) {
    String source = trimToNull(rawCode);
    if (source == null) source = name;
    String normalized = Normalizer
      .normalize(source, Normalizer.Form.NFKD)
      .toUpperCase(Locale.ROOT)
      .trim();
    normalized = CODE_PATTERN.matcher(normalized).replaceAll("_");
    normalized = normalized.replaceAll("^_+|_+$", "");
    if (normalized.isBlank()) {
      normalized =
        "ITEM_" + Long.toString(Math.abs((long) name.hashCode()), 36).toUpperCase(Locale.ROOT);
    }
    return normalized;
  }

  private String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
