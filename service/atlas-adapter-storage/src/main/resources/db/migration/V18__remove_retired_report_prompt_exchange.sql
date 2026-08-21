DELETE FROM agent_message
WHERE (conversation_id, idempotency_key) IN (
    SELECT conversation_id, idempotency_key
    FROM agent_message
    WHERE role = 'ASSISTANT'
      AND (
        content LIKE '%该对话创建于旧流程%'
        OR content LIKE '%原先要求上传历史报告%'
        OR content LIKE '%当前范围是更新旧风险报告%'
      )
);
