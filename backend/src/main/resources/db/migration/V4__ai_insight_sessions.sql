CREATE TABLE IF NOT EXISTS ai_insight_session (
  id VARCHAR(64) PRIMARY KEY,
  insight_id VARCHAR(64),
  title VARCHAR(255),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_ai_insight_session_insight(insight_id)
);

CREATE TABLE IF NOT EXISTS ai_insight_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content MEDIUMTEXT,
  structured_json MEDIUMTEXT,
  model VARCHAR(100),
  generated_by VARCHAR(60),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_insight_message_session(session_id, id)
);
