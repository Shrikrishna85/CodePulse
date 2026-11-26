const state = {
    csrf: null,
    user: null,
    problem: null,
    contestId: null,
    pollTimer: null,
    authMode: "login"
};

const byId = (id) => document.getElementById(id);

async function csrfToken() {
    if (!state.csrf) {
        const response = await fetch("/api/auth/csrf");
        const body = await response.json();
        if (!response.ok) throw new Error(body.error || "Could not load CSRF token");
        state.csrf = body.token;
    }
    return state.csrf;
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers || {});
    if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    if (options.method && options.method !== "GET") headers.set("X-XSRF-TOKEN", await csrfToken());
    const response = await fetch(path, { ...options, headers, credentials: "same-origin" });
    const text = await response.text();
    let body = null;
    if (text) {
        try {
            body = JSON.parse(text);
        } catch {
            body = { error: text };
        }
    }
    if (!response.ok) throw new Error(body?.error || `Request failed (${response.status})`);
    return body;
}

function showMessage(element, text, isError = false) {
    element.textContent = text || "";
    element.classList.toggle("error", isError);
}

function setAuthMode(mode) {
    state.authMode = mode;
    const registering = mode === "register";
    byId("authHeading").textContent = registering ? "Create your account" : "Welcome back";
    byId("authSubmit").textContent = registering ? "Create account" : "Sign in";
    byId("authModeButton").textContent = registering ? "Back to sign in" : "Create a student account";
    byId("emailField").hidden = !registering;
    byId("emailField").querySelector("input").required = registering;
    byId("authForm").elements.password.autocomplete = registering ? "new-password" : "current-password";
    showMessage(byId("authMessage"), "");
}

function showApplication(user) {
    state.user = user;
    byId("authView").hidden = true;
    byId("appView").hidden = false;
    byId("accountBar").hidden = false;
    byId("accountName").textContent = `${user.username} · ${user.role}`;
    byId("adminNav").hidden = user.role !== "ADMIN";
    loadProblems();
}

function showSignIn() {
    state.user = null;
    byId("authView").hidden = false;
    byId("appView").hidden = true;
    byId("accountBar").hidden = true;
}

async function loadProblems() {
    try {
        const problems = await api("/api/problems");
        const list = byId("problemList");
        list.replaceChildren();
        if (problems.length === 0) {
            showMessage(byId("problemsMessage"), "No published problems yet.");
            return;
        }
        showMessage(byId("problemsMessage"), "");
        for (const problem of problems) {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "problem-card";
            const details = document.createElement("div");
            const title = document.createElement("h2");
            title.textContent = problem.title;
            const summary = document.createElement("p");
            summary.textContent = `${problem.tags.join(" · ")}  |  ${problem.timeLimitMs} ms  |  ${problem.memoryLimitMb} MB`;
            details.append(title, summary);
            const difficulty = document.createElement("span");
            difficulty.className = "difficulty";
            difficulty.textContent = problem.difficulty;
            card.append(details, difficulty);
            card.addEventListener("click", () => {
                state.contestId = null;
                openProblem(problem.slug);
            });
            list.append(card);
        }
    } catch (error) {
        showMessage(byId("problemsMessage"), error.message, true);
    }
}

async function openProblem(slug, contestId = state.contestId) {
    try {
        state.contestId = contestId;
        state.problem = await api(`/api/problems/${encodeURIComponent(slug)}`);
        showView("solverView");
        byId("problemTitle").textContent = state.problem.title;
        byId("problemMeta").textContent =
            `${state.problem.difficulty} · ${state.problem.timeLimitMs} ms · ${state.problem.memoryLimitMb} MB`;
        byId("problemStatement").textContent = state.problem.statement;
        byId("problemTags").replaceChildren(...state.problem.tags.map((tagName) => {
            const tag = document.createElement("span");
            tag.className = "tag";
            tag.textContent = tagName;
            return tag;
        }));
        renderSamples(state.problem.samples);
        byId("submissionPanel").hidden = true;
        byId("sourceCode").value = "";
        updateLineNumbers();
        updateSourceSize();
    } catch (error) {
        showMessage(byId("problemsMessage"), error.message, true);
        showView("problemsView");
    }
}

function renderSamples(samples) {
    const container = byId("samples");
    container.replaceChildren();
    for (const sample of samples) {
        const card = document.createElement("div");
        card.className = "sample";
        const title = document.createElement("h3");
        title.textContent = `Sample ${sample.ordinal}`;
        const inputLabel = document.createElement("span");
        inputLabel.className = "sample-label";
        inputLabel.textContent = "INPUT";
        const input = document.createElement("pre");
        input.textContent = sample.stdin || "(empty)";
        const outputLabel = document.createElement("span");
        outputLabel.className = "sample-label";
        outputLabel.textContent = "EXPECTED OUTPUT";
        const output = document.createElement("pre");
        output.textContent = sample.expectedOutput;
        card.append(title, inputLabel, input, outputLabel, output);
        container.append(card);
    }
}

function updateLineNumbers() {
    const code = byId("sourceCode").value;
    const count = Math.max(1, code.split("\n").length);
    byId("lineNumbers").textContent = Array.from({ length: count }, (_, index) => index + 1).join("\n");
    byId("lineNumbers").scrollTop = byId("sourceCode").scrollTop;
}

function updateSourceSize() {
    const bytes = new TextEncoder().encode(byId("sourceCode").value).length;
    byId("sourceSize").textContent = `${bytes} / 65536 bytes`;
    byId("sourceSize").classList.toggle("error", bytes > 65536);
    byId("submitButton").disabled = bytes > 65536 || !state.problem;
}

async function submitSolution() {
    const code = byId("sourceCode").value;
    if (!code.trim()) {
        showMessage(byId("submissionMessage"), "Add source code before submitting.", true);
        byId("submissionPanel").hidden = false;
        return;
    }
    byId("submitButton").disabled = true;
    byId("submissionPanel").hidden = false;
    showMessage(byId("submissionMessage"), "Submitting…");
    try {
        const result = await api("/api/submissions", {
            method: "POST",
            body: JSON.stringify({
                problemId: state.problem.id,
                language: byId("languageSelect").value,
                sourceCode: code,
                contestId: state.contestId
            })
        });
        byId("submissionVerdict").textContent = result.verdict;
        byId("submissionError").hidden = true;
        byId("testResults").replaceChildren();
        showMessage(byId("submissionMessage"), `Submission #${result.id} is queued.`);
        pollSubmission(result.id);
    } catch (error) {
        showMessage(byId("submissionMessage"), error.message, true);
    } finally {
        updateSourceSize();
    }
}

function pollSubmission(id) {
    if (state.pollTimer) clearInterval(state.pollTimer);
    const check = async () => {
        try {
            const submission = await api(`/api/submissions/${id}`);
            renderSubmission(submission);
            if (!["QUEUED", "RUNNING"].includes(submission.verdict)) {
                clearInterval(state.pollTimer);
                state.pollTimer = null;
            }
        } catch (error) {
            clearInterval(state.pollTimer);
            state.pollTimer = null;
            showMessage(byId("submissionMessage"), error.message, true);
        }
    };
    check();
    state.pollTimer = setInterval(check, 1800);
}

function renderSubmission(submission) {
    byId("submissionVerdict").textContent = submission.verdict;
    showMessage(byId("submissionMessage"),
        submission.completedAt ? `Completed in ${submission.executionTimeMs ?? 0} ms.` : "Waiting for a worker…");
    const errorPanel = byId("submissionError");
    errorPanel.hidden = !submission.errorMessage;
    errorPanel.textContent = submission.errorMessage || "";
    const results = byId("testResults");
    results.replaceChildren();
    for (const test of submission.testResults) {
        const row = document.createElement("div");
        row.className = "test-result";
        const name = document.createElement("span");
        name.textContent = test.sample ? `Sample ${test.ordinal}` : `Hidden test ${test.ordinal}`;
        const verdict = document.createElement("span");
        verdict.className = "verdict";
        verdict.textContent = test.verdict;
        row.append(name, verdict);
        results.append(row);
    }
}

async function loadSubmissions() {
    try {
        const submissions = await api("/api/submissions/mine");
        const list = byId("submissionList");
        list.replaceChildren();
        byId("selectedSubmissionDetail").hidden = true;
        showMessage(byId("submissionsMessage"), submissions.length ? "" : "No submissions yet.");
        for (const submission of submissions) {
            const row = document.createElement("button");
            row.type = "button";
            row.className = "submission-card";
            const title = document.createElement("span");
            title.textContent = `#${submission.id} · ${submission.problemTitle} · ${submission.language}`;
            const verdict = document.createElement("span");
            verdict.className = "verdict";
            verdict.textContent = submission.verdict;
            row.append(title, verdict);
            row.addEventListener("click", async () => {
                try {
                    const detail = await api(`/api/submissions/${submission.id}`);
                    renderSubmissionHistoryDetail(detail);
                } catch (error) {
                    showMessage(byId("submissionsMessage"), error.message, true);
                }
            });
            list.append(row);
        }
    } catch (error) {
        showMessage(byId("submissionsMessage"), error.message, true);
    }
}

function renderSubmissionHistoryDetail(submission) {
    const detail = byId("selectedSubmissionDetail");
    detail.hidden = false;
    byId("selectedSubmissionTitle").textContent = `Submission #${submission.id} · ${submission.problemTitle}`;
    byId("selectedSubmissionVerdict").textContent = submission.verdict;
    byId("selectedSubmissionMeta").textContent =
        `${submission.language} · ${submission.completedAt ? `completed in ${submission.executionTimeMs ?? 0} ms` : "still processing"}`;
    const error = byId("selectedSubmissionError");
    error.hidden = !submission.errorMessage;
    error.textContent = submission.errorMessage || "";
    byId("selectedSubmissionCode").textContent = submission.sourceCode;
    const tests = byId("selectedSubmissionTests");
    tests.replaceChildren();
    for (const test of submission.testResults) {
        const row = document.createElement("div");
        row.className = "test-result";
        const title = document.createElement("span");
        title.textContent = test.sample ? `Sample ${test.ordinal}` : `Hidden test ${test.ordinal}`;
        const verdict = document.createElement("span");
        verdict.className = "verdict";
        verdict.textContent = test.verdict;
        row.append(title, verdict);
        tests.append(row);
    }
}

async function loadContests() {
    try {
        const contests = await api("/api/contests");
        const list = byId("contestList");
        list.replaceChildren();
        list.hidden = false;
        byId("contestDetail").hidden = true;
        showMessage(byId("contestMessage"), contests.length ? "" : "No contests are scheduled.");
        for (const contest of contests) {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "problem-card";
            const title = document.createElement("h2");
            title.textContent = contest.title;
            const dates = document.createElement("p");
            dates.textContent = `${new Date(contest.startsAt).toLocaleString()} – ${new Date(contest.endsAt).toLocaleString()}`;
            const description = document.createElement("div");
            description.append(title, dates);
            const open = document.createElement("span");
            open.className = "difficulty";
            open.textContent = "OPEN";
            card.append(description, open);
            card.addEventListener("click", () => openContest(contest));
            list.append(card);
        }
    } catch (error) {
        showMessage(byId("contestMessage"), error.message, true);
    }
}

async function openContest(contest) {
    state.contestId = contest.id;
    byId("contestList").hidden = true;
    byId("contestDetail").hidden = false;
    byId("contestTitle").textContent = contest.title;
    try {
        const [problems, scoreboard] = await Promise.all([
            api(`/api/contests/${contest.id}/problems`),
            api(`/api/contests/${contest.id}/scoreboard`)
        ]);
        const problemList = byId("contestProblems");
        problemList.replaceChildren();
        for (const problem of problems) {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "problem-card";
            const title = document.createElement("h2");
            title.textContent = `${String.fromCharCode(64 + problem.ordinal)}. ${problem.title}`;
            const difficulty = document.createElement("span");
            difficulty.className = "difficulty";
            difficulty.textContent = problem.difficulty;
            card.append(title, difficulty);
            card.addEventListener("click", () => openProblem(problem.slug, contest.id));
            problemList.append(card);
        }
        const rows = byId("scoreboardRows");
        rows.replaceChildren();
        for (const entry of scoreboard) {
            const row = document.createElement("tr");
            for (const value of [entry.rank, entry.username, entry.solvedCount, entry.penaltyMinutes]) {
                const cell = document.createElement("td");
                cell.textContent = String(value);
                row.append(cell);
            }
            rows.append(row);
        }
        showMessage(byId("contestMessage"), "");
    } catch (error) {
        showMessage(byId("contestMessage"), error.message, true);
    }
}

async function registerForContest() {
    if (!state.contestId) return;
    try {
        await api(`/api/contests/${state.contestId}/register`, { method: "POST" });
        showMessage(byId("contestMessage"), "You are registered for this contest.");
    } catch (error) {
        showMessage(byId("contestMessage"), error.message, true);
    }
}

async function loadAnalytics() {
    try {
        const overview = await api("/api/analytics/overview");
        const stats = overview.personalStats;
        byId("personalStats").replaceChildren(
            statCard("Solved problems", stats.solvedProblems),
            statCard("Accepted submissions", stats.accepted),
            statCard("Total submissions", stats.submissions)
        );
        drawBarChart(byId("verdictChart"),
            overview.verdicts.map((item) => item.verdict),
            overview.verdicts.map((item) => item.count));
        drawBarChart(byId("dailyChart"),
            overview.submissionsPerDay.map((item) => item.date.slice(5)),
            overview.submissionsPerDay.map((item) => item.count));
        drawBarChart(byId("acceptanceChart"),
            overview.problemAcceptance.map((item) => item.title),
            overview.problemAcceptance.map((item) => item.ratePercent),
            true);
        showMessage(byId("analyticsMessage"), "");
    } catch (error) {
        showMessage(byId("analyticsMessage"), error.message, true);
    }
}

function statCard(label, value) {
    const card = document.createElement("div");
    card.className = "stat-card";
    const name = document.createElement("span");
    name.className = "muted";
    name.textContent = label;
    const count = document.createElement("strong");
    count.textContent = String(value);
    card.append(name, count);
    return card;
}

function drawBarChart(canvas, labels, values, horizontal = false) {
    const context = canvas.getContext("2d");
    const ratio = window.devicePixelRatio || 1;
    const width = Math.max(280, canvas.clientWidth);
    const height = horizontal ? Math.max(250, labels.length * 38 + 50) : 240;
    canvas.style.height = `${height}px`;
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    context.scale(ratio, ratio);
    context.clearRect(0, 0, width, height);
    const maximum = Math.max(1, ...values);
    context.font = "11px Segoe UI, sans-serif";
    context.fillStyle = "#9aa9bf";

    if (horizontal) {
        const labelWidth = Math.min(150, width * 0.38);
        const chartWidth = width - labelWidth - 45;
        const rowHeight = 32;
        labels.forEach((label, index) => {
            const y = 24 + index * rowHeight;
            const shownLabel = label.length > 22 ? `${label.slice(0, 20)}…` : label;
            context.fillText(shownLabel, 0, y + 13);
            context.fillStyle = "#26354a";
            context.fillRect(labelWidth, y, chartWidth, 14);
            context.fillStyle = "#6de0bd";
            const barWidth = chartWidth * Math.max(0, Math.min(100, values[index])) / 100;
            context.fillRect(labelWidth, y, barWidth, 14);
            context.fillStyle = "#c8d4e4";
            context.fillText(`${Number(values[index]).toFixed(0)}%`, labelWidth + chartWidth + 7, y + 12);
            context.fillStyle = "#9aa9bf";
        });
        return;
    }

    const chartTop = 18;
    const chartHeight = height - 58;
    const slot = width / Math.max(1, values.length);
    values.forEach((value, index) => {
        const barHeight = chartHeight * value / maximum;
        const x = slot * index + slot * 0.2;
        context.fillStyle = "#6de0bd";
        context.fillRect(x, chartTop + chartHeight - barHeight, slot * 0.6, barHeight);
        context.fillStyle = "#c8d4e4";
        context.textAlign = "center";
        context.fillText(String(value), x + slot * 0.3, chartTop + chartHeight - barHeight - 5);
        const label = labels[index].length > 12 ? `${labels[index].slice(0, 10)}…` : labels[index];
        context.fillStyle = "#9aa9bf";
        context.fillText(label, x + slot * 0.3, height - 18);
    });
    context.textAlign = "start";
}

function showView(viewId) {
    document.querySelectorAll(".view").forEach((view) => {
        view.hidden = view.id !== viewId;
    });
    document.querySelectorAll(".nav-button").forEach((button) => {
        button.classList.toggle("active", button.dataset.view === viewId);
    });
}

async function createProblem(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    const request = {
        slug: values.get("slug"),
        title: values.get("title"),
        statement: values.get("statement"),
        difficulty: values.get("difficulty"),
        timeLimitMs: Number(values.get("timeLimitMs")),
        memoryLimitMb: Number(values.get("memoryLimitMb")),
        tags: String(values.get("tags") || "").split(",").map((tag) => tag.trim()).filter(Boolean)
    };
    try {
        const created = await api("/api/admin/problems", { method: "POST", body: JSON.stringify(request) });
        byId("testCaseForm").elements.problemId.value = created.id;
        showMessage(byId("adminMessage"), `Created problem #${created.id}. Add at least one sample and one hidden case before publishing.`);
        form.reset();
        loadProblems();
    } catch (error) {
        showMessage(byId("adminMessage"), error.message, true);
    }
}

async function addTestCase(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    const problemId = Number(values.get("problemId"));
    try {
        const result = await api(`/api/admin/problems/${problemId}/test-cases`, {
            method: "POST",
            body: JSON.stringify({
                stdin: values.get("stdin"),
                expectedOutput: values.get("expectedOutput"),
                sample: values.has("sample")
            })
        });
        showMessage(byId("adminMessage"), `Added test case ${result.ordinal} to problem #${problemId}.`);
        form.elements.stdin.value = "";
        form.elements.expectedOutput.value = "";
        form.elements.sample.checked = false;
    } catch (error) {
        showMessage(byId("adminMessage"), error.message, true);
    }
}

async function publishProblem() {
    const problemId = Number(byId("testCaseForm").elements.problemId.value);
    if (!problemId) {
        showMessage(byId("adminMessage"), "Enter a problem ID first.", true);
        return;
    }
    try {
        await api(`/api/admin/problems/${problemId}/published`, {
            method: "PUT",
            body: JSON.stringify({ published: true })
        });
        showMessage(byId("adminMessage"), `Problem #${problemId} is published.`);
        loadProblems();
    } catch (error) {
        showMessage(byId("adminMessage"), error.message, true);
    }
}

async function loadSimilarityPairs() {
    try {
        const pairs = await api("/api/admin/similarity/review");
        const container = byId("similarityPairs");
        container.replaceChildren();
        if (pairs.length === 0) {
            showMessage(byId("adminMessage"), "No high-overlap pairs are pending review.");
            return;
        }
        for (const pair of pairs) {
            const row = document.createElement("div");
            row.className = "submission-card";
            const details = document.createElement("span");
            details.textContent =
                `Problem #${pair.problemId}: ${pair.usernameA} (#${pair.submissionAId}) ↔ ${pair.usernameB} (#${pair.submissionBId}) · ${(pair.score * 100).toFixed(1)}% · ${pair.reviewStatus}`;
            row.append(details);
            if (pair.reviewStatus === "PENDING") {
                const review = document.createElement("button");
                review.type = "button";
                review.className = "button button-quiet";
                review.textContent = "Mark reviewed";
                review.addEventListener("click", async () => {
                    try {
                        await api(`/api/admin/similarity/${pair.id}/review`, { method: "POST" });
                        loadSimilarityPairs();
                    } catch (error) {
                        showMessage(byId("adminMessage"), error.message, true);
                    }
                });
                row.append(review);
            }
            container.append(row);
        }
        showMessage(byId("adminMessage"), "");
    } catch (error) {
        showMessage(byId("adminMessage"), error.message, true);
    }
}

byId("authModeButton").addEventListener("click", () => {
    setAuthMode(state.authMode === "login" ? "register" : "login");
});
byId("authForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const registering = state.authMode === "register";
    const request = {
        username: values.get("username"),
        password: values.get("password")
    };
    if (registering) request.email = values.get("email");
    try {
        await api(`/api/auth/${registering ? "register" : "login"}`, {
            method: "POST",
            body: JSON.stringify(request)
        });
        const user = registering
            ? await api("/api/auth/login", {
                method: "POST",
                body: JSON.stringify({ username: request.username, password: request.password })
            })
            : await api("/api/auth/me");
        showApplication(user);
    } catch (error) {
        showMessage(byId("authMessage"), error.message, true);
    }
});
byId("logoutButton").addEventListener("click", async () => {
    try {
        await api("/api/auth/logout", { method: "POST" });
        state.csrf = null;
        showSignIn();
    } catch (error) {
        showMessage(byId("authMessage"), error.message, true);
    }
});
document.querySelectorAll(".nav-button").forEach((button) => {
    button.addEventListener("click", () => {
        showView(button.dataset.view);
        if (button.dataset.view === "problemsView") loadProblems();
        if (button.dataset.view === "submissionsView") loadSubmissions();
        if (button.dataset.view === "contestsView") loadContests();
        if (button.dataset.view === "analyticsView") loadAnalytics();
    });
});
byId("refreshProblems").addEventListener("click", loadProblems);
byId("refreshSubmissions").addEventListener("click", loadSubmissions);
byId("backToProblems").addEventListener("click", () => {
    state.contestId = null;
    showView("problemsView");
});
byId("sourceCode").addEventListener("input", () => {
    updateLineNumbers();
    updateSourceSize();
});
byId("sourceCode").addEventListener("scroll", updateLineNumbers);
byId("sourceCode").addEventListener("keydown", (event) => {
    if (event.key === "Tab") {
        event.preventDefault();
        const editor = event.currentTarget;
        const start = editor.selectionStart;
        const end = editor.selectionEnd;
        editor.setRangeText("\t", start, end, "end");
        editor.dispatchEvent(new Event("input"));
    }
});
byId("submitButton").addEventListener("click", submitSolution);
byId("createProblemForm").addEventListener("submit", createProblem);
byId("testCaseForm").addEventListener("submit", addTestCase);
byId("publishButton").addEventListener("click", publishProblem);
byId("refreshContests").addEventListener("click", loadContests);
byId("registerContest").addEventListener("click", registerForContest);
byId("refreshSimilarity").addEventListener("click", loadSimilarityPairs);

(async function initialize() {
    try {
        await csrfToken();
        const user = await api("/api/auth/me");
        showApplication(user);
    } catch {
        showSignIn();
    }
})();
