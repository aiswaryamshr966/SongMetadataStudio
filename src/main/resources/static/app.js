const state = {
    searchId: null,
    audio: null,
    candidates: [],
    selected: null,
    metadata: null,
    toastTimer: null
};

const $ = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", () => {
    $("search-button").addEventListener("click", search);
    $("song-path").addEventListener("keydown", (event) => {
        if (event.key === "Enter") search();
    });
    $("metadata-form").addEventListener("submit", exportFlac);
    $("copy-path-button").addEventListener("click", copyOutputPath);
    checkHealth();
});

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

async function search() {
    const path = $("song-path").value.trim();
    if (!path) {
        showError("Enter the full path to a song file.");
        $("song-path").focus();
        return;
    }

    setBusy($("search-button"), true, "Searching YouTube Music…");
    clearError();
    $("candidate-section").hidden = true;
    $("metadata-section").hidden = true;
    $("success-card").hidden = true;
    state.selected = null;
    try {
        const result = await request("/api/search", {path});
        state.searchId = result.searchId;
        state.audio = result.audio;
        state.candidates = result.candidates;
        $("search-summary").textContent =
            `${result.audio.filename} · ${result.audio.durationLabel} · Search: “${result.audio.searchQuery}”`;
        $("result-count").textContent = `${result.candidates.length} releases`;
        renderCandidates(result.candidates, result.audio.durationSeconds);
        $("candidate-section").hidden = false;
        const automaticMatch = findUniqueExactRuntimeMatch(result.candidates, result.audio.filename);
        if (automaticMatch) {
            showToast("A unique title and duration match was found. Loading its tags…");
            await selectCandidate(automaticMatch);
        } else {
            $("candidate-section").scrollIntoView({behavior: "smooth", block: "start"});
        }
    } catch (error) {
        showError(error.message);
    } finally {
        setBusy($("search-button"), false);
    }
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
    clearError();
    setBusyButtonForCandidate(candidate.videoId, true);
    try {
        const details = await request("/api/details", {
            searchId: state.searchId,
            videoId: candidate.videoId
        });
        state.selected = candidate;
        state.metadata = details;
        fillMetadata(details);
        $("metadata-section").hidden = false;
        $("success-card").hidden = true;
        $("metadata-section").scrollIntoView({behavior: "smooth", block: "start"});
    } catch (error) {
        showError(error.message);
    } finally {
        setBusyButtonForCandidate(candidate.videoId, false);
    }
}

function fillMetadata(metadata) {
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
        "tag-comment": ""
    };
    Object.entries(fields).forEach(([id, value]) => {
        $(id).value = value === null || value === undefined ? "" : value;
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
    const warnings = metadata.warnings || [];
    $("match-note").innerHTML = `<strong>Version check</strong><br>${escapeHtml(diffText)}`
        + (warnings.length ? `<br>${warnings.map(escapeHtml).join("<br>")}` : "");
}

async function exportFlac(event) {
    event.preventDefault();
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

    setBusy($("export-button"), true, "Creating FLAC…");
    clearError();
    try {
        const result = await request("/api/export", {
            searchId: state.searchId,
            videoId: state.selected.videoId,
            metadata,
            replaceExisting: $("replace-existing").checked
        });
        $("success-path").textContent = result.outputPath;
        $("success-card").hidden = false;
        showToast("FLAC created. The source audio file was left untouched.");
        $("success-card").scrollIntoView({behavior: "smooth", block: "nearest"});
    } catch (error) {
        showError(error.message);
    } finally {
        setBusy($("export-button"), false);
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

function numberOrNull(id) {
    const value = $(id).value.trim();
    if (!value) return null;
    const parsed = Number.parseInt(value, 10);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function setBusy(button, busy, label) {
    if (!button.dataset.originalHtml) button.dataset.originalHtml = button.innerHTML;
    button.disabled = busy;
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
