SET NAMES utf8mb4;
USE issue_ops;
UPDATE issue SET status = '已完成' WHERE status IN ('已修复', '已关闭');
UPDATE issue SET status = '处理中', is_reopened = 1 WHERE status = '已复发';
