CREATE TABLE IF NOT EXISTS role_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(30) NOT NULL UNIQUE,
  name VARCHAR(60) NOT NULL,
  description VARCHAR(255),
  permissions VARCHAR(500) NOT NULL DEFAULT '',
  default_data_scope VARCHAR(30) NOT NULL DEFAULT 'DEPARTMENT',
  default_department VARCHAR(80),
  sort_order INT NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  system_builtin TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_role_config_enabled(enabled, sort_order)
);

INSERT INTO role_config (
  code,
  name,
  description,
  permissions,
  default_data_scope,
  default_department,
  sort_order,
  enabled,
  system_builtin
) VALUES
  (
    'ADMIN',
    '管理员',
    '系统管理员，拥有账号、角色、字段和问题全量管理权限',
    'issue:create,issue:edit,issue:delete,issue:status,issue:log,field:manage,account:manage,ai:execute',
    'ALL',
    '全部',
    10,
    1,
    1
  ),
  (
    'PRODUCT',
    '产品',
    '负责产品缺陷治理、状态推进和 AI 操作确认',
    'issue:create,issue:edit,issue:status,issue:log,ai:execute',
    'ALL',
    '产品部',
    20,
    1,
    1
  ),
  (
    'TECH',
    '技术',
    '负责修复推进、状态更新和处理记录',
    'issue:edit,issue:status,issue:log,ai:execute',
    'DEPARTMENT',
    '技术部',
    30,
    1,
    1
  ),
  (
    'CS',
    '客服',
    '负责录入客户反馈和补充处理记录',
    'issue:create,issue:log',
    'OWN',
    '客服部',
    40,
    1,
    1
  ),
  (
    'VIEWER',
    '观察员',
    '只读查看问题、数据和复盘信息',
    '',
    'DEPARTMENT',
    '全部',
    50,
    1,
    1
  )
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  description = VALUES(description),
  default_data_scope = VALUES(default_data_scope),
  default_department = VALUES(default_department),
  sort_order = VALUES(sort_order),
  enabled = 1,
  system_builtin = 1;
