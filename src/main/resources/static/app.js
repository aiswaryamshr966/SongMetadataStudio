const state = {
    mode: "single",
    singleRunId: 0,
    singleSearching: false,
    singleUploading: false,
    singleDetailsLoading: false,
    singleExporting: false,
    uploadQueueItems: [],
    searchId: null,
    audio: null,
    candidates: [],
    selected: null,
    metadata: null,
    selectionRequestId: 0,
    metadataEditVersions: new Map(),
    batchItems: [],
    batchRunId: 0,
    batchSearching: false,
    batchUploading: false,
    batchExporting: false,
    batchExportTotal: 0,
    batchExportCompleted: 0,
    shuttingDown: false,
    defaultOutputDirectory: "",
    outputDirectory: "",
    toastTimer: null
};

const $ = (id) => document.getElementById(id);
const MAX_UPLOAD_BYTES = 512 * 1024 * 1024;
const MAX_BATCH_FILES = 100;
const SUPPORTED_UPLOAD_EXTENSIONS = new Set([
    "3gp", "aac", "ac3", "aif", "aiff", "alac", "amr", "ape", "dff", "dsf",
    "eac3", "flac", "m4a", "mka", "mkv", "mov", "mp2", "mp3", "mp4", "oga",
    "ogg", "opus", "wav", "webm", "wma"
]);
const BATCH_FIELDS = [
    ["title", "Title", "text", true],
    ["artist", "Artist", "text", true],
    ["album", "Album", "text", false],
    ["albumArtist", "Album artist", "text", false],
    ["composer", "Composer", "text", false],
    ["writer", "Written by", "text", false],
    ["publisher", "Publisher / label", "text", false],
    ["genre", "Genre", "text", false],
    ["date", "Release date / year", "text", false],
    ["trackNumber", "Track", "number", false],
    ["trackTotal", "Track total", "number", false],
    ["discNumber", "Disc", "number", false],
    ["discTotal", "Disc total", "number", false],
    ["comment", "Comment", "text", false]
];

document.addEventListener("DOMContentLoaded", () => {
    $("mode-single-tab").addEventListener("click", () => setMode("single"));
    $("mode-batch-tab").addEventListener("click", () => setMode("batch"));
    $("shutdown-button").addEventListener("click", shutdownApp);
    const modeTabs = [$("mode-single-tab"), $("mode-batch-tab")];
    modeTabs.forEach((tab, index) => {
        tab.addEventListener("keydown", (event) => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            const nextIndex = event.key === "Home" ? 0
                : event.key === "End" ? modeTabs.length - 1
                    : (index + (event.key === "ArrowRight" ? 1 : -1) + modeTabs.length) % modeTabs.length;
            modeTabs[nextIndex].focus();
            setMode(nextIndex === 0 ? "single" : "batch");
        });
    });
    $("search-button").addEventListener("click", search);
    $("choose-single-button").addEventListener("click", chooseSingleFile);
    $("choose-batch-button").addEventListener("click", chooseBatchFiles);
    $("single-file-input").addEventListener("change", (event) => {
        const file = event.target.files?.[0];
        if (file) void uploadSingleFile(file).finally(() => { event.target.value = ""; });
    });
    $("batch-file-input").addEventListener("change", (event) => {
        const files = Array.from(event.target.files || []);
        if (files.length) void uploadBatchFiles(files).finally(() => { event.target.value = ""; });
    });
    $("search-batch-button").addEventListener("click", searchBatch);
    $("batch-export-button").addEventListener("click", exportBatch);
    $("batch-open-output-button").addEventListener("click", openOutputDirectory);
    $("output-directory").addEventListener("input", updateOutputDirectory);
    $("output-directory").addEventListener("blur", () => {
        if (!$("output-directory").value.trim()) {
            $("output-directory").value = state.defaultOutputDirectory;
            updateOutputDirectory();
        }
    });
    $("open-output-directory-button").addEventListener("click", openOutputDirectory);
    $("success-open-output-button").addEventListener("click", openOutputDirectory);
    bindDropzone("single-dropzone", "single");
    bindDropzone("batch-dropzone", "batch");
    $("song-path").addEventListener("keydown", (event) => {
        if (event.key === "Enter") search();
    });
    $("metadata-form").addEventListener("submit", exportFlac);
    $("metadata-form").addEventListener("input", (event) => {
        const id = event.target.id;
        if (id?.startsWith("tag-")) {
            state.metadataEditVersions.set(id, (state.metadataEditVersions.get(id) || 0) + 1);
        }
    });
    $("copy-path-button").addEventListener("click", copyOutputPath);
    setMode("single");
    updateWorkflowStep("choose");
    updateSessionQueue();
    syncActionAvailability();
    void loadOutputDirectory();
    checkHealth();
});

function setMode(mode) {
    const single = mode === "single";
    state.mode = single ? "single" : "batch";
    $("mode-single-tab").setAttribute("aria-selected", String(single));
    $("mode-single-tab").tabIndex = single ? 0 : -1;
    $("mode-batch-tab").setAttribute("aria-selected", String(!single));
    $("mode-batch-tab").tabIndex = single ? -1 : 0;
    $("single-panel").hidden = !single;
    $("batch-panel").hidden = single;
    $("candidate-section").hidden = !single || state.candidates.length === 0;
    $("metadata-section").hidden = !single || !state.selected;
    $("success-card").hidden = !single || !$("success-path").textContent;
    $("batch-section").hidden = single || state.batchItems.length === 0;
    if (single) {
        const complete = Boolean($("success-path").textContent);
        const step = complete ? "create" : state.selected || state.candidates.length ? "review" : "choose";
        updateWorkflowStep(step,
            state.singleSearching || state.singleUploading || state.singleDetailsLoading || state.singleExporting,
            complete);
    } else {
        const complete = state.batchItems.length > 0
            && state.batchItems.every((item) => item.status === "exported");
        const hasBatchInput = state.batchItems.length > 0 || state.uploadQueueItems.length > 0
            || $("batch-paths").value.trim().length > 0;
        const step = state.batchExporting || complete ? "create" : hasBatchInput ? "review" : "choose";
        updateWorkflowStep(step,
            state.batchSearching || state.batchUploading || state.batchExporting,
            complete);
    }
    updateSessionQueue();
}

function isOperationActive() {
    return state.shuttingDown || state.singleSearching || state.singleUploading || state.singleDetailsLoading
        || state.singleExporting || state.batchSearching || state.batchUploading || state.batchExporting;
}

function syncActionAvailability() {
    const busy = isOperationActive();
    $("search-button").disabled = busy;
    $("choose-single-button").disabled = busy;
    $("choose-batch-button").disabled = busy;
    $("search-batch-button").disabled = busy;
    $("song-path").disabled = state.singleSearching || state.singleUploading;
    $("batch-paths").disabled = state.batchSearching || state.batchUploading || state.batchExporting;
    $("output-directory").disabled = state.singleExporting || state.batchExporting || state.shuttingDown;
    $("candidate-list").querySelectorAll(".select-candidate").forEach((button) => {
        button.disabled = busy;
    });
    $("mode-single-tab").disabled = state.shuttingDown;
    $("mode-batch-tab").disabled = state.shuttingDown;
    $("shutdown-button").disabled = busy;
    $("single-dropzone").setAttribute("aria-disabled", String(busy));
    $("batch-dropzone").setAttribute("aria-disabled", String(busy));
}

function updateSessionQueue() {
    const uploads = state.uploadQueueItems;
    if (state.mode === "batch") {
        const showingUploads = state.batchUploading || (uploads.length && !$("upload-queue").hidden);
        const items = showingUploads ? uploads : state.batchItems;
        const ready = state.batchItems.filter((item) => item.status === "ready").length;
        const exported = state.batchItems.filter((item) => item.status === "exported").length;
        const failed = state.batchItems.filter((item) => item.status.endsWith("failed")).length;
        $("session-file-count").textContent = `${items.length} ${items.length === 1 ? "track" : "tracks"}`;
        if (showingUploads) {
            const uploaded = uploads.filter((item) => item.status === "uploaded").length;
            const uploadFailures = uploads.filter((item) => item.status === "failed").length;
            $("session-file-summary").textContent = `${uploaded} uploaded · ${uploadFailures} need attention`;
        } else if (state.batchItems.length) {
            $("session-file-summary").textContent = `${ready} ready · ${exported} created · ${failed} need attention`;
        } else {
            $("session-file-summary").textContent = "No files in this session yet.";
        }
        return;
    }

    const path = $("song-path").value.trim();
    const filename = state.audio?.filename || path.split(/[\\/]/).pop() || "";
    $("session-file-count").textContent = filename ? "1 track" : "0 tracks";
    $("session-file-summary").textContent = filename || "No files in this session yet.";
}

function bindDropzone(id, mode) {
    const dropzone = $(id);
    ["dragenter", "dragover"].forEach((eventName) => {
        dropzone.addEventListener(eventName, (event) => {
            event.preventDefault();
            if (!isOperationActive()) dropzone.classList.add("dragover");
        });
    });
    ["dragleave", "drop"].forEach((eventName) => {
        dropzone.addEventListener(eventName, (event) => {
            event.preventDefault();
            if (eventName === "drop" || !dropzone.contains(event.relatedTarget)) {
                dropzone.classList.remove("dragover");
            }
        });
    });
    dropzone.addEventListener("drop", (event) => {
        if (isOperationActive()) {
            showToast("Wait for the current task to finish before adding files.");
            return;
        }
        const files = Array.from(event.dataTransfer?.files || []);
        if (!files.length) return;
        if (mode === "single" && files.length === 1) {
            void uploadSingleFile(files[0]);
        } else {
            setMode("batch");
            if (mode === "single") showToast("Multiple files added to Batch studio.");
            void uploadBatchFiles(files);
        }
    });
}

function renderUploadQueue(files) {
    const list = $("upload-queue-list");
    state.uploadQueueItems = files.map((file) => {
        const row = document.createElement("li");
        row.className = "upload-queue-item";
        row.dataset.state = "queued";
        row.innerHTML = `<span><strong>${escapeHtml(file.name)}</strong><small>Waiting to upload</small></span>`
            + '<span class="upload-state">Queued</span>';
        return {filename: file.name, status: "queued", error: "", element: row};
    });
    list.replaceChildren(...state.uploadQueueItems.map((item) => item.element));
    $("upload-queue").hidden = state.uploadQueueItems.length === 0;
    $("upload-queue").open = true;
    updateUploadQueueSummary();
    updateSessionQueue();
}

function updateUploadQueueItem(index, status, error = "") {
    const item = state.uploadQueueItems[index];
    if (!item) return;
    item.status = status;
    item.error = error;
    item.element.dataset.state = status;
    item.element.querySelector("small").textContent = error || {
        queued: "Waiting to upload",
        uploading: "Uploading to this local app…",
        uploaded: "Ready for metadata matching",
        failed: "Upload could not be completed"
    }[status];
    item.element.querySelector(".upload-state").textContent = {
        queued: "Queued",
        uploading: "Uploading",
        uploaded: "Uploaded",
        failed: "Failed"
    }[status];
    updateUploadQueueSummary();
    updateSessionQueue();
}

function updateUploadQueueSummary() {
    const total = state.uploadQueueItems.length;
    const uploaded = state.uploadQueueItems.filter((item) => item.status === "uploaded").length;
    const failed = state.uploadQueueItems.filter((item) => item.status === "failed").length;
    const uploading = state.uploadQueueItems.filter((item) => item.status === "uploading").length;
    $("upload-queue-summary").textContent = uploading
        ? `Uploading ${uploaded + failed} of ${total} · ${uploading} in progress`
        : `${uploaded} uploaded${failed ? ` · ${failed} failed` : ""} · matching begins next`;
}

function updateWorkflowStep(current, busy = false, complete = false) {
    const steps = ["choose", "review", "create"];
    const currentIndex = steps.indexOf(current);
    steps.forEach((step, index) => {
        const element = $(`workflow-step-${step}`);
        const status = index < currentIndex || (complete && index === currentIndex) ? "complete"
            : index === currentIndex ? (busy ? "busy" : "current")
                : "upcoming";
        element.dataset.state = status;
        if (index === currentIndex && !complete) element.setAttribute("aria-current", "step");
        else element.removeAttribute("aria-current");
    });

    const hasMatch = current === "create" || Boolean(state.selected)
        || state.batchItems.some((item) => item.details);
    const checklistStates = {
        "workflow-check-file": current === "choose" ? (busy ? "busy" : "current") : "complete",
        "workflow-check-match": hasMatch ? "complete"
            : current === "review" ? (busy ? "busy" : "current") : "upcoming",
        "workflow-check-tags": current === "create" ? "complete"
            : state.selected || state.batchItems.some((item) => item.details) ? "current" : "upcoming",
        "workflow-check-create": complete && current === "create" ? "complete"
            : current !== "create" ? "upcoming"
            : busy ? "busy" : "current"
    };
    Object.entries(checklistStates).forEach(([id, status]) => {
        $(`${id}`).dataset.state = status;
    });

    const phaseLabels = {choose: "Choose", review: "Review", create: "Create"};
    $("workflow-progress-label").textContent = complete
        ? "Complete" : busy
        ? `${phaseLabels[current]} in progress`
        : phaseLabels[current] || "Ready";
    let progress = currentIndex <= 0 ? (busy ? 12 : 0)
        : currentIndex === 1 ? (hasMatch ? 62 : 38)
            : busy ? 86 : 100;
    if (complete) progress = 100;
    if (current === "review" && checklistStates["workflow-check-tags"] === "current") progress = 72;
    $("workflow-progress-track").setAttribute("aria-valuenow", String(progress));
    $("workflow-progress-track").setAttribute("aria-valuetext", `${progress}% · ${phaseLabels[current] || "Ready"}`);
    $("workflow-progress-fill").style.width = `${progress}%`;
    $("workflow-check-file-detail").textContent = current === "choose"
        ? "Drop a file or browse to add it" : state.mode === "batch" ? "Collection added to the queue" : "Audio file ready";
    $("workflow-check-match-detail").textContent = hasMatch
        ? "Release and runtime matched" : current === "review" ? "Choose the closest release" : "Waiting for audio";
    $("workflow-check-tags-detail").textContent = checklistStates["workflow-check-tags"] === "current"
        ? "Edit fields before creating" : "Missing credits stay blank";
    $("workflow-check-create-detail").textContent = current === "create"
        ? complete ? "Verified FLAC copy ready" : busy ? "Writing and verifying tags…" : "Ready to save a copy"
        : "Original audio remains untouched";
}

function setActivity(message, status = "ready") {
    $("activity-message").textContent = message;
    $("activity-status").dataset.state = status;
    $("workflow-progress-description").textContent = message;
    $("session-queue-status").textContent = message;
    $("session-queue-state").dataset.state = status;
    updateSessionQueue();
}

async function loadOutputDirectory() {
    try {
        const result = await request("/api/output-directory");
        state.defaultOutputDirectory = result.path;
        const saved = readSavedOutputDirectory();
        const typed = $("output-directory").value.trim();
        $("output-directory").value = typed || saved || result.path;
        updateOutputDirectory();
    } catch (error) {
        $("output-directory-hint").textContent = error.message;
        showError(error.message);
    }
}

function readSavedOutputDirectory() {
    try {
        return localStorage.getItem("songMetadataStudio.outputDirectory") || "";
    } catch {
        return "";
    }
}

function updateOutputDirectory() {
    const entered = $("output-directory").value.trim();
    state.outputDirectory = entered || state.defaultOutputDirectory;
    try {
        if (state.outputDirectory && state.outputDirectory !== state.defaultOutputDirectory) {
            localStorage.setItem("songMetadataStudio.outputDirectory", state.outputDirectory);
        } else {
            localStorage.removeItem("songMetadataStudio.outputDirectory");
        }
    } catch {
        // The path remains available for the current page even if browser storage is disabled.
    }
    $("output-directory-hint").textContent = state.outputDirectory
        ? `New single and batch copies will be saved to ${state.outputDirectory}. Existing files are not overwritten unless you choose replace.`
        : "Loading the default output folder…";
}

async function openOutputDirectory() {
    try {
        const result = await request("/api/output-directory/open", {path: state.outputDirectory});
        showToast(`Opened output folder: ${result.path}`);
    } catch (error) {
        showError(error.message);
    }
}

async function checkHealth() {
    try {
        const health = await request("/api/health");
        const ready = health.mediaToolsAvailable && health.ytmusicAvailable;
        $("health-dot").className = `status-dot ${ready ? "ready" : "error"}`;
        $("health-text").textContent = ready ? "Ready to tag" : health.message;
    } catch (error) {
        $("health-dot").className = "status-dot error";
        $("health-text").textContent = "Local service unavailable";
    }
}

function chooseSingleFile() {
    if (isOperationActive()) return;
    setMode("single");
    $("single-file-input").click();
}

async function shutdownApp() {
    if (isOperationActive()) return;
    const confirmed = window.confirm(
        "Shut down the local Song Metadata Studio server? Temporary uploads will be removed; saved FLAC copies will stay safe.");
    if (!confirmed) return;

    state.shuttingDown = true;
    $("shutdown-button").setAttribute("aria-busy", "true");
    $("shutdown-button").textContent = "Shutting down…";
    setActivity("The local server is shutting down.", "busy");
    syncActionAvailability();
    clearError();
    try {
        const result = await request("/api/shutdown", {});
        $("health-text").textContent = "Shutting down";
        $("shutdown-hint").textContent = result.message;
        setActivity(result.message, "success");
        $("shutdown-button").textContent = "Server shutting down";
    } catch (error) {
        state.shuttingDown = false;
        $("shutdown-button").removeAttribute("aria-busy");
        $("shutdown-button").textContent = "Shut down app";
        showError(error.message);
        syncActionAvailability();
    }
}

async function uploadSingleFile(file) {
    if (isOperationActive()) return;
    setMode("single");
    state.selectionRequestId++;
    state.searchId = null;
    state.audio = null;
    state.candidates = [];
    state.selected = null;
    $("candidate-section").hidden = true;
    $("metadata-section").hidden = true;
    $("success-card").hidden = true;
    $("success-path").textContent = "";
    clearAudioPlayer($("success-audio"));
    $("success-player").hidden = true;
    state.singleUploading = true;
    setBusy($("choose-single-button"), true, "Uploading file…");
    setActivity(`Uploading ${file.name}`, "busy");
    updateWorkflowStep("choose", true);
    syncActionAvailability();
    try {
        validateUploadFile(file);
        const uploaded = await uploadFile(file);
        $("song-path").value = uploaded.path;
        updateSessionQueue();
        state.singleUploading = false;
        syncActionAvailability();
        await search();
    } catch (error) {
        showError(error.message);
    } finally {
        state.singleUploading = false;
        setBusy($("choose-single-button"), false);
        syncActionAvailability();
    }
}

function chooseBatchFiles() {
    if (isOperationActive()) return;
    setMode("batch");
    $("batch-file-input").click();
}

async function uploadBatchFiles(files) {
    if (isOperationActive()) return;
    setMode("batch");
    $("batch-section").hidden = true;
    state.batchUploading = true;
    let completedUploads = 0;
    renderUploadQueue(files);
    setBusy($("choose-batch-button"), true, `Uploading 0 of ${files.length} files…`);
    setActivity(`Preparing ${files.length} files for batch review`, "busy");
    updateWorkflowStep("choose", true);
    syncActionAvailability();
    try {
        const results = new Array(files.length);
        if (files.length > MAX_BATCH_FILES) {
            files.forEach((file, index) => {
                results[index] = {
                    filename: file.name,
                    error: `Select no more than ${MAX_BATCH_FILES} files per batch.`
                };
                updateUploadQueueItem(index, "failed", results[index].error);
            });
            completedUploads = files.length;
        } else {
            let nextIndex = 0;
            const uploadNext = async () => {
                while (nextIndex < files.length) {
                    const index = nextIndex++;
                    const file = files[index];
                    updateUploadQueueItem(index, "uploading");
                    try {
                        validateUploadFile(file);
                        const uploaded = await uploadFile(file);
                        results[index] = {path: uploaded.path, filename: uploaded.filename};
                        updateUploadQueueItem(index, "uploaded");
                    } catch (error) {
                        results[index] = {filename: file.name, error: error.message};
                        updateUploadQueueItem(index, "failed", error.message);
                    } finally {
                        completedUploads++;
                        setBusy($("choose-batch-button"), true,
                            `Uploading ${completedUploads} of ${files.length} files…`);
                        setActivity(`Uploaded or checked ${completedUploads} of ${files.length} files`, "busy");
                    }
                }
            };
            await Promise.all(Array.from(
                {length: Math.min(3, files.length)}, () => uploadNext()));
        }
        $("batch-paths").value = results.filter((result) => result.path)
            .map((result) => result.path).join("\n");
        updateSessionQueue();
        state.batchUploading = false;
        syncActionAvailability();
        await searchBatch(results);
    } catch (error) {
        showError(error.message);
    } finally {
        state.batchUploading = false;
        setBusy($("choose-batch-button"), false);
        syncActionAvailability();
    }
}

function validateUploadFile(file) {
    const extension = file.name.split(".").pop().toLowerCase();
    if (!SUPPORTED_UPLOAD_EXTENSIONS.has(extension) || !file.name.includes(".")) {
        throw new Error("Choose a supported audio file.");
    }
    if (file.size === 0) {
        throw new Error("The selected file is empty.");
    }
    if (file.size > MAX_UPLOAD_BYTES) {
        throw new Error("Each uploaded audio file must be 512 MiB or smaller.");
    }
}

async function uploadFile(file) {
    const formData = new FormData();
    formData.append("file", file, file.name);
    const response = await fetch("/api/uploads", {method: "POST", body: formData});
    let data;
    try {
        data = await response.json();
    } catch {
        throw new Error(`The local app returned an unreadable response (${response.status}).`);
    }
    if (!response.ok) throw new Error(data.message || `Upload failed (${response.status}).`);
    return data;
}

async function search() {
    if (isOperationActive()) return;
    setMode("single");
    const runId = ++state.singleRunId;
    state.singleSearching = true;
    state.batchRunId++;
    state.selectionRequestId++;
    const path = $("song-path").value.trim();
    if (!path) {
        state.singleSearching = false;
        syncActionAvailability();
        showError("Enter the full path to a song file.");
        $("song-path").focus();
        return;
    }

    setBusy($("search-button"), true, "Searching YouTube Music…");
    setActivity("Searching for matching releases…", "busy");
    updateWorkflowStep("choose", true);
    syncActionAvailability();
    clearError();
    $("candidate-section").hidden = true;
    $("metadata-section").hidden = true;
    $("success-card").hidden = true;
    $("batch-section").hidden = true;
    clearAudioPlayer($("success-audio"));
    $("success-player").hidden = true;
    state.searchId = null;
    state.audio = null;
    state.candidates = [];
    state.selected = null;
    $("success-path").textContent = "";
    try {
        const result = await request("/api/search", {path});
        if (runId !== state.singleRunId) return;
        state.searchId = result.searchId;
        state.audio = result.audio;
        state.candidates = result.candidates;
        $("search-summary").textContent =
            `${result.audio.filename} · ${result.audio.durationLabel} · Search: “${result.audio.searchQuery}”`;
        $("result-count").textContent = `${result.candidates.length} releases`;
        renderCandidates(result.candidates, result.audio.durationSeconds);
        $("candidate-section").hidden = false;
        updateWorkflowStep("review");
        const automaticMatch = findUniqueExactRuntimeMatch(result.candidates, result.audio.filename);
        if (automaticMatch) {
            setActivity("A unique title and runtime match was found", "success");
            showToast("A unique title and duration match was found. Loading its tags…");
            await selectCandidate(automaticMatch);
        } else {
            setActivity(`${result.candidates.length} releases found. Choose the closest match.`);
            $("candidate-section").scrollIntoView({behavior: "smooth", block: "start"});
        }
    } catch (error) {
        if (runId === state.singleRunId) {
            updateWorkflowStep("choose");
            showError(error.message);
        }
    } finally {
        if (runId === state.singleRunId) {
            state.singleSearching = false;
            setBusy($("search-button"), false);
            syncActionAvailability();
        }
    }
}

function parseBatchPaths(value) {
    return value.split(/\r?\n/)
        .map((line) => line.trim().replace(/^"(.*)"$/, "$1").trim())
        .filter(Boolean);
}

async function searchBatch(selectedFiles) {
    if (isOperationActive()) return;
    const files = selectedFiles || parseBatchPaths($("batch-paths").value)
        .map((path) => ({path, filename: path.split(/[\\/]/).pop() || path}));
    if (!files.length) {
        showError("Choose multiple files or paste one full path per line.");
        $("batch-paths").focus();
        return;
    }

    state.batchItems.forEach(stopBatchPlayer);
    setMode("batch");
    state.singleRunId++;
    state.selectionRequestId++;
    state.batchRunId++;
    const runId = state.batchRunId;
    state.batchSearching = true;
    state.batchExporting = false;
    state.batchExportCompleted = 0;
    state.selected = null;
    state.batchItems = files.map((file, index) => ({
        id: `${runId}-${index}`,
        runId,
        path: file.path || "",
        filename: file.filename || file.path?.split(/[\\/]/).pop() || "Audio file",
        status: file.error ? "upload-failed" : "queued",
        statusLabel: file.error ? "Upload failed" : "Waiting",
        searched: false,
        searchId: null,
        candidates: [],
        selectedCandidate: null,
        details: null,
        editedFields: new Set(),
        detailsRequestId: 0,
        error: file.error || "",
        outputPath: ""
    }));

    clearError();
    $("candidate-section").hidden = true;
    $("metadata-section").hidden = true;
    $("success-card").hidden = true;
    $("upload-queue").hidden = true;
    $("batch-completion-panel").hidden = true;
    $("batch-section").hidden = false;
    const list = $("batch-list");
    list.replaceChildren();
    state.batchItems.forEach((item) => {
        list.append(createBatchCard(item));
        if (item.error) setBatchStatus(item, "upload-failed", "Upload failed", item.error);
    });
    updateSessionQueue();
    $("batch-section").scrollIntoView({behavior: "smooth", block: "start"});
    setActivity(`Searching ${files.length} tracks in parallel…`, "busy");
    updateWorkflowStep("review", true);
    syncActionAvailability();
    updateBatchSummary();

    let nextIndex = 0;
    const searchNext = async () => {
        while (nextIndex < state.batchItems.length) {
            const item = state.batchItems[nextIndex++];
            if (runId !== state.batchRunId) return;
            if (item.error) {
                updateBatchSummary();
                continue;
            }
            setBatchStatus(item, "searching", "Searching");
            try {
                const result = await request("/api/search", {path: item.path});
                if (runId !== state.batchRunId) return;
                item.searched = true;
                item.searchId = result.searchId;
                item.candidates = result.candidates;
                setBatchCandidates(item);
                updateBatchSummary();
                const exactMatch = findUniqueExactRuntimeMatch(result.candidates, item.filename);
                if (exactMatch) {
                    item.card.querySelector(".batch-select").value = exactMatch.videoId;
                    await loadBatchDetails(item, exactMatch.videoId);
                } else {
                    setBatchStatus(item, "choose-release", "Choose a release");
                }
            } catch (error) {
                if (runId !== state.batchRunId) return;
                item.searched = true;
                setBatchStatus(item, "search-failed", "Search failed", error.message);
                updateBatchSummary();
            }
        }
    };

    try {
        const workers = Math.min(3, state.batchItems.length);
        await Promise.all(Array.from({length: workers}, () => searchNext()));
    } finally {
        if (runId === state.batchRunId) {
            state.batchSearching = false;
            syncActionAvailability();
            updateBatchSummary();
        }
    }
}

function createBatchCard(item) {
    const card = document.createElement("article");
    card.className = "batch-item";
    card.dataset.state = item.status;
    card.innerHTML = `
        <div class="batch-item-header">
            <div>
                <div class="batch-item-title">${escapeHtml(item.filename)}</div>
                <div class="batch-item-path">${escapeHtml(item.path || "Not uploaded")}</div>
            </div>
            <span class="batch-status" data-state="queued" aria-live="polite">Waiting</span>
        </div>
        <select class="batch-select" aria-label="YouTube Music suggestion for ${escapeHtml(item.filename)}" hidden>
            <option value="">Choose a release</option>
        </select>
        <div class="batch-review" hidden>
            <p class="batch-match" data-role="match"></p>
            <div class="batch-review-grid">
                ${BATCH_FIELDS.map(([key, label, type, required]) => `
                    <div class="batch-field${["title", "artist", "album", "comment"].includes(key) ? " wide" : ""}">
                        <label for="batch-${item.id}-${key}">${label}${required ? " *" : ""}</label>
                        <input id="batch-${item.id}-${key}" data-batch-field="${key}" type="${type}"
                               ${type === "number" ? 'min="1"' : ""} ${required ? "required" : ""}>
                    </div>`).join("")}
            </div>
        </div>
        <p class="batch-item-error" data-role="error" hidden></p>
        <div class="batch-output" data-role="output" hidden>
            <span data-role="output-path"></span>
            <div class="batch-player" data-role="player" hidden>
                <strong data-role="player-label"></strong>
                <audio data-role="audio" controls preload="none" aria-label="Play this created FLAC"></audio>
            </div>
        </div>`;
    item.card = card;
    card.querySelector(".batch-select").addEventListener("change", (event) => {
        if (event.target.value) {
            loadBatchDetails(item, event.target.value);
        } else {
            item.detailsRequestId++;
            item.details = null;
            item.selectedCandidate = null;
            item.card.querySelector(".batch-review").hidden = true;
            setBatchStatus(item, "choose-release", "Choose a release");
            updateBatchSummary();
        }
    });
    card.querySelector(".batch-review-grid").addEventListener("input", (event) => {
        const field = event.target.dataset.batchField;
        if (field) {
            item.editedFields.add(field);
            if (item.status === "export-failed" && item.details) {
                setBatchStatus(item, "ready", "Ready to create");
            }
            updateBatchSummary();
        }
    });
    return card;
}

function setBatchCandidates(item) {
    const select = item.card.querySelector(".batch-select");
    select.replaceChildren(new Option("Choose a release", ""));
    item.candidates.forEach((candidate) => {
        const summary = [
            candidate.title,
            (candidate.artists || []).join(", "),
            candidate.album,
            candidate.duration
        ].filter(Boolean).join(" · ");
        select.add(new Option(summary, candidate.videoId));
    });
    select.hidden = false;
}

async function loadBatchDetails(item, videoId) {
    const candidate = item.candidates.find((result) => result.videoId === videoId);
    if (!candidate) return;
    const requestId = ++item.detailsRequestId;
    item.selectedCandidate = candidate;
    item.details = null;
    item.outputPath = "";
    stopBatchPlayer(item);
    setBatchStatus(item, "loading-details", "Loading tags");
    item.card.querySelector('[data-role="output"]').hidden = true;
    item.card.querySelector(".batch-review").hidden = false;
    try {
        const details = await request("/api/details", {
            searchId: item.searchId,
            videoId
        });
        if (item.runId !== state.batchRunId || requestId !== item.detailsRequestId) return;
        item.details = details;
        BATCH_FIELDS.forEach(([key]) => {
            if (item.editedFields.has(key)) return;
            const input = item.card.querySelector(`[data-batch-field="${key}"]`);
            input.value = details[key] === null || details[key] === undefined ? "" : details[key];
        });
        const matchNotes = [details.catalogMatch, ...(details.warnings || [])].filter(Boolean);
        item.card.querySelector('[data-role="match"]').textContent = matchNotes.join(" · ")
            || "Fields use YouTube Music details, with source-file tags where available. Credits are not guessed.";
        setBatchStatus(item, "ready", "Ready to create");
    } catch (error) {
        if (item.runId !== state.batchRunId || requestId !== item.detailsRequestId) return;
        setBatchStatus(item, "details-failed", "Details failed", error.message);
    }
    updateBatchSummary();
}

function setBatchStatus(item, status, label, error = "") {
    item.status = status;
    item.statusLabel = label;
    item.error = error;
    item.card.dataset.state = status;
    const badge = item.card.querySelector(".batch-status");
    badge.dataset.state = status;
    badge.textContent = label;
    const errorElement = item.card.querySelector('[data-role="error"]');
    errorElement.textContent = error;
    errorElement.hidden = !error;
}

function updateBatchSummary() {
    const items = state.batchItems;
    const searched = items.filter((item) => item.searched).length;
    const ready = items.filter((item) => item.status === "ready").length;
    const retryable = items.filter((item) => item.details && item.status === "export-failed").length;
    const canCreate = ready + retryable;
    const exported = items.filter((item) => item.status === "exported").length;
    const failed = items.filter((item) => item.status.endsWith("failed")
        && !(item.details && item.status === "export-failed")).length;
    const parts = [`${searched} of ${items.length} files searched`];
    if (ready) parts.push(`${ready} ready`);
    if (retryable) parts.push(`${retryable} can retry`);
    if (failed) parts.push(`${failed} need attention`);
    if (exported) parts.push(`${exported} created`);
    $("batch-progress").textContent = parts.join(" · ");
    const progressTrack = $("batch-progress-track");
    const progressFill = $("batch-progress-fill");
    const progressMax = state.batchExporting
        ? Math.max(1, state.batchExportTotal)
        : Math.max(1, items.length);
    const progressValue = state.batchExporting
        ? state.batchExportCompleted
        : searched;
    progressTrack.setAttribute("aria-valuemax", String(progressMax));
    progressTrack.setAttribute("aria-valuenow", String(Math.min(progressValue, progressMax)));
    progressTrack.setAttribute("aria-valuetext", state.batchExporting
        ? `${progressValue} of ${progressMax} copies processed`
        : `${progressValue} of ${items.length} files searched`);
    progressFill.style.width = `${Math.min(100, Math.round(progressValue / progressMax * 100))}%`;
    progressTrack.dataset.state = state.batchExporting ? "creating"
        : state.batchSearching ? "searching"
            : failed ? "attention" : searched === items.length && items.length ? "complete" : "ready";
    const button = $("batch-export-button");
    button.disabled = state.batchSearching || state.batchExporting || canCreate === 0;
    button.textContent = canCreate
        ? `${exported ? "Continue" : retryable ? "Retry / create" : "Create"} ${canCreate} FLAC ${canCreate === 1 ? "copy" : "copies"}`
        : "Create ready FLAC copies";
    const summary = $("batch-create-summary");
    summary.textContent = state.batchExporting
        ? `Creating copies: ${state.batchExportCompleted} of ${state.batchExportTotal} processed · ${exported} created`
        : canCreate
            ? `${ready} ready${retryable ? ` · ${retryable} can retry` : ""}. Other files can be fixed or skipped independently.`
            : "Choose a release for each file before creating.";
    $("batch-completion-panel").hidden = exported === 0;
    $("batch-completion-count").textContent = `${exported} FLAC ${exported === 1 ? "copy" : "copies"} created`
        + (failed ? ` · ${failed} need attention` : "");
    $("batch-completion-path").textContent = state.outputDirectory
        ? `Saved to ${state.outputDirectory}` : "Saved in your persistent output folder";
    if (state.batchExporting) {
        setActivity(`Creating FLAC copies · ${state.batchExportCompleted} of ${state.batchExportTotal} processed`, "busy");
        updateWorkflowStep("create", true);
    } else if (state.batchSearching) {
        setActivity(`Matching tracks · ${searched} of ${items.length} searched`, "busy");
        updateWorkflowStep("review", true);
    } else if (items.length) {
        setActivity(failed
            ? `${searched} files checked · ${failed} need attention`
            : `${searched} files checked · ${ready + exported} ready or created`,
        failed ? "error" : "ready");
        updateWorkflowStep("review");
    }
    updateSessionQueue();
}

async function exportBatch() {
    if (isOperationActive()) return;
    const readyItems = state.batchItems.filter((item) => item.details
        && (item.status === "ready" || item.status === "export-failed"));
    if (!readyItems.length) return;
    state.batchExporting = true;
    state.batchExportTotal = readyItems.length;
    state.batchExportCompleted = 0;
    const replaceExisting = $("batch-replace-existing").checked;
    const outputDirectory = state.outputDirectory;
    $("batch-replace-existing").disabled = true;
    readyItems.forEach((item) => setBatchInputsDisabled(item, true));
    $("batch-export-button").disabled = true;
    syncActionAvailability();
    updateBatchSummary();
    for (const item of readyItems) {
        setBatchStatus(item, "exporting", "Creating FLAC");
        try {
            const metadata = {};
            BATCH_FIELDS.forEach(([key, , type]) => {
                const field = item.card.querySelector(`[data-batch-field="${key}"]`);
                if (!field) throw new Error(`The ${key} field is unavailable.`);
                const value = String(field.value ?? "").trim();
                metadata[key] = type === "number" ? (value ? Number.parseInt(value, 10) : null) : value;
            });
            if (!metadata.title || !metadata.artist) {
                throw new Error("Title and artist are required.");
            }
            const result = await request("/api/export", {
                searchId: item.searchId,
                videoId: item.selectedCandidate.videoId,
                metadata,
                replaceExisting,
                outputDirectory
            });
            item.outputPath = result.outputPath;
            item.card.querySelector('[data-role="output-path"]').textContent =
                `Created: ${result.outputPath}`;
            item.card.querySelector('[data-role="output"]').hidden = false;
            const player = item.card.querySelector('[data-role="player"]');
            player.querySelector('[data-role="player-label"]').textContent =
                `${metadata.title} — ${metadata.artist}`;
            const audio = player.querySelector('[data-role="audio"]');
            audio.src = audioPlaybackUrl(result.outputPath);
            player.hidden = false;
            setBatchStatus(item, "exported", "Created");
        } catch (error) {
            setBatchStatus(item, "export-failed", "Create failed", error.message);
        } finally {
            state.batchExportCompleted++;
            updateBatchSummary();
        }
    }
    state.batchExporting = false;
    $("batch-replace-existing").disabled = false;
    readyItems.forEach((item) => setBatchInputsDisabled(item, item.status === "exported"));
    updateBatchSummary();
    syncActionAvailability();
    const successes = readyItems.filter((item) => item.status === "exported").length;
    const failures = readyItems.length - successes;
    setActivity(failures
        ? `Batch complete · ${successes} created · ${failures} need attention`
        : `Batch complete · ${successes} FLAC ${successes === 1 ? "copy" : "copies"} created`,
    failures ? "error" : "success");
    updateWorkflowStep("create", false, failures === 0);
    showToast(failures
        ? `Batch complete: ${successes} created, ${failures} need attention.`
        : `Batch complete: ${successes} FLAC ${successes === 1 ? "copy" : "copies"} created.`);
}

function setBatchInputsDisabled(item, disabled) {
    item.card.querySelectorAll(".batch-select, [data-batch-field]").forEach((input) => {
        input.disabled = disabled;
    });
}

function renderCandidates(candidates, sourceDuration) {
    const list = $("candidate-list");
    list.replaceChildren();
    candidates.forEach((candidate) => {
        const difference = candidate.durationDifferenceSeconds;
        const isClose = difference !== null && difference <= 2;
        const card = document.createElement("article");
        card.className = `candidate-card${isClose ? " best-match" : ""}`;
        card.dataset.videoId = candidate.videoId;
        const thumb = candidate.thumbnailUrl
            ? `<img src="${escapeHtml(candidate.thumbnailUrl)}" alt="" loading="lazy">`
            : `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12.2a3.7 3.7 0 1 1-2-3.3V6l10-2v9.2a3.7 3.7 0 1 1-2-3.3V3.5L12 5z"/></svg>`;
        const timing = difference === null
            ? "Runtime unavailable"
            : `${difference.toFixed(1)}s ${difference <= 2 ? "from file" : "from file"}`;
        const badge = isClose
            ? `<span class="match-chip">Runtime match</span>`
            : difference !== null && difference > 10
                ? `<span class="match-chip caution">Check version</span>`
                : "";
        card.innerHTML = `
            <div class="candidate-thumb">${thumb}</div>
            <div class="candidate-copy">
                <div class="candidate-title" title="${escapeHtml(candidate.title)}">${escapeHtml(candidate.title)}</div>
                <div class="candidate-artist">${escapeHtml((candidate.artists || []).join(", "))}</div>
                <div class="candidate-album">${escapeHtml(candidate.album || "Album not listed")}</div>
                <div class="candidate-meta">
                    <span>${escapeHtml(candidate.duration || "—")}</span>
                    ${candidate.year ? `<span>·</span><span>${escapeHtml(String(candidate.year))}</span>` : ""}
                    <span>·</span><span>${escapeHtml(timing)}</span>${badge}
                </div>
            </div>
            <button type="button" class="select-candidate">Use this</button>`;
        card.querySelector("button").addEventListener("click", () => selectCandidate(candidate));
        list.append(card);
    });
}

function findUniqueExactRuntimeMatch(candidates, filename) {
    const stem = filename.replace(/\.[^.]+$/, "");
    const separator = stem.indexOf(" - ");
    const expectedTitle = separator >= 0 ? stem.slice(separator + 3).trim() : stem.trim();
    const normalizedTitle = normalizeTitle(expectedTitle);
    if (!normalizedTitle) return null;

    const matches = candidates.filter((candidate) =>
        normalizeTitle(candidate.title) === normalizedTitle
        && candidate.durationDifferenceSeconds !== null
        && candidate.durationDifferenceSeconds <= 2);
    return matches.length === 1 ? matches[0] : null;
}

function normalizeTitle(value) {
    return String(value ?? "").normalize("NFKD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "");
}

async function selectCandidate(candidate) {
    if (state.singleDetailsLoading || state.singleExporting) return;
    const requestId = ++state.selectionRequestId;
    const editVersionsAtRequestStart = new Map(state.metadataEditVersions);
    state.singleDetailsLoading = true;
    updateWorkflowStep("review", true);
    setActivity(`Loading metadata for ${candidate.title}…`, "busy");
    syncActionAvailability();
    clearError();
    setBusyButtonForCandidate(candidate.videoId, true);
    try {
        const details = await request("/api/details", {
            searchId: state.searchId,
            videoId: candidate.videoId
        });
        if (requestId !== state.selectionRequestId) return;
        state.selected = candidate;
        state.metadata = details;
        fillMetadata(details, editVersionsAtRequestStart);
        $("metadata-section").hidden = false;
        $("success-card").hidden = true;
        clearAudioPlayer($("success-audio"));
        $("success-player").hidden = true;
        updateWorkflowStep("review");
        setActivity("Review the suggested tags, then create your FLAC copy", "success");
        $("metadata-section").scrollIntoView({behavior: "smooth", block: "start"});
    } catch (error) {
        if (requestId === state.selectionRequestId) showError(error.message);
    } finally {
        if (requestId === state.selectionRequestId) {
            state.singleDetailsLoading = false;
            setBusyButtonForCandidate(candidate.videoId, false);
            syncActionAvailability();
        }
    }
}

function fillMetadata(metadata, editVersionsAtRequestStart) {
    const fields = {
        "tag-title": metadata.title,
        "tag-artist": metadata.artist,
        "tag-album": metadata.album,
        "tag-album-artist": metadata.albumArtist,
        "tag-composer": metadata.composer,
        "tag-writer": metadata.writer,
        "tag-publisher": metadata.publisher,
        "tag-genre": metadata.genre,
        "tag-date": metadata.date,
        "tag-track": metadata.trackNumber,
        "tag-track-total": metadata.trackTotal,
        "tag-disc": metadata.discNumber,
        "tag-disc-total": metadata.discTotal,
        "tag-comment": metadata.comment
    };
    Object.entries(fields).forEach(([id, value]) => {
        if ((state.metadataEditVersions.get(id) || 0)
                === (editVersionsAtRequestStart.get(id) || 0)) {
            $(id).value = value === null || value === undefined ? "" : value;
        }
    });

    $("source-link").href = metadata.sourceUrl || "#";
    $("artist-credit-hint").textContent = metadata.creditedArtists?.length
        ? `YouTube Music credits: ${metadata.creditedArtists.join(", ")}`
        : "";
    $("cover-image").hidden = !metadata.coverUrl;
    $("cover-placeholder").hidden = Boolean(metadata.coverUrl);
    $("cover-check").hidden = !metadata.coverUrl;
    $("cover-caption-text").textContent = metadata.coverUrl
        ? "Artwork will be embedded in the FLAC"
        : "No artwork was found for this release";
    $("cover-image").onerror = () => {
        $("cover-image").hidden = true;
        $("cover-placeholder").hidden = false;
        $("cover-check").hidden = true;
        $("cover-caption-text").textContent = "Artwork preview unavailable";
    };
    if (metadata.coverUrl) $("cover-image").src = metadata.coverUrl;

    const difference = state.selected?.durationDifferenceSeconds;
    const diffText = difference === null || difference === undefined
        ? "The source runtime could not be compared."
        : `The selected release differs from the file by ${difference.toFixed(1)} seconds.`;
    const warnings = [metadata.catalogMatch, ...(metadata.warnings || [])].filter(Boolean);
    $("match-note").innerHTML = `<strong>Version check</strong><br>${escapeHtml(diffText)}`
        + (warnings.length ? `<br>${warnings.map(escapeHtml).join("<br>")}` : "");
    const uniqueMatch = findUniqueExactRuntimeMatch(state.candidates, state.audio?.filename || "");
    $("match-confidence").textContent = uniqueMatch?.videoId === state.selected?.videoId
        ? "Strong · unique title and runtime"
        : difference !== null && difference !== undefined && difference <= 10
            ? "Review · close runtime"
            : "Manual review · check version";
    $("metadata-source").textContent = metadata.catalogMatch
        ? "Apple catalog + YouTube Music details"
        : "YouTube Music details + local tags where available";
}

async function exportFlac(event) {
    event.preventDefault();
    if (isOperationActive()) return;
    if (!state.selected || !state.searchId) {
        showError("Select a YouTube Music result before creating a FLAC.");
        return;
    }
    const metadata = {
        title: $("tag-title").value.trim(),
        artist: $("tag-artist").value.trim(),
        album: $("tag-album").value.trim(),
        albumArtist: $("tag-album-artist").value.trim(),
        composer: $("tag-composer").value.trim(),
        writer: $("tag-writer").value.trim(),
        publisher: $("tag-publisher").value.trim(),
        genre: $("tag-genre").value.trim(),
        date: $("tag-date").value.trim(),
        trackNumber: numberOrNull("tag-track"),
        trackTotal: numberOrNull("tag-track-total"),
        discNumber: numberOrNull("tag-disc"),
        discTotal: numberOrNull("tag-disc-total"),
        comment: $("tag-comment").value.trim()
    };
    if (!metadata.title || !metadata.artist) {
        showError("Title and artist are required.");
        return;
    }

    state.singleExporting = true;
    setBusy($("export-button"), true, "Creating FLAC…");
    const outputDirectory = state.outputDirectory;
    setActivity("Creating and verifying your FLAC copy…", "busy");
    updateWorkflowStep("create", true);
    syncActionAvailability();
    clearError();
    try {
        const result = await request("/api/export", {
            searchId: state.searchId,
            videoId: state.selected.videoId,
            metadata,
            replaceExisting: $("replace-existing").checked,
            outputDirectory
        });
        $("success-path").textContent = result.outputPath;
        $("success-card").hidden = false;
        $("success-audio-label").textContent = `${metadata.title} — ${metadata.artist}`;
        $("success-audio").src = audioPlaybackUrl(result.outputPath);
        $("success-player").hidden = false;
        setActivity("FLAC created and tags verified", "success");
        updateWorkflowStep("create", false, true);
        showToast("FLAC created. The source audio file was left untouched.");
        $("success-card").scrollIntoView({behavior: "smooth", block: "nearest"});
    } catch (error) {
        showError(error.message);
    } finally {
        state.singleExporting = false;
        setBusy($("export-button"), false);
        syncActionAvailability();
    }
}

async function request(url, body) {
    const options = body === undefined
        ? {method: "GET"}
        : {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)};
    const response = await fetch(url, options);
    let data;
    try {
        data = await response.json();
    } catch {
        throw new Error(`The local app returned an unreadable response (${response.status}).`);
    }
    if (!response.ok) throw new Error(data.message || `Request failed (${response.status}).`);
    return data;
}

function audioPlaybackUrl(outputPath) {
    const separator = Math.max(outputPath.lastIndexOf("/"), outputPath.lastIndexOf("\\"));
    if (separator <= 0 || separator === outputPath.length - 1) {
        throw new Error("The created FLAC path is invalid for playback.");
    }
    const directory = outputPath.slice(0, separator);
    const name = outputPath.slice(separator + 1);
    return `/api/output/play?directory=${encodeURIComponent(directory)}&name=${encodeURIComponent(name)}`;
}

function clearAudioPlayer(audio) {
    if (!audio) return;
    audio.pause();
    audio.removeAttribute("src");
    audio.load();
}

function stopBatchPlayer(item) {
    const audio = item.card?.querySelector('[data-role="audio"]');
    if (audio) clearAudioPlayer(audio);
    const player = item.card?.querySelector('[data-role="player"]');
    if (player) player.hidden = true;
}

function numberOrNull(id) {
    const value = $(id).value.trim();
    if (!value) return null;
    const parsed = Number.parseInt(value, 10);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function setBusy(button, busy, label) {
    if (!button.dataset.originalHtml) button.dataset.originalHtml = button.innerHTML;
    button.disabled = busy;
    button.setAttribute("aria-busy", String(busy));
    if (busy) {
        button.innerHTML = `<span class="spin"></span><span>${escapeHtml(label)}</span>`;
    } else {
        button.innerHTML = button.dataset.originalHtml;
    }
}

function setBusyButtonForCandidate(videoId, busy) {
    const card = [...$("candidate-list").children]
        .find((item) => item.dataset.videoId === videoId);
    const button = card?.querySelector("button");
    if (!button) return;
    button.disabled = busy;
    button.textContent = busy ? "Loading…" : "Use this";
}

function showError(message) {
    $("error-banner").textContent = message;
    $("error-banner").hidden = false;
    setActivity(message, "error");
    updateWorkflowStep(state.mode === "batch" ? "review"
        : state.selected ? "create" : state.candidates.length ? "review" : "choose");
    showToast(message);
}

function clearError() {
    $("error-banner").hidden = true;
    $("error-banner").textContent = "";
}

function showToast(message) {
    const toast = $("toast");
    toast.textContent = message;
    toast.classList.add("visible");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => toast.classList.remove("visible"), 4000);
}

async function copyOutputPath() {
    const path = $("success-path").textContent;
    try {
        await navigator.clipboard.writeText(path);
        showToast("Output path copied.");
    } catch {
        showToast(path);
    }
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (character) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[character]);
}
