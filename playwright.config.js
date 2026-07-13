// ShipKit widget — Playwright E2E + accessibility configuration.
// Dev-only tooling: the ShipKit runtime (backend + widget) stays dependency-free.
// Author: Daniel Wilson Kemp — MIT.
//
// The specs load the real widget (src/main/resources/public/js/shipkit.js) and
// the shipped self-contained pages directly over file:// URLs, driving them
// against an in-page mock backend that speaks the exact §2 REST shapes. No web
// server, no network, no credentials are required.

const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests/e2e',
  // Per-test working artifacts (traces, failure screenshots) live here.
  outputDir: './test-results',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    // file:// pages: no baseURL. Capture rich diagnostics only on failure.
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
