package com.company.issueops.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.company.issueops.domain.DictionaryItem;
import com.company.issueops.domain.Issue;
import com.company.issueops.repository.DictionaryItemRepository;
import com.company.issueops.repository.IssueRepository;
import com.company.issueops.web.DictionaryRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceTest {

  @Mock
  private DictionaryItemRepository dictionaries;

  @Mock
  private IssueRepository issues;

  @InjectMocks
  private DictionaryService service;

  @Test
  void rejectsDeletingReferencedDictionaryItem() {
    DictionaryItem item = item(1L, DictionaryService.ISSUE_SOURCE, "客服反馈");
    Issue issue = new Issue();
    issue.setSource("客服反馈");
    issue.setDeleted(false);
    when(dictionaries.findById(1L)).thenReturn(Optional.of(item));
    when(issues.findAll()).thenReturn(List.of(issue));

    assertThatThrownBy(() -> service.delete(1L))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("只能停用");
  }

  @Test
  void rejectsRenamingReferencedDictionaryItem() {
    DictionaryItem item = item(1L, DictionaryService.ISSUE_SOURCE, "客服反馈");
    Issue issue = new Issue();
    issue.setSource("客服反馈");
    issue.setDeleted(false);
    when(dictionaries.findById(1L)).thenReturn(Optional.of(item));
    when(issues.findAll()).thenReturn(List.of(issue));

    assertThatThrownBy(() ->
      service.update(
        1L,
        new DictionaryRequest(
          DictionaryService.ISSUE_SOURCE,
          "CUSTOMER_SERVICE",
          "售后反馈",
          "改名",
          10,
          true
        )
      )
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("不能修改类型或名称");
  }

  private DictionaryItem item(Long id, String type, String name) {
    DictionaryItem item = new DictionaryItem();
    item.setId(id);
    item.setDictType(type);
    item.setCode("CODE");
    item.setName(name);
    item.setDeleted(false);
    item.setEnabled(true);
    return item;
  }
}
