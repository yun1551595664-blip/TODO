package com.company.issueops.repository;

import com.company.issueops.domain.DictionaryItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DictionaryItemRepository extends JpaRepository<DictionaryItem, Long> {
  List<DictionaryItem> findByDeletedFalseOrderByDictTypeAscSortOrderAscIdAsc();

  List<DictionaryItem> findByDictTypeAndDeletedFalseOrderBySortOrderAscIdAsc(
    String dictType
  );

  List<DictionaryItem> findByDictTypeAndEnabledTrueAndDeletedFalseOrderBySortOrderAscIdAsc(
    String dictType
  );

  Optional<DictionaryItem> findByDictTypeAndCodeAndDeletedFalse(
    String dictType,
    String code
  );
}
