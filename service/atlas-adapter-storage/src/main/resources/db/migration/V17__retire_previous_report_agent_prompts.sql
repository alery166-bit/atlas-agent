UPDATE agent_message
SET content = '该对话创建于旧流程，原先要求上传历史报告。当前版本已取消这一限制，请直接重新发送企业风险排查指令，系统将从企业数据源生成报告。',
    required_inputs_json = '[]',
    suggested_actions_json = '[]'
WHERE role = 'ASSISTANT'
  AND required_inputs_json LIKE '%PREVIOUS_REPORT_FILE_ID%';

