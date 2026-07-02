ALTER TABLE user_account
  ADD COLUMN department VARCHAR(80) NULL AFTER role,
  ADD COLUMN data_scope VARCHAR(30) NOT NULL DEFAULT 'DEPARTMENT' AFTER department;

UPDATE user_account
SET
  department = CASE role
    WHEN 'ADMIN' THEN '公司全局'
    WHEN 'PRODUCT' THEN '产品部'
    WHEN 'TECH' THEN '技术部'
    WHEN 'CS' THEN '客服部'
    ELSE COALESCE(display_name, '未分配')
  END,
  data_scope = CASE role
    WHEN 'ADMIN' THEN 'ALL'
    WHEN 'PRODUCT' THEN 'ALL'
    WHEN 'TECH' THEN 'DEPARTMENT'
    WHEN 'CS' THEN 'OWN'
    ELSE 'DEPARTMENT'
  END
WHERE department IS NULL OR department = '' OR data_scope IS NULL OR data_scope = '';

CREATE INDEX idx_user_account_scope ON user_account(data_scope, department);
