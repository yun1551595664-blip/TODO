CREATE TABLE IF NOT EXISTS department_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(60) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL UNIQUE,
  parent_code VARCHAR(60),
  sort_order INT DEFAULT 100,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  source VARCHAR(30) DEFAULT 'SYSTEM',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO department_config (code, name, sort_order, enabled, source)
VALUES
  ('ALL', '全部', 10, 1, 'SYSTEM'),
  ('PRODUCT', '产品部', 20, 1, 'SYSTEM'),
  ('TECH', '技术部', 30, 1, 'SYSTEM'),
  ('CS', '客服部', 40, 1, 'SYSTEM'),
  ('MANAGEMENT', '管理部', 50, 1, 'SYSTEM')
ON DUPLICATE KEY UPDATE
  sort_order = VALUES(sort_order),
  enabled = 1,
  source = VALUES(source);
