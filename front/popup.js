// 密码库 popup

let vaultItems = [];
let totpRefreshInterval = null;
let editingItemId = null;
let editingItemType = 'totp';
let currentView = 'list';
let currentFilter = 'all';

const USER_SETTINGS_KEY = 'userSettings';
const VAULT_STORAGE_KEY = 'vaultItems';
const NEXT_VAULT_ID_KEY = 'nextVaultId';
const SNOWFLAKE_EPOCH = 1700000000000;
let userSettings = { serverUrl: '', apiKey: '', autoSync: false };
let connectionStatus = null;
let snowflakeSequence = 0;
let snowflakeLastTime = -1;

const ITEM_TYPES = {
    totp: { label: '2FA', formLabel: '双重验证' },
    login: { label: '密码', formLabel: '登录密码' },
    note: { label: '笔记', formLabel: '安全笔记' },
    card: { label: '银行卡', formLabel: '银行卡' },
    identity: { label: '身份', formLabel: '身份信息' },
};

const OTHER_TYPES = ['note', 'card', 'identity'];

// --------- UI helpers ---------
function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `
        <span class="toast-message">${message}</span>
        <button class="toast-close" type="button" aria-label="关闭">×</button>
    `;

    toast.querySelector('.toast-close')?.addEventListener('click', () => toast.remove());
    container.appendChild(toast);

    if (duration > 0) {
        setTimeout(() => {
            toast.classList.add('hiding');
            setTimeout(() => toast.remove(), 200);
        }, duration);
    }
}

window.addEventListener('error', (e) => {
    showToast(`脚本错误：${e?.message || '未知错误'}`, 'error', 5000);
});
window.addEventListener('unhandledrejection', (e) => {
    showToast(`操作失败：${e?.reason?.message || e?.reason || '未知错误'}`, 'error', 5000);
});

function showConfirm(message, title = '确认', type = 'confirm') {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'modal';

        const confirmClass = type === 'danger' ? 'modal-btn-danger' : 'modal-btn-confirm';
        const confirmText = type === 'danger' ? '确认' : '确定';

        modal.innerHTML = `
            <div class="modal-title">${title}</div>
            <div class="modal-message">${message}</div>
            <div class="modal-buttons">
                <button type="button" class="modal-btn modal-btn-cancel">取消</button>
                <button type="button" class="modal-btn ${confirmClass}">${confirmText}</button>
            </div>
        `;

        overlay.appendChild(modal);
        document.body.appendChild(overlay);

        const close = (result) => {
            overlay.remove();
            resolve(result);
        };

        modal.querySelector('.modal-btn-cancel')?.addEventListener('click', () => close(false));
        modal.querySelector(`.${confirmClass}`)?.addEventListener('click', () => close(true));
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close(false);
        });
    });
}

function showTypePicker() {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';

        const modal = document.createElement('div');
        modal.className = 'modal';
        modal.style.maxWidth = '320px';

        const buttons = Object.entries(ITEM_TYPES)
            .map(([type, meta]) => {
                const hints = {
                    totp: 'TOTP 验证码',
                    login: '网址、用户名、密码',
                    note: '纯文本笔记',
                    card: '卡号、有效期、CVV',
                    identity: '姓名、邮箱、电话',
                };
                return `<button type="button" class="type-picker-btn" data-type="${type}">
                    ${meta.formLabel}<span>${hints[type]}</span>
                </button>`;
            })
            .join('');

        modal.innerHTML = `
            <div class="modal-title">选择类型</div>
            <div class="type-picker">${buttons}</div>
            <div class="modal-buttons">
                <button type="button" class="modal-btn modal-btn-cancel">取消</button>
            </div>
        `;

        overlay.appendChild(modal);
        document.body.appendChild(overlay);

        const close = (type) => {
            overlay.remove();
            resolve(type);
        };

        modal.querySelectorAll('.type-picker-btn').forEach((btn) => {
            btn.addEventListener('click', () => close(btn.dataset.type));
        });
        modal.querySelector('.modal-btn-cancel')?.addEventListener('click', () => close(null));
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close(null);
        });
    });
}

function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

async function copyText(text, label = '已复制') {
    try {
        await navigator.clipboard.writeText(text);
        showToast(label, 'success', 1500);
    } catch {
        showToast('复制失败', 'error');
    }
}

function maskCardNumber(num) {
    const digits = String(num || '').replace(/\s/g, '');
    if (digits.length < 4) return '••••';
    return `•••• •••• •••• ${digits.slice(-4)}`;
}

// --------- navigation ---------
function setActivePanel(panelId) {
    document.querySelectorAll('.panel').forEach((el) => el.classList.remove('active'));
    document.getElementById(panelId)?.classList.add('active');
}

function setActiveNav(view) {
    document.querySelectorAll('.nav-item').forEach((el) => {
        el.classList.toggle('active', el.dataset.view === view);
    });
}

function getFilteredItems() {
    if (currentFilter === 'all') return vaultItems;
    if (currentFilter === 'totp') return vaultItems.filter((i) => getItemType(i) === 'totp');
    if (currentFilter === 'login') return vaultItems.filter((i) => getItemType(i) === 'login');
    return vaultItems.filter((i) => OTHER_TYPES.includes(getItemType(i)));
}

function updateHeader() {
    const title = document.getElementById('headerTitle');
    const subtitle = document.getElementById('headerSubtitle');
    const actions = document.getElementById('headerActions');
    const bottomNav = document.getElementById('bottomNav');

    if (!title || !subtitle || !actions) return;

    if (currentView === 'form') {
        const typeLabel = ITEM_TYPES[editingItemType]?.formLabel || '记录';
        title.textContent = editingItemId ? `编辑${typeLabel}` : `添加${typeLabel}`;
        subtitle.textContent = '填写详细信息';
        actions.innerHTML = `<button type="button" id="itemBackBtn" class="text-btn">取消</button>`;
        document.getElementById('itemBackBtn')?.addEventListener('click', hideItemForm);
        bottomNav?.classList.add('hidden');
        return;
    }

    bottomNav?.classList.remove('hidden');

    if (currentView === 'user') {
        title.textContent = '用户中心';
        subtitle.textContent = '本地与服务器数据管理';
        actions.innerHTML = '';
        return;
    }

    const filtered = getFilteredItems();
    title.textContent = '密码库';
    const filterLabel = { all: '全部', totp: '2FA', login: '密码', other: '其他' }[currentFilter];
    subtitle.textContent =
        currentFilter === 'all'
            ? `共 ${vaultItems.length} 条记录`
            : `${filterLabel} ${filtered.length} 条 / 共 ${vaultItems.length} 条`;
    actions.innerHTML = `
        <button type="button" id="addItemBtn" class="icon-btn primary" title="添加" aria-label="添加">
            <svg viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </button>
    `;
    document.getElementById('addItemBtn')?.addEventListener('click', () => showAddItemFlow());
}

function switchView(view) {
    currentView = view;

    if (view === 'list') {
        setActivePanel('listPanel');
        setActiveNav('list');
        updateHeader();
    } else if (view === 'user') {
        setActivePanel('userCenterPanel');
        setActiveNav('user');
        populateUserSettingsForm().then(() => updateHeader());
    }
}

function setFormType(type) {
    editingItemType = type;
    const select = document.getElementById('itemTypeSelect');
    if (select) {
        select.value = type;
        select.disabled = !!editingItemId;
    }

    document.querySelectorAll('.form-type-fields').forEach((el) => el.classList.add('hidden'));
    const fieldMap = {
        totp: 'totpFields',
        login: 'loginFields',
        note: 'noteFields',
        card: 'cardFields',
        identity: 'identityFields',
    };
    document.getElementById(fieldMap[type])?.classList.remove('hidden');
}

function clearItemForm() {
    document.getElementById('itemName').value = '';
    document.getElementById('totpSecret').value = '';
    document.getElementById('otpauthUrlInput').value = '';
    document.getElementById('otpauthUrlInput')?.classList.add('hidden');
    document.getElementById('qrImageInput').value = '';
    document.getElementById('loginUrl').value = '';
    document.getElementById('loginUsername').value = '';
    document.getElementById('loginPassword').value = '';
    document.getElementById('loginNotes').value = '';
    document.getElementById('noteContent').value = '';
    document.getElementById('cardholder').value = '';
    document.getElementById('cardNumber').value = '';
    document.getElementById('cardExpiry').value = '';
    document.getElementById('cardCvv').value = '';
    document.getElementById('cardNotes').value = '';
    document.getElementById('identityFullName').value = '';
    document.getElementById('identityEmail').value = '';
    document.getElementById('identityPhone').value = '';
    document.getElementById('identityAddress').value = '';
    document.getElementById('identityNotes').value = '';
}

async function showAddItemFlow() {
    const type = await showTypePicker();
    if (!type) return;
    showItemForm(type);
}

function showItemForm(type = 'totp') {
    editingItemId = null;
    clearItemForm();
    setFormType(type);
    currentView = 'form';
    setActivePanel('itemFormView');
    updateHeader();
    document.getElementById('itemName')?.focus();
}

function hideItemForm() {
    editingItemId = null;
    editingItemType = 'totp';
    switchView('list');
}

// --------- item model ---------
function getItemType(item) {
    if (item.type) return item.type;
    if (item.secret || item.otpauthUrl) return 'totp';
    if (item.username || item.password || item.url) return 'login';
    if (item.number || item.cardholder) return 'card';
    if (item.fullName || item.email || item.phone) return 'identity';
    return 'note';
}

function normalizeItem(item) {
    const copy = { ...item };
    copy.type = getItemType(copy);
    return copy;
}

// --------- user settings ---------
const {
    normalizeServerUrl,
    buildAuthHeaders: buildConnectionAuthHeaders,
    loadConnectionStatus,
    runHealthCheck,
    formatConnectionStatusText,
    isStatusForSettings,
    CONNECTION_STATUS_KEY,
} = globalThis.ConnectionHealth;

async function populateUserSettingsForm() {
    const serverInput = document.getElementById('serverUrlInput');
    const apiKeyInput = document.getElementById('apiKeyInput');
    const autoSyncInput = document.getElementById('autoSyncInput');
    if (serverInput) serverInput.value = userSettings.serverUrl || '';
    if (apiKeyInput) apiKeyInput.value = userSettings.apiKey || '';
    if (autoSyncInput) autoSyncInput.checked = !!userSettings.autoSync;

    connectionStatus = await loadConnectionStatus();
    if (connectionStatus && !isStatusForSettings(connectionStatus, userSettings)) {
        connectionStatus = null;
    }
    applyConnectionStatus(connectionStatus);
}

async function checkConnectionOnOpen() {
    if (!userSettings.serverUrl) {
        connectionStatus = await loadConnectionStatus();
        if (connectionStatus && isStatusForSettings(connectionStatus, userSettings)) {
            applyConnectionStatus(connectionStatus);
        } else {
            applyConnectionStatus(null);
        }
        return;
    }

    await refreshConnectionStatus(userSettings, { silent: true, showLoading: false });
}

function readUserSettingsFromForm() {
    const serverUrl = normalizeServerUrl(document.getElementById('serverUrlInput')?.value);
    const apiKey = document.getElementById('apiKeyInput')?.value?.trim() || '';
    const autoSync = !!document.getElementById('autoSyncInput')?.checked;

    if (serverUrl && !/^https?:\/\//i.test(serverUrl)) {
        throw new Error('服务器地址需以 http:// 或 https:// 开头');
    }

    return { serverUrl, apiKey, autoSync };
}

function isAutoSyncEnabled() {
    return !!(userSettings.autoSync && userSettings.serverUrl);
}

async function loadUserSettings() {
    return new Promise((resolve) => {
        if (!chrome?.storage?.local) {
            resolve(userSettings);
            return;
        }
        chrome.storage.local.get([USER_SETTINGS_KEY], (result) => {
            userSettings = {
                serverUrl: '',
                apiKey: '',
                autoSync: false,
                ...(result[USER_SETTINGS_KEY] || {}),
            };
            resolve(userSettings);
        });
    });
}

async function persistUserSettings(settings) {
    userSettings = settings;
    return new Promise((resolve, reject) => {
        if (!chrome?.storage?.local) {
            reject(new Error('存储不可用'));
            return;
        }
        chrome.storage.local.set({ [USER_SETTINGS_KEY]: settings }, () => {
            if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
            else resolve();
        });
    });
}

function applyConnectionStatus(status) {
    connectionStatus = status;
    if (!status) {
        updateConnectionStatus('idle', '未连接');
        return;
    }
    updateConnectionStatus(status.state, formatConnectionStatusText(status));
}

function updateConnectionStatus(state, text) {
    const el = document.getElementById('connectionStatus');
    if (!el) return;
    el.className = `status-badge ${state}`;
    el.textContent = text;
}

function buildAuthHeaders() {
    return buildConnectionAuthHeaders(userSettings.apiKey);
}

async function refreshConnectionStatus(settings, { silent = false, showLoading = true } = {}) {
    if (!settings?.serverUrl) {
        const status = { state: 'idle', message: '未配置服务器', checkedAt: Date.now(), serverUrl: '' };
        applyConnectionStatus(status);
        return status;
    }

    if (showLoading) updateConnectionStatus('loading', '连接中…');

    const status = await runHealthCheck(settings);
    applyConnectionStatus(status);

    if (!silent) {
        if (status.state === 'ok') showToast('连接成功', 'success');
        else if (status.state === 'warning') showToast('服务器可达，但鉴权可能有问题', 'warning');
        else if (status.state === 'error') showToast(status.message, 'error');
    }

    return status;
}

async function testServerConnection() {
    let settings;
    try {
        settings = readUserSettingsFromForm();
    } catch (e) {
        showToast(e.message, 'error');
        return;
    }

    if (!settings.serverUrl) {
        showToast('请先填写服务器地址', 'warning');
        return;
    }

    await refreshConnectionStatus(settings, { silent: false });
}

async function fetchServerPayload() {
    const base = userSettings.serverUrl;
    const endpoints = [`${base}/api/2fa/accounts`, `${base}/api/2fa`, `${base}/2fa/accounts`];
    let lastError = '未找到有效数据';

    for (const url of endpoints) {
        try {
            const res = await fetch(url, {
                method: 'GET',
                headers: buildAuthHeaders(),
                signal: AbortSignal.timeout(12000),
            });
            if (!res.ok) {
                lastError = `HTTP ${res.status}`;
                continue;
            }
            const data = await res.json();
            const accounts = normalizeAccountsPayload(data);
            if (!accounts) continue;
            return { accounts, nextVaultId: data?.nextVaultId };
        } catch (e) {
            lastError = e?.message || '请求失败';
        }
    }

    return { error: lastError };
}

function getItemTimestamp(item) {
    return item?.updatedAt || item?.createdAt || 0;
}

function mergeVaultItems(localItems, serverItems) {
    const map = new Map(localItems.map((item) => [item.id, { ...item }]));
    let added = 0;
    let updated = 0;

    for (const raw of serverItems) {
        const serverItem = normalizeItem(raw);
        const localItem = map.get(serverItem.id);
        if (!localItem) {
            map.set(serverItem.id, serverItem);
            added += 1;
            continue;
        }
        if (getItemTimestamp(serverItem) > getItemTimestamp(localItem)) {
            map.set(serverItem.id, { ...serverItem, createdAt: localItem.createdAt });
            updated += 1;
        }
    }

    return { items: Array.from(map.values()), added, updated };
}

function applyNextVaultIdFromPayload(nextVaultId) {
    if (nextVaultId === undefined || nextVaultId === null) return;
    const nextId = parseInt(String(nextVaultId), 10);
    if (Number.isFinite(nextId) && nextId > 0) {
        localStorage.setItem(NEXT_VAULT_ID_KEY, String(nextId));
    }
}

async function pullFromServerAndMerge({ silent = false } = {}) {
    if (!userSettings.serverUrl) {
        if (!silent) showToast('请先配置服务器地址', 'warning');
        return false;
    }

    if (!silent) showToast('正在拉取…', 'info', 1200);

    const result = await fetchServerPayload();
    if (result.error) {
        if (!silent) showToast(`拉取失败：${result.error}`, 'error');
        return false;
    }

    applyNextVaultIdFromPayload(result.nextVaultId);
    const { items, added, updated } = mergeVaultItems(vaultItems, result.accounts || []);
    vaultItems = items;
    reconcileNextVaultId(vaultItems);
    await saveVaultItems();
    await renderVaultList();

    if (!silent) {
        const parts = [`共 ${vaultItems.length} 条`];
        if (added) parts.push(`新增 ${added} 条`);
        if (updated) parts.push(`更新 ${updated} 条`);
        showToast(`已合并：${parts.join('，')}`, 'success');
    }

    return true;
}

async function pushToServer({ silent = false, requireConfirm = false } = {}) {
    if (!userSettings.serverUrl) {
        if (!silent) {
            showToast('请先在服务器管理中配置服务器地址', 'warning');
            switchView('user');
        }
        return false;
    }

    if (vaultItems.length === 0) return false;

    if (requireConfirm) {
        const confirmed = await showConfirm(
            `将上传 ${vaultItems.length} 条数据到服务器并覆盖服务器上的数据，是否继续？`,
            '备份到服务器',
            'danger',
        );
        if (!confirmed) return false;
    }

    if (!silent) showToast('正在上传…', 'info', 1200);

    const url = `${userSettings.serverUrl}/api/2fa/accounts`;
    const payload = buildExportPayload(vaultItems);

    try {
        const res = await fetch(url, {
            method: 'PUT',
            headers: {
                ...buildAuthHeaders(),
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload),
            signal: AbortSignal.timeout(12000),
        });

        if (!res.ok) {
            let errMsg = `HTTP ${res.status}`;
            try {
                const errData = await res.json();
                if (errData?.error) errMsg = errData.error;
            } catch { /* ignore */ }
            if (!silent) showToast(`上传失败：${errMsg}`, 'error');
            return false;
        }

        if (!silent) {
            const data = await res.json();
            const count = normalizeAccountsPayload(data)?.length ?? vaultItems.length;
            showToast(`已上传 ${count} 条记录`, 'success');
        }
        return true;
    } catch (e) {
        if (!silent) showToast(`上传失败：${e?.message || '请求失败'}`, 'error');
        return false;
    }
}

async function onVaultChanged() {
    if (!isAutoSyncEnabled()) return;
    await pushToServer({ silent: true });
}

async function syncToServer() {
    await pushToServer({ requireConfirm: true });
}

async function syncFromServer() {
    if (!userSettings.serverUrl) {
        showToast('请先在服务器管理中配置服务器地址', 'warning');
        switchView('user');
        return;
    }

    const confirmed = await showConfirm(
        '将从服务器拉取数据并与本地合并，不会删除仅存在于本地的记录，是否继续？',
        '从服务器同步',
    );
    if (!confirmed) return;

    const ok = await pullFromServerAndMerge();
    if (ok) switchView('list');
}

async function autoPullOnOpen() {
    if (!isAutoSyncEnabled()) return;
    await pullFromServerAndMerge({ silent: true });
}

async function saveUserSettingsFromForm() {
    try {
        const settings = readUserSettingsFromForm();
        await persistUserSettings(settings);
        showToast('配置已保存', 'success');
        await refreshConnectionStatus(settings, { silent: true });
        if (settings.autoSync && settings.serverUrl) {
            await pullFromServerAndMerge({ silent: true });
            await renderVaultList();
        }
    } catch (e) {
        showToast(e.message || '保存失败', 'error');
    }
}

// --------- data.json import/export ---------
function normalizeAccountsPayload(payload) {
    let list = null;
    if (Array.isArray(payload)) list = payload;
    else if (payload && Array.isArray(payload.accounts)) list = payload.accounts;
    else if (payload && Array.isArray(payload.items)) list = payload.items;
    if (!list) return null;
    return list.map(normalizeItem);
}

function buildExportPayload(items) {
    const { nextVaultId } = reconcileNextVaultId(items);
    return {
        version: 2,
        exportedAt: Date.now(),
        nextVaultId,
        accounts: items,
    };
}

async function exportToDataJsonDownload() {
    try {
        if (!chrome?.downloads?.download) {
            showToast('导出不可用，请重新加载扩展', 'error');
            return;
        }

        const confirmed = await showConfirm(
            `将导出 ${vaultItems.length} 条数据，是否继续？`,
            '导出备份',
        );
        if (!confirmed) return;

        const blob = new Blob([JSON.stringify(buildExportPayload(vaultItems), null, 2)], {
            type: 'application/json',
        });
        const url = URL.createObjectURL(blob);

        chrome.downloads.download({ url, filename: 'data.json', saveAs: true }, (downloadId) => {
            setTimeout(() => URL.revokeObjectURL(url), 5000);
            if (chrome.runtime.lastError) {
                showToast(`导出失败：${chrome.runtime.lastError.message}`, 'error');
                return;
            }
            if (downloadId) showToast('导出已开始', 'success');
        });
    } catch (e) {
        showToast('导出失败', 'error');
    }
}

async function importFromDataJsonFile(file) {
    const text = await file.text();
    const parsed = JSON.parse(text);
    const accounts = normalizeAccountsPayload(parsed);
    if (!accounts) throw new Error('备份文件格式不正确');
    if (parsed?.nextVaultId) {
        const nextId = parseInt(String(parsed.nextVaultId), 10);
        if (Number.isFinite(nextId) && nextId > 0) {
            localStorage.setItem(NEXT_VAULT_ID_KEY, String(nextId));
        }
    }
    return accounts;
}

function applyImportedVaultItems(accounts) {
    vaultItems = accounts;
    reconcileNextVaultId(vaultItems);
}

// --------- QR / otpauth ---------
function parseQRCodeFromImage(file) {
    return new Promise((resolve, reject) => {
        if (!window.jsQR) return reject(new Error('二维码库未加载'));

        const reader = new FileReader();
        reader.onload = (e) => {
            const img = new Image();
            img.onload = () => {
                const canvas = document.createElement('canvas');
                const ctx = canvas.getContext('2d');
                if (!ctx) return reject(new Error('Canvas 初始化失败'));
                canvas.width = img.width;
                canvas.height = img.height;
                ctx.drawImage(img, 0, 0);
                const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const code = window.jsQR(imageData.data, imageData.width, imageData.height);
                resolve(code ? code.data : null);
            };
            img.onerror = () => reject(new Error('图片加载失败'));
            img.src = e.target?.result;
        };
        reader.onerror = () => reject(new Error('文件读取失败'));
        reader.readAsDataURL(file);
    });
}

async function parseOtpauthUrl(url) {
    if (!url?.startsWith('otpauth://')) {
        showToast('无效的 otpauth 地址', 'error');
        return null;
    }

    try {
        const urlObj = new URL(url);
        const label = decodeURIComponent(urlObj.pathname.substring(1));
        const parts = label.split(':');
        let accountName = parts[0] || '';
        let issuer = parts.length > 1 ? parts[0] : '';
        if (parts.length > 1) accountName = parts.slice(1).join(':');

        const secret = urlObj.searchParams.get('secret');
        if (!secret) {
            showToast('未找到密钥参数', 'error');
            return null;
        }

        const issuerParam = urlObj.searchParams.get('issuer');
        if (issuerParam) issuer = issuerParam;

        const displayName =
            issuer && !accountName.includes(issuer) ? `${issuer} - ${accountName}` : accountName;

        document.getElementById('itemName').value = displayName;
        document.getElementById('totpSecret').value = secret.toUpperCase().replace(/\s+/g, '');
        document.getElementById('otpauthUrlInput').value = url;

        showToast('已解析二维码信息', 'success');
        return { name: displayName, secret: secret.toUpperCase().replace(/\s+/g, ''), otpauthUrl: url };
    } catch (error) {
        showToast(`解析失败：${error?.message || '未知错误'}`, 'error');
        return null;
    }
}

// --------- TOTP ---------
function base32Decode(str) {
    const base32chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    str = str.toUpperCase().replace(/\s+/g, '').replace(/=+$/, '');

    let bits = '';
    for (const ch of str) {
        const idx = base32chars.indexOf(ch);
        if (idx === -1) throw new Error(`无效字符: ${ch}`);
        bits += idx.toString(2).padStart(5, '0');
    }

    const bytes = [];
    for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
    return new Uint8Array(bytes);
}

async function generateTOTP(secret, timeStep = 30) {
    try {
        const key = base32Decode(secret);
        const timeCounter = Math.floor(Date.now() / 1000 / timeStep);
        const timeBuffer = new ArrayBuffer(8);
        new DataView(timeBuffer).setUint32(4, timeCounter, false);

        const importedKey = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
        const hash = await crypto.subtle.sign('HMAC', importedKey, timeBuffer);
        const hashArray = new Uint8Array(hash);

        const offset = hashArray[19] & 0x0f;
        const code =
            ((hashArray[offset] & 0x7f) << 24) |
            ((hashArray[offset + 1] & 0xff) << 16) |
            ((hashArray[offset + 2] & 0xff) << 8) |
            (hashArray[offset + 3] & 0xff);

        return (code % 1000000).toString().padStart(6, '0');
    } catch {
        return '000000';
    }
}

function getTimeRemaining() {
    const timeStep = 30;
    return timeStep - (Math.floor(Date.now() / 1000) % timeStep);
}

function getTotpSecret(item) {
    let secret = item.secret;
    if (item.otpauthUrl) {
        try {
            secret = new URL(item.otpauthUrl).searchParams.get('secret') || secret;
        } catch { /* ignore */ }
    }
    return secret;
}

// --------- storage ---------
function persistVaultToLocalCache() {
    localStorage.setItem(VAULT_STORAGE_KEY, JSON.stringify(vaultItems));
}

async function loadVaultItems() {
    let stored = localStorage.getItem(VAULT_STORAGE_KEY);
    if (!stored) {
        stored = localStorage.getItem('twofaAccounts');
        if (stored) localStorage.removeItem('twofaAccounts');
    }

    if (stored) {
        vaultItems = JSON.parse(stored).map(normalizeItem);
        reconcileNextVaultId(vaultItems);
        await renderVaultList();
        return;
    }

    try {
        const url = chrome?.runtime?.getURL?.('data.json');
        if (url) {
            const res = await fetch(url, { cache: 'no-store' });
            if (res.ok) {
                const accounts = normalizeAccountsPayload(await res.json());
                if (accounts) {
                    vaultItems = accounts;
                    reconcileNextVaultId(vaultItems);
                    persistVaultToLocalCache();
                    await renderVaultList();
                    return;
                }
            }
        }
    } catch (e) {
        console.error('load data.json failed:', e);
    }

    vaultItems = [];
    await renderVaultList();
}

async function saveVaultItems() {
    persistVaultToLocalCache();
}

function parseNumericId(id) {
    const raw = String(id ?? '').trim();
    if (!/^\d+$/.test(raw)) return null;
    const value = Number(raw);
    return Number.isSafeInteger(value) ? value : null;
}

function getStoredNextVaultId() {
    const stored = localStorage.getItem(NEXT_VAULT_ID_KEY) || localStorage.getItem('nextTwofaId');
    const parsed = stored ? parseInt(stored, 10) : 1;
    return Number.isFinite(parsed) && parsed > 0 ? parsed : 1;
}

function reconcileNextVaultId(items = vaultItems) {
    let maxNumericId = 0;
    const usedIds = new Set();

    for (const item of items) {
        usedIds.add(String(item.id));
        const numericId = parseNumericId(item.id);
        if (numericId !== null && numericId > maxNumericId) {
            maxNumericId = numericId;
        }
    }

    const storedNext = getStoredNextVaultId();
    const nextVaultId = Math.max(maxNumericId + 1, storedNext);
    localStorage.setItem(NEXT_VAULT_ID_KEY, String(nextVaultId));
    return { nextVaultId, usedIds };
}

function getSnowflakeWorkerId() {
    const key = 'vaultWorkerId';
    let id = localStorage.getItem(key);
    if (!id) {
        id = String(Math.floor(Math.random() * 32));
        localStorage.setItem(key, id);
    }
    return parseInt(id, 10) & 31;
}

function generateItemId() {
    const workerId = getSnowflakeWorkerId();
    let timestamp = Date.now() - SNOWFLAKE_EPOCH;

    if (timestamp === snowflakeLastTime) {
        snowflakeSequence = (snowflakeSequence + 1) & 0xfff;
        if (snowflakeSequence === 0) {
            while (timestamp <= snowflakeLastTime) {
                timestamp = Date.now() - SNOWFLAKE_EPOCH;
            }
        }
    } else {
        snowflakeSequence = 0;
        snowflakeLastTime = timestamp;
    }

    const id = (BigInt(timestamp) << 17n) | (BigInt(workerId) << 12n) | BigInt(snowflakeSequence);
    return id.toString(36);
}

function getNextItemId() {
    const { nextVaultId, usedIds } = reconcileNextVaultId();
    let candidate = nextVaultId;

    while (usedIds.has(String(candidate))) {
        candidate += 1;
    }

    localStorage.setItem(NEXT_VAULT_ID_KEY, String(candidate + 1));
    return String(candidate);
}

function createItemId() {
    const { usedIds } = reconcileNextVaultId();
    let id = generateItemId();
    while (usedIds.has(id)) {
        id = generateItemId();
    }
    return id;
}

// --------- render ---------
function renderCardHeader(item) {
    const type = getItemType(item);
    const badge = ITEM_TYPES[type]?.label || type;
    return `
        <div class="account-card-header">
            <div class="account-name">
                ${escapeHtml(item.name)}
                <span class="type-badge ${type}">${badge}</span>
            </div>
            <div class="account-actions">
                <button type="button" class="account-action" data-action="edit" title="编辑" aria-label="编辑">
                    <svg viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                </button>
                <button type="button" class="account-action danger" data-action="delete" title="删除" aria-label="删除">
                    <svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
            </div>
        </div>
    `;
}

function bindCardActions(itemEl, item) {
    itemEl.querySelector('[data-action="edit"]')?.addEventListener('click', () => editItem(item.id));
    itemEl.querySelector('[data-action="delete"]')?.addEventListener('click', async () => {
        const confirmed = await showConfirm(`确定删除「${item.name}」？`, '删除记录', 'danger');
        if (!confirmed) return;
        vaultItems = vaultItems.filter((a) => a.id !== item.id);
        await saveVaultItems();
        await onVaultChanged();
        await renderVaultList();
        showToast('已删除', 'success', 1500);
    });

    itemEl.querySelectorAll('[data-copy-action]').forEach((btn) => {
        const field = btn.dataset.copyAction;
        const labels = {
            username: '用户名已复制',
            password: '密码已复制',
            url: '网址已复制',
            notes: '内容已复制',
            number: '卡号已复制',
            expiry: '有效期已复制',
            cvv: '安全码已复制',
            fullName: '姓名已复制',
            email: '邮箱已复制',
            phone: '电话已复制',
            address: '地址已复制',
        };
        btn.addEventListener('click', () => copyText(item[field] || '', labels[field] || '已复制'));
    });

    itemEl.querySelector('[data-action="open-url"]')?.addEventListener('click', () => {
        const url = item.url;
        if (url) chrome.tabs?.create?.({ url }) ?? window.open(url, '_blank');
    });
}

async function renderTotpCard(item, circumference, timeRemaining, dashOffset) {
    const secret = getTotpSecret(item);
    if (!secret) return null;

    const code = await generateTOTP(secret);
    const itemEl = document.createElement('article');
    itemEl.className = 'account-card';
    itemEl.dataset.id = item.id;
    itemEl.innerHTML = `
        ${renderCardHeader(item)}
        <div class="code-row">
            <div class="totp-code" data-account-id="${item.id}">${code}</div>
            <button type="button" class="copy-btn" data-copy-action="code">复制</button>
            <div class="timer-ring" data-account-id="${item.id}">
                <svg viewBox="0 0 32 32">
                    <circle class="track" cx="16" cy="16" r="14"></circle>
                    <circle class="progress ${timeRemaining <= 5 ? 'warning' : ''}"
                        cx="16" cy="16" r="14"
                        stroke-dasharray="${circumference}"
                        stroke-dashoffset="${dashOffset}"></circle>
                </svg>
            </div>
        </div>
    `;
    const codeBtn = itemEl.querySelector('[data-copy-action="code"]');
    codeBtn?.addEventListener('click', () => copyText(code, '验证码已复制'));
    bindCardActions(itemEl, item);
    return itemEl;
}

function renderLoginCard(item) {
    const itemEl = document.createElement('article');
    itemEl.className = 'account-card';
    itemEl.dataset.id = item.id;

    const urlBtn = item.url
        ? `<button type="button" class="copy-btn" data-action="open-url">打开</button>`
        : '';

    itemEl.innerHTML = `
        ${renderCardHeader(item)}
        <div class="cred-rows">
            ${item.username ? `
            <div class="cred-row">
                <span class="cred-label">用户名</span>
                <span class="cred-value">${escapeHtml(item.username)}</span>
                <button type="button" class="copy-btn" data-copy-action="username">复制</button>
            </div>` : ''}
            ${item.password ? `
            <div class="cred-row">
                <span class="cred-label">密码</span>
                <span class="cred-value muted">••••••••</span>
                <button type="button" class="copy-btn" data-copy-action="password">复制</button>
            </div>` : ''}
            ${item.url ? `
            <div class="cred-row">
                <span class="cred-label">网址</span>
                <span class="cred-value mono">${escapeHtml(item.url)}</span>
                ${urlBtn}
            </div>` : ''}
        </div>
    `;
    bindCardActions(itemEl, item);
    return itemEl;
}

function renderNoteCard(item) {
    const itemEl = document.createElement('article');
    itemEl.className = 'account-card';
    itemEl.dataset.id = item.id;
    itemEl.innerHTML = `
        ${renderCardHeader(item)}
        <div class="note-preview">${escapeHtml(item.notes || '')}</div>
        ${item.notes ? `<button type="button" class="copy-btn" style="margin-top:8px" data-copy-action="notes">复制</button>` : ''}
    `;
    bindCardActions(itemEl, item);
    return itemEl;
}

function renderCardItem(item) {
    const itemEl = document.createElement('article');
    itemEl.className = 'account-card';
    itemEl.dataset.id = item.id;
    itemEl.innerHTML = `
        ${renderCardHeader(item)}
        <div class="cred-rows">
            ${item.cardholder ? `
            <div class="cred-row">
                <span class="cred-label">持卡人</span>
                <span class="cred-value">${escapeHtml(item.cardholder)}</span>
            </div>` : ''}
            ${item.number ? `
            <div class="cred-row">
                <span class="cred-label">卡号</span>
                <span class="cred-value mono">${escapeHtml(maskCardNumber(item.number))}</span>
                <button type="button" class="copy-btn" data-copy-action="number">复制</button>
            </div>` : ''}
            ${item.expiry ? `
            <div class="cred-row">
                <span class="cred-label">有效期</span>
                <span class="cred-value mono">${escapeHtml(item.expiry)}</span>
                <button type="button" class="copy-btn" data-copy-action="expiry">复制</button>
            </div>` : ''}
            ${item.cvv ? `
            <div class="cred-row">
                <span class="cred-label">安全码</span>
                <span class="cred-value muted">•••</span>
                <button type="button" class="copy-btn" data-copy-action="cvv">复制</button>
            </div>` : ''}
        </div>
    `;
    bindCardActions(itemEl, item);
    return itemEl;
}

function renderIdentityCard(item) {
    const itemEl = document.createElement('article');
    itemEl.className = 'account-card';
    itemEl.dataset.id = item.id;

    const fieldMap = [
        ['姓名', 'fullName', item.fullName],
        ['邮箱', 'email', item.email],
        ['电话', 'phone', item.phone],
        ['地址', 'address', item.address],
    ].filter(([, , v]) => v);

    const rows = fieldMap
        .map(
            ([label, field, value]) => `
        <div class="cred-row">
            <span class="cred-label">${label}</span>
            <span class="cred-value">${escapeHtml(value)}</span>
            <button type="button" class="copy-btn" data-copy-action="${field}">复制</button>
        </div>`,
        )
        .join('');

    itemEl.innerHTML = `
        ${renderCardHeader(item)}
        <div class="cred-rows">${rows}</div>
    `;
    bindCardActions(itemEl, item);
    return itemEl;
}

async function renderVaultList() {
    const container = document.getElementById('vaultContainer');
    if (!container) return;

    updateHeader();

    const items = getFilteredItems();

    if (items.length === 0) {
        const emptyDesc =
            currentFilter === 'all'
                ? '点击右上角添加，或在用户中心导入备份'
                : '当前分类暂无记录';
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state-title">暂无记录</div>
                <div class="empty-state-desc">${emptyDesc}</div>
            </div>
        `;
        return;
    }

    container.innerHTML = '';
    const circumference = 2 * Math.PI * 14;
    const timeRemaining = getTimeRemaining();
    const dashOffset = circumference - (timeRemaining / 30) * circumference;

    for (const item of items) {
        try {
            const type = getItemType(item);
            let cardEl = null;

            if (type === 'totp') {
                cardEl = await renderTotpCard(item, circumference, timeRemaining, dashOffset);
            } else if (type === 'login') {
                cardEl = renderLoginCard(item);
            } else if (type === 'note') {
                cardEl = renderNoteCard(item);
            } else if (type === 'card') {
                cardEl = renderCardItem(item);
            } else if (type === 'identity') {
                cardEl = renderIdentityCard(item);
            }

            if (cardEl) container.appendChild(cardEl);
        } catch (e) {
            console.error('render item failed:', e);
        }
    }
}

function editItem(itemId) {
    const item = vaultItems.find((a) => a.id === itemId);
    if (!item) return;

    editingItemId = itemId;
    const type = getItemType(item);
    setFormType(type);
    clearItemForm();

    document.getElementById('itemName').value = item.name || '';

    if (type === 'totp') {
        document.getElementById('totpSecret').value = getTotpSecret(item) || '';
        const otpauthInput = document.getElementById('otpauthUrlInput');
        if (otpauthInput) {
            otpauthInput.value = item.otpauthUrl || '';
            otpauthInput.classList.toggle('hidden', !item.otpauthUrl);
        }
    } else if (type === 'login') {
        document.getElementById('loginUrl').value = item.url || '';
        document.getElementById('loginUsername').value = item.username || '';
        document.getElementById('loginPassword').value = item.password || '';
        document.getElementById('loginNotes').value = item.notes || '';
    } else if (type === 'note') {
        document.getElementById('noteContent').value = item.notes || '';
    } else if (type === 'card') {
        document.getElementById('cardholder').value = item.cardholder || '';
        document.getElementById('cardNumber').value = item.number || '';
        document.getElementById('cardExpiry').value = item.expiry || '';
        document.getElementById('cardCvv').value = item.cvv || '';
        document.getElementById('cardNotes').value = item.notes || '';
    } else if (type === 'identity') {
        document.getElementById('identityFullName').value = item.fullName || '';
        document.getElementById('identityEmail').value = item.email || '';
        document.getElementById('identityPhone').value = item.phone || '';
        document.getElementById('identityAddress').value = item.address || '';
        document.getElementById('identityNotes').value = item.notes || '';
    }

    currentView = 'form';
    setActivePanel('itemFormView');
    updateHeader();
}

function buildItemFromForm() {
    const type = document.getElementById('itemTypeSelect')?.value || editingItemType;
    const name = document.getElementById('itemName')?.value?.trim() || '';

    if (!name) throw new Error('请填写名称');

    const base = { type, name };

    if (type === 'totp') {
        const otpauthUrl = document.getElementById('otpauthUrlInput')?.value?.trim() || '';
        let secret = '';
        let finalOtpauthUrl = '';

        if (otpauthUrl?.startsWith('otpauth://')) {
            const urlObj = new URL(otpauthUrl);
            secret = urlObj.searchParams.get('secret') || '';
            if (!secret) throw new Error('otpauth 地址中未找到密钥');
            finalOtpauthUrl = otpauthUrl;
        } else {
            secret = (document.getElementById('totpSecret')?.value || '').trim().replace(/\s+/g, '').toUpperCase();
            if (!secret) throw new Error('请填写密钥或导入 otpauth 地址');
            if (!/^[A-Z2-7]{16,}$/.test(secret)) throw new Error('密钥格式不正确');
        }

        return { ...base, secret, ...(finalOtpauthUrl ? { otpauthUrl: finalOtpauthUrl } : {}) };
    }

    if (type === 'login') {
        const url = document.getElementById('loginUrl')?.value?.trim() || '';
        const username = document.getElementById('loginUsername')?.value?.trim() || '';
        const password = document.getElementById('loginPassword')?.value || '';
        const notes = document.getElementById('loginNotes')?.value?.trim() || '';
        if (!username && !password) throw new Error('请至少填写用户名或密码');
        return { ...base, url, username, password, notes };
    }

    if (type === 'note') {
        const notes = document.getElementById('noteContent')?.value?.trim() || '';
        if (!notes) throw new Error('请填写笔记内容');
        return { ...base, notes };
    }

    if (type === 'card') {
        const cardholder = document.getElementById('cardholder')?.value?.trim() || '';
        const number = document.getElementById('cardNumber')?.value?.trim() || '';
        const expiry = document.getElementById('cardExpiry')?.value?.trim() || '';
        const cvv = document.getElementById('cardCvv')?.value?.trim() || '';
        const notes = document.getElementById('cardNotes')?.value?.trim() || '';
        if (!number && !cardholder) throw new Error('请至少填写持卡人或卡号');
        return { ...base, cardholder, number, expiry, cvv, notes };
    }

    if (type === 'identity') {
        const fullName = document.getElementById('identityFullName')?.value?.trim() || '';
        const email = document.getElementById('identityEmail')?.value?.trim() || '';
        const phone = document.getElementById('identityPhone')?.value?.trim() || '';
        const address = document.getElementById('identityAddress')?.value?.trim() || '';
        const notes = document.getElementById('identityNotes')?.value?.trim() || '';
        if (!fullName && !email && !phone) throw new Error('请至少填写一项身份信息');
        return { ...base, fullName, email, phone, address, notes };
    }

    throw new Error('未知类型');
}

// --------- refresh ---------
function startTotpRefresh() {
    if (totpRefreshInterval) clearInterval(totpRefreshInterval);
    totpRefreshInterval = setInterval(() => updateTotpCodes(), 1000);
}

async function updateTotpCodes() {
    const container = document.getElementById('vaultContainer');
    if (!container || currentView !== 'list') return;

    const hasTotp = getFilteredItems().some((i) => getItemType(i) === 'totp');
    if (!hasTotp) return;

    const timeRemaining = getTimeRemaining();
    const circumference = 2 * Math.PI * 14;
    const dashOffset = circumference - (timeRemaining / 30) * circumference;

    for (const item of vaultItems) {
        if (getItemType(item) !== 'totp') continue;
        try {
            const secret = getTotpSecret(item);
            if (!secret) continue;

            const code = await generateTOTP(secret);
            const codeEl = container.querySelector(`.totp-code[data-account-id="${item.id}"]`);
            const progressCircle = container.querySelector(
                `.timer-ring[data-account-id="${item.id}"] .progress`,
            );

            if (codeEl && codeEl.textContent !== code) codeEl.textContent = code;
            if (progressCircle) {
                progressCircle.style.strokeDashoffset = dashOffset;
                progressCircle.classList.toggle('warning', timeRemaining <= 5);
            }

            const copyBtn = container.querySelector(
                `.account-card[data-id="${item.id}"] [data-copy-action="code"]`,
            );
            if (copyBtn) {
                copyBtn.onclick = () => copyText(code, '验证码已复制');
            }
        } catch { /* ignore */ }
    }

    if (timeRemaining === 30) await renderVaultList();
}

// --------- events ---------
function bindEvents() {
    document.getElementById('navList')?.addEventListener('click', () => switchView('list'));
    document.getElementById('navUser')?.addEventListener('click', () => switchView('user'));

    document.getElementById('filterTabs')?.addEventListener('click', async (e) => {
        const tab = e.target.closest('.filter-tab');
        if (!tab) return;
        currentFilter = tab.dataset.filter;
        document.querySelectorAll('.filter-tab').forEach((el) => {
            el.classList.toggle('active', el.dataset.filter === currentFilter);
        });
        await renderVaultList();
    });

    document.getElementById('itemTypeSelect')?.addEventListener('change', (e) => {
        if (!editingItemId) setFormType(e.target.value);
    });

    document.getElementById('saveUserSettingsBtn')?.addEventListener('click', saveUserSettingsFromForm);
    document.getElementById('testConnectionBtn')?.addEventListener('click', testServerConnection);
    document.getElementById('syncToServerBtn')?.addEventListener('click', syncToServer);
    document.getElementById('syncFromServerBtn')?.addEventListener('click', syncFromServer);

    document.getElementById('toggleApiKeyBtn')?.addEventListener('click', () => {
        const input = document.getElementById('apiKeyInput');
        if (!input) return;
        input.type = input.type === 'password' ? 'text' : 'password';
    });

    document.getElementById('toggleLoginPasswordBtn')?.addEventListener('click', () => {
        const input = document.getElementById('loginPassword');
        if (!input) return;
        input.type = input.type === 'password' ? 'text' : 'password';
    });

    document.getElementById('backupFileBtn')?.addEventListener('click', exportToDataJsonDownload);
    document.getElementById('restoreFromFileBtn')?.addEventListener('click', () => {
        document.getElementById('twofaImportJsonInput')?.click();
    });

    document.getElementById('twofaImportJsonInput')?.addEventListener('change', async (e) => {
        const file = e.target?.files?.[0];
        if (!file) return;
        try {
            const accounts = await importFromDataJsonFile(file);
            const confirmed = await showConfirm(
                `将覆盖当前 ${vaultItems.length} 条数据，导入 ${accounts.length} 条，是否继续？`,
                '导入备份',
                'danger',
            );
            if (!confirmed) return;
            applyImportedVaultItems(accounts);
            await saveVaultItems();
            await renderVaultList();
            switchView('list');
            showToast('导入成功', 'success');
        } catch (err) {
            showToast(err?.message || '导入失败', 'error');
        } finally {
            e.target.value = '';
        }
    });

    document.getElementById('saveItemBtn')?.addEventListener('click', async () => {
        try {
            const data = buildItemFromForm();

            if (editingItemId) {
                const idx = vaultItems.findIndex((a) => a.id === editingItemId);
                if (idx !== -1) {
                    vaultItems[idx] = {
                        id: editingItemId,
                        createdAt: vaultItems[idx].createdAt,
                        updatedAt: Date.now(),
                        ...data,
                    };
                }
            } else {
                vaultItems.push({ id: createItemId(), createdAt: Date.now(), ...data });
            }

            const isEdit = !!editingItemId;
            editingItemId = null;

            await saveVaultItems();
            await onVaultChanged();
            await renderVaultList();
            hideItemForm();
            showToast(isEdit ? '已更新' : '已添加', 'success');
        } catch (e) {
            showToast(e.message || '保存失败', 'error');
        }
    });

    document.getElementById('scanQRBtn')?.addEventListener('click', () => {
        document.getElementById('qrImageInput')?.click();
    });

    document.getElementById('qrImageInput')?.addEventListener('change', async (e) => {
        const file = e.target?.files?.[0];
        if (!file) return;
        try {
            const result = await parseQRCodeFromImage(file);
            if (result) {
                setFormType('totp');
                await parseOtpauthUrl(result);
            } else showToast('未能识别二维码', 'error');
        } catch (error) {
            showToast(error?.message || '识别失败', 'error');
        } finally {
            e.target.value = '';
        }
    });

    const otpauthUrlInput = document.getElementById('otpauthUrlInput');
    document.getElementById('importUrlBtn')?.addEventListener('click', () => {
        if (!otpauthUrlInput) return;
        const hidden = otpauthUrlInput.classList.contains('hidden');
        otpauthUrlInput.classList.toggle('hidden', !hidden);
        if (hidden) otpauthUrlInput.focus();
    });

    const tryParseUrl = async () => {
        const url = otpauthUrlInput?.value?.trim() || '';
        if (url?.startsWith('otpauth://')) {
            await parseOtpauthUrl(url);
            otpauthUrlInput.value = '';
            otpauthUrlInput.classList.add('hidden');
        } else if (url) {
            showToast('请输入有效的 otpauth 地址', 'warning');
        }
    };
    otpauthUrlInput?.addEventListener('blur', tryParseUrl);
    otpauthUrlInput?.addEventListener('keydown', async (e) => {
        if (e.key === 'Enter') await tryParseUrl();
    });

    chrome.storage?.onChanged?.addListener((changes, area) => {
        if (area !== 'local' || !changes[CONNECTION_STATUS_KEY]) return;
        if (currentView !== 'user') return;
        const status = changes[CONNECTION_STATUS_KEY].newValue;
        if (status && isStatusForSettings(status, userSettings)) {
            applyConnectionStatus(status);
        }
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    bindEvents();
    await loadUserSettings();
    await checkConnectionOnOpen();
    await loadVaultItems();
    await autoPullOnOpen();
    startTotpRefresh();
    switchView('list');
});
