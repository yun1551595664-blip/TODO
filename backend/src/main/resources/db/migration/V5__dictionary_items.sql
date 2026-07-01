CREATE TABLE IF NOT EXISTS dictionary_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dict_type VARCHAR(50) NOT NULL,
  code VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  sort_order INT DEFAULT 0,
  enabled TINYINT(1) DEFAULT 1,
  system_builtin TINYINT(1) DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT(1) DEFAULT 0,
  UNIQUE KEY uk_dictionary_type_code(dict_type, code),
  INDEX idx_dictionary_type_enabled(dict_type, enabled, deleted),
  INDEX idx_dictionary_sort(dict_type, sort_order)
);

INSERT IGNORE INTO dictionary_item(dict_type, code, name, description, sort_order, enabled, system_builtin) VALUES
('ISSUE_SOURCE', 'CUSTOMER_SERVICE', '客服反馈', '售后客服收集并反馈的问题', 10, 1, 1),
('ISSUE_SOURCE', 'CUSTOMER_FEEDBACK', '客户反馈', '客户主动反馈的问题', 20, 1, 1),
('ISSUE_SOURCE', 'MONITOR_ALERT', '监控告警', '监控系统发现的问题', 30, 1, 1),
('ISSUE_SOURCE', 'DATA_INSPECTION', '数据巡检', '数据巡检或运营巡检发现的问题', 40, 1, 1),
('ISSUE_SOURCE', 'BUSINESS_FEEDBACK', '业务反馈', '业务团队反馈的问题', 50, 1, 1),

('BUSINESS_SCENE', 'ORDER_PAYMENT', '订单支付', '订单、支付、履约相关场景', 10, 1, 1),
('BUSINESS_SCENE', 'MARKETING_CAMPAIGN', '营销活动', '活动、优惠券、营销规则相关场景', 20, 1, 1),
('BUSINESS_SCENE', 'USER_OPERATIONS', '用户运营', '用户导入、运营任务相关场景', 30, 1, 1),
('BUSINESS_SCENE', 'MESSAGE_REACH', '消息触达', '消息推送、短信、站内信相关场景', 40, 1, 1),
('BUSINESS_SCENE', 'DATA_REPORT', '数据报表', '报表、导出、数据分析相关场景', 50, 1, 1),
('BUSINESS_SCENE', 'ACCOUNT_CANCELLATION', '账户注销', '账号注销、账号状态相关场景', 60, 1, 1),

('ISSUE_TYPE', 'SYSTEM_DEFECT', '系统缺陷', '系统实现或运行异常', 10, 1, 1),
('ISSUE_TYPE', 'PRODUCT_DEFECT', '产品缺陷', '产品规则、体验或方案缺陷', 20, 1, 1),
('ISSUE_TYPE', 'PERFORMANCE', '性能问题', '性能、超时、稳定性问题', 30, 1, 1),
('ISSUE_TYPE', 'REQUIREMENT_MISS', '需求遗漏', '需求设计或交付遗漏', 40, 1, 1),
('ISSUE_TYPE', 'THIRD_PARTY', '第三方服务', '第三方依赖或外部服务异常', 50, 1, 1),
('ISSUE_TYPE', 'CONFIG_ERROR', '配置异常', '规则、配置、数据配置问题', 60, 1, 1),

('IMPACT_SCOPE', 'SINGLE_CUSTOMER', '单个客户', '影响单个客户或个体案例', 10, 1, 1),
('IMPACT_SCOPE', 'PARTIAL_USERS', '部分用户', '影响部分用户或部分业务范围', 20, 1, 1),
('IMPACT_SCOPE', 'ALL_USERS', '全部用户', '影响全部用户或核心主流程', 30, 1, 1),
('IMPACT_SCOPE', 'INTERNAL_USERS', '内部用户', '影响内部运营、客服或管理人员', 40, 1, 1);
