const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

class FakeElement {
    constructor(id) {
        this.id = id;
        this.dataset = {};
        this.style = {};
        this.attributes = {};
        this.listeners = new Map();
        this.controls = [];
        this.hidden = false;
        this.disabled = false;
        this.tabIndex = 0;
        this.textContent = "";
        this.value = "";
    }

    addEventListener(type, listener) {
        this.listeners.set(type, listener);
    }

    setAttribute(name, value) {
        this.attributes[name] = value;
    }

    removeAttribute(name) {
        delete this.attributes[name];
    }

    querySelectorAll(selector) {
        return selector === ".select-candidate" ? this.controls : [];
    }

    focus() {
        this.focused = true;
    }
}

function createAppContext() {
    const source = fs.readFileSync(path.join(__dirname, "../../main/resources/static/app.js"), "utf8");
    const html = fs.readFileSync(path.join(__dirname, "../../main/resources/static/index.html"), "utf8");
    const elements = new Map(Array.from(html.matchAll(/\bid="([^"]+)"/g), ([, id]) => [id, new FakeElement(id)]));
    const requests = [];
    let ready;
    const document = {
        addEventListener(type, listener) {
            if (type === "DOMContentLoaded") ready = listener;
        },
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, new FakeElement(id));
            return elements.get(id);
        }
    };
    const context = vm.createContext({
        document,
        fetch: async (url, options = {}) => {
            requests.push({url, options});
            return {
            ok: true,
            status: 200,
            json: async () => url === "/api/output-directory"
                ? {path: "C:\\Music\\SongMetadataStudio\\Output"}
                : url === "/api/shutdown"
                    ? {message: "The local server is shutting down. Saved FLAC copies are kept."}
                : {mediaToolsAvailable: true, ytmusicAvailable: true, message: "Ready"}
            };
        },
        localStorage: {getItem: () => null, setItem() {}, removeItem() {}},
        window: {confirm: () => true},
        console
    });
    vm.runInContext(source, context);
    ready();
    return {context, elements, requests};
}

test("workflow tabs support arrow-key switching and expose the selected panel", async () => {
    const {elements} = createAppContext();
    const batchTab = elements.get("mode-batch-tab");
    let prevented = false;
    batchTab.listeners.get("keydown")({
        key: "ArrowLeft",
        preventDefault() {
            prevented = true;
        }
    });

    assert.equal(prevented, true);
    assert.equal(elements.get("mode-single-tab").attributes["aria-selected"], "true");
    assert.equal(elements.get("mode-batch-tab").attributes["aria-selected"], "false");
    assert.equal(elements.get("single-panel").hidden, false);
    assert.equal(elements.get("batch-panel").hidden, true);
    batchTab.listeners.get("keydown")({
        key: "End",
        preventDefault() {}
    });
    assert.equal(elements.get("mode-batch-tab").attributes["aria-selected"], "true");
    assert.equal(elements.get("batch-panel").hidden, false);
    assert.equal(elements.get("mode-batch-tab").tabIndex, 0);
});

test("batch progress updates accessible values and locks conflicting actions", async () => {
    const {context, elements} = createAppContext();
    const candidateButton = new FakeElement("candidate");
    elements.get("candidate-list").controls.push(candidateButton);
    vm.runInContext(`
        state.batchItems = [
            {searched: true, status: "ready", details: {}, error: ""},
            {searched: false, status: "searching", details: null, error: ""}
        ];
        state.batchSearching = true;
        updateBatchSummary();
        syncActionAvailability();
    `, context);

    assert.equal(elements.get("batch-progress-track").attributes["aria-valuemax"], "2");
    assert.equal(elements.get("batch-progress-track").attributes["aria-valuenow"], "1");
    assert.equal(elements.get("batch-progress-track").attributes["aria-valuetext"], "1 of 2 files searched");
    assert.equal(elements.get("batch-progress-fill").style.width, "50%");
    assert.equal(elements.get("search-button").disabled, true);
    assert.equal(candidateButton.disabled, true);
    assert.equal(elements.get("activity-status").dataset.state, "busy");
});

test("completed exports mark the create step and progress checklist complete", async () => {
    const {context, elements} = createAppContext();
    vm.runInContext(`updateWorkflowStep("create", false, true);`, context);

    assert.equal(elements.get("workflow-step-choose").dataset.state, "complete");
    assert.equal(elements.get("workflow-step-review").dataset.state, "complete");
    assert.equal(elements.get("workflow-step-create").dataset.state, "complete");
    assert.equal(elements.get("workflow-step-create").attributes["aria-current"], undefined);
    assert.equal(elements.get("workflow-check-create").dataset.state, "complete");
    assert.equal(elements.get("workflow-progress-track").attributes["aria-valuenow"], "100");
    assert.equal(elements.get("workflow-check-create-detail").textContent, "Verified FLAC copy ready");
});

test("batch workflow stays at Choose until files are in the queue", async () => {
    const {context, elements} = createAppContext();
    vm.runInContext(`setMode("batch");`, context);

    assert.equal(elements.get("workflow-step-choose").dataset.state, "current");
    assert.equal(elements.get("workflow-progress-label").textContent, "Choose");

    vm.runInContext(`
        state.batchItems = [{details: null, status: "searching"}];
        setMode("batch");
    `, context);

    assert.equal(elements.get("workflow-step-review").dataset.state, "current");
    assert.equal(elements.get("workflow-progress-label").textContent, "Review");
});

test("batch queue limit is 100 tracks and playback URLs encode Unicode output paths", async () => {
    const {context} = createAppContext();
    assert.equal(vm.runInContext("MAX_BATCH_FILES", context), 100);
    const outputPath = "C:\\Music\\Música\\Beyoncé - Déjà Vu.flac";
    const url = vm.runInContext(`audioPlaybackUrl(${JSON.stringify(outputPath)})`, context);

    assert.equal(url, `/api/output/play?directory=${encodeURIComponent("C:\\Music\\Música")}`
        + `&name=${encodeURIComponent("Beyoncé - Déjà Vu.flac")}`);
});

test("shutdown confirms, uses the local shutdown endpoint, and locks the page", async () => {
    const {context, elements, requests} = createAppContext();
    await vm.runInContext(`shutdownApp()`, context);

    const shutdownRequest = requests.find((request) => request.url === "/api/shutdown");
    assert.ok(shutdownRequest);
    assert.equal(shutdownRequest.options.method, "POST");
    assert.equal(elements.get("shutdown-button").disabled, true);
    assert.equal(elements.get("shutdown-button").textContent, "Server shutting down");
    assert.match(elements.get("shutdown-hint").textContent, /Saved FLAC copies are kept/);
});
