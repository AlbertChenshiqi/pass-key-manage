// Shared server health check logic (popup + background)
(function () {
    const CONNECTION_STATUS_KEY = 'connectionStatus';
    const HEALTH_CHECK_ALARM = 'healthCheck';
    const HEALTH_CHECK_INTERVAL_MINUTES = 10;

    function normalizeServerUrl(url) {
        const trimmed = String(url || '').trim();
        if (!trimmed) return '';
        return trimmed.replace(/\/+$/, '');
    }

    function buildAuthHeaders(apiKey) {
        const headers = { Accept: 'application/json' };
        if (apiKey) {
            headers.Authorization = `Bearer ${apiKey}`;
            headers['X-API-Key'] = apiKey;
        }
        return headers;
    }

    function storageGet(keys) {
        return new Promise((resolve) => {
            if (!chrome?.storage?.local) {
                resolve({});
                return;
            }
            chrome.storage.local.get(keys, resolve);
        });
    }

    function storageSet(data) {
        return new Promise((resolve, reject) => {
            if (!chrome?.storage?.local) {
                reject(new Error('存储不可用'));
                return;
            }
            chrome.storage.local.set(data, () => {
                if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
                else resolve();
            });
        });
    }

    async function checkServerHealth(settings) {
        const serverUrl = normalizeServerUrl(settings?.serverUrl);
        const checkedAt = Date.now();

        if (!serverUrl) {
            return { state: 'idle', message: '未配置服务器', checkedAt, serverUrl: '' };
        }

        const endpoints = [`${serverUrl}/api/health`, `${serverUrl}/health`, serverUrl];
        let lastError = '无法连接服务器';

        for (const url of endpoints) {
            try {
                const res = await fetch(url, {
                    method: 'GET',
                    headers: buildAuthHeaders(settings?.apiKey),
                    signal: AbortSignal.timeout(8000),
                });
                if (res.ok) {
                    return { state: 'ok', message: '连接正常', checkedAt, serverUrl };
                }
                if (res.status === 401 || res.status === 403) {
                    return {
                        state: 'warning',
                        message: '服务器可达（需检查 Key）',
                        checkedAt,
                        serverUrl,
                    };
                }
                lastError = `HTTP ${res.status}`;
            } catch (e) {
                lastError = e?.message || '网络错误';
            }
        }

        return { state: 'error', message: `连接失败：${lastError}`, checkedAt, serverUrl };
    }

    async function persistConnectionStatus(status) {
        await storageSet({ [CONNECTION_STATUS_KEY]: status });
        return status;
    }

    async function loadConnectionStatus() {
        const result = await storageGet([CONNECTION_STATUS_KEY]);
        return result[CONNECTION_STATUS_KEY] || null;
    }

    async function runHealthCheck(settings) {
        const status = await checkServerHealth(settings);
        await persistConnectionStatus(status);
        return status;
    }

    async function runHealthCheckFromStorage() {
        const result = await storageGet(['userSettings']);
        const settings = result.userSettings || { serverUrl: '', apiKey: '' };
        return runHealthCheck(settings);
    }

    function formatTimeAgo(timestamp) {
        if (!timestamp) return '';
        const diff = Date.now() - timestamp;
        if (diff < 60_000) return '刚刚';
        if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
        if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
        return `${Math.floor(diff / 86_400_000)} 天前`;
    }

    function formatConnectionStatusText(status) {
        if (!status?.message) return '未连接';
        const ago = formatTimeAgo(status.checkedAt);
        return ago ? `${status.message} · ${ago}` : status.message;
    }

    function isStatusStale(status, maxAgeMs = HEALTH_CHECK_INTERVAL_MINUTES * 60 * 1000) {
        if (!status?.checkedAt) return true;
        return Date.now() - status.checkedAt > maxAgeMs;
    }

    function isStatusForSettings(status, settings) {
        if (!status) return false;
        return normalizeServerUrl(status.serverUrl) === normalizeServerUrl(settings?.serverUrl);
    }

    globalThis.ConnectionHealth = {
        CONNECTION_STATUS_KEY,
        HEALTH_CHECK_ALARM,
        HEALTH_CHECK_INTERVAL_MINUTES,
        normalizeServerUrl,
        buildAuthHeaders,
        checkServerHealth,
        persistConnectionStatus,
        loadConnectionStatus,
        runHealthCheck,
        runHealthCheckFromStorage,
        formatTimeAgo,
        formatConnectionStatusText,
        isStatusStale,
        isStatusForSettings,
    };
})();
