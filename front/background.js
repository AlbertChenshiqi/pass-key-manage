importScripts('connection.js');

const { HEALTH_CHECK_ALARM, HEALTH_CHECK_INTERVAL_MINUTES, runHealthCheckFromStorage } =
    globalThis.ConnectionHealth;

function ensureHealthAlarm() {
    chrome.alarms.create(HEALTH_CHECK_ALARM, {
        periodInMinutes: HEALTH_CHECK_INTERVAL_MINUTES,
    });
}

chrome.runtime.onInstalled.addListener(() => {
    ensureHealthAlarm();
    runHealthCheckFromStorage();
});

chrome.runtime.onStartup.addListener(() => {
    ensureHealthAlarm();
    runHealthCheckFromStorage();
});

chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === HEALTH_CHECK_ALARM) {
        runHealthCheckFromStorage();
    }
});

chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !changes.userSettings) return;
    globalThis.ConnectionHealth.runHealthCheck(
        changes.userSettings.newValue || { serverUrl: '', apiKey: '' },
    );
});
