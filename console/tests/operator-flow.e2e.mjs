import assert from "node:assert/strict";
import { mkdir, readFile, stat } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { chromium } from "playwright";

const webBase = process.env.ATLAS_E2E_WEB_BASE || "http://127.0.0.1:13000";
const apiBase = process.env.ATLAS_E2E_API_BASE || "http://127.0.0.1:18080";
const operatorId = process.env.ATLAS_E2E_OPERATOR || "e2e-operator";
const reportFile = fileURLToPath(
  new URL("../../data/templates/北京简熹和食品有限公司_企业风险监测分析报告20260714.docx", import.meta.url),
);
const artifactRoot = fileURLToPath(
  new URL("../../work/e2e/browser-artifacts/", import.meta.url),
);

async function api(path, options = {}) {
  const response = await fetch(`${apiBase}${path}`, {
    ...options,
    headers: {
      "X-Operator-Id": operatorId,
      ...(options.headers || {}),
    },
  });
  if (!response.ok) {
    throw new Error(
      `${options.method || "GET"} ${path} failed: ${response.status} ${await response.text()}`,
    );
  }
  const contentType = response.headers.get("content-type") || "";
  return contentType.includes("json") ? response.json() : response;
}

async function eventually(action, description, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await action();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(
    `Timed out waiting for ${description}${lastError ? `: ${lastError.message}` : ""}`,
  );
}

async function latestTask() {
  const page = await api(
    `/api/tasks?operator_id=${encodeURIComponent(operatorId)}&page_size=20`,
  );
  return page.items[0];
}

async function workspace(taskId) {
  return api(`/api/tasks/${taskId}/workspace`);
}

async function clickNextAction(page, taskId, expectedAction) {
  await eventually(
    async () => (await workspace(taskId)).next_action === expectedAction,
    `next action ${expectedAction}`,
  );
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      response.url().startsWith(`${apiBase}/api/tasks/${taskId}/`),
  );
  await page.locator(".next-action-card button").click();
  const response = await responsePromise;
  assert.ok(
    response.ok(),
    `${expectedAction} failed: ${response.status()} ${await response.text()}`,
  );
  await eventually(
    async () => page.locator(".next-action-card button").isEnabled(),
    `UI completion of ${expectedAction}`,
  );
}

test("operator completes the controlled report-update flow in a real browser", async (t) => {
  await mkdir(artifactRoot, { recursive: true });
  const browser = await chromium.launch({
    channel: "chrome",
    headless: true,
  });
  t.after(() => browser.close());

  const context = await browser.newContext({
    acceptDownloads: true,
    viewport: { width: 1500, height: 1000 },
  });
  const page = await context.newPage();
  page.on("console", (message) =>
    console.log(`[browser:${message.type()}] ${message.text()}`),
  );
  page.on("pageerror", (error) =>
    console.log(`[browser:pageerror] ${error.message}`),
  );
  page.on("requestfailed", (request) =>
    console.log(
      `[browser:requestfailed] ${request.method()} ${request.url()} ${request.failure()?.errorText}`,
    ),
  );
  page.on("response", (response) => {
    if (response.url().startsWith(apiBase)) {
      console.log(
        `[browser:api] ${response.request().method()} ${response.status()} ${response.url()}`,
      );
    }
  });
  await page.addInitScript(
    ({ configuredApiBase, configuredOperator }) => {
      localStorage.setItem("atlas-api-base", configuredApiBase);
      localStorage.setItem("atlas-operator", configuredOperator);
      localStorage.setItem("atlas-theme", "glacier");
    },
    {
      configuredApiBase: apiBase,
      configuredOperator: operatorId,
    },
  );

  await page.goto(webBase, { waitUntil: "load" });
  await page.locator("form.composer textarea").waitFor();
  console.log(
    `[browser:config] api=${await page.evaluate(() => localStorage.getItem("atlas-api-base"))}`,
  );
  const fileChooserPromise = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "添加原风险报告" }).click();
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles(reportFile);
  try {
    await page.locator(".attachment-chip").waitFor({ timeout: 10_000 });
  } catch (error) {
    console.log(
      `[browser:upload] toast=${await page.locator(".toast").textContent().catch(() => "none")}`,
    );
    await page.screenshot({
      path: `${artifactRoot}operator-flow-upload-failed.png`,
      fullPage: true,
    });
    throw error;
  }
  await page
    .locator("form.composer textarea")
    .fill("更新JSON样本企业有限公司的风险报告，重点核实失联、欠薪和门店关闭。");
  await page.locator("form.composer").evaluate((form) =>
    form.requestSubmit(),
  );

  const task = await eventually(
    latestTask,
    "task created from the dialogue",
  );
  assert.equal(task.task.company_query, "JSON样本企业有限公司");
  const taskId = task.task.task_id;

  await page.goto(`${webBase}/tasks`, { waitUntil: "load" });
  await page.locator(".task-detail").waitFor();
  await eventually(
    async () => page.locator(".next-action-card button").isVisible(),
    "task action button",
  );

  for (let attempts = 0; attempts < 5; attempts += 1) {
    const current = await workspace(taskId);
    if (current.next_action === "REVIEW_EVIDENCE") break;
    if (current.next_action === "CONFIRM_SUBJECT") {
      await page.getByRole("button", { name: "确认此主体" }).first().click();
      await eventually(
        async () => (await workspace(taskId)).next_action !== "CONFIRM_SUBJECT",
        "subject confirmation",
      );
      continue;
    }
    assert.equal(current.next_action, "EXECUTE_TASK");
    await clickNextAction(page, taskId, "EXECUTE_TASK");
  }
  assert.equal((await workspace(taskId)).next_action, "REVIEW_EVIDENCE");

  while ((await workspace(taskId)).evidence_progress.unverified > 0) {
    const article = page
      .locator(".evidence-list article")
      .filter({ has: page.getByRole("button", { name: "排除" }) })
      .first();
    await article.waitFor();
    const hasAccessibleSource = (await article.locator("a").count()) > 0;
    await article
      .getByRole("button", {
        name: hasAccessibleSource ? "确认" : "排除",
        exact: true,
      })
      .click();
    await page.waitForTimeout(250);
  }

  await clickNextAction(page, taskId, "CALCULATE_RISK");
  const calculated = await eventually(
    async () => {
      const current = await workspace(taskId);
      return current.risk_score ? current : undefined;
    },
    "risk score calculation",
  );
  assert.equal(calculated.risk_score.original_score, 8);

  const scoreForm = page.locator("form.score-adjustment");
  await scoreForm.locator('input[type="number"]').fill("7.5");
  await scoreForm
    .locator("textarea")
    .fill("端到端自动化验证：保留闭店和欠薪事实，调整事件严重程度。");
  await scoreForm.getByRole("button", { name: "保存人工评分" }).click();
  await eventually(
    async () => (await workspace(taskId)).risk_score?.manual_score === 7.5,
    "manual score adjustment",
  );

  await page
    .locator(".confirmation-note textarea")
    .fill("端到端流程已核对企业数据、公开证据和人工评分。");
  await clickNextAction(page, taskId, "CONFIRM_REVIEW");
  await clickNextAction(page, taskId, "GENERATE_REPORT");

  const completed = await eventually(
    async () => {
      const current = await workspace(taskId);
      return current.next_action === "DOWNLOAD_REPORT" ? current : undefined;
    },
    "generated report",
    30_000,
  );
  assert.equal(completed.task.status, "COMPLETED");
  assert.ok(completed.reports.some((report) => report.status === "GENERATED"));

  const downloadPromise = page.waitForEvent("download");
  await page.locator(".next-action-card button").click();
  const download = await downloadPromise;
  const downloadPath = `${artifactRoot}atlas-e2e-report.docx`;
  await download.saveAs(downloadPath);
  const downloaded = await stat(downloadPath);
  assert.ok(downloaded.size > 10_000);
  const signature = await readFile(downloadPath);
  assert.equal(signature.subarray(0, 2).toString("ascii"), "PK");

  await page.screenshot({
    path: `${artifactRoot}operator-flow-completed.png`,
    fullPage: true,
  });
  await page.getByRole("button", { name: "切换墨玉政企" }).click();
  await assert.doesNotReject(() =>
    page.locator(".atlas-app.theme-jade").waitFor(),
  );
});
