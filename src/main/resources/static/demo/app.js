(function () {
    "use strict";

    const API_BASE = "/api/v1/demo";
    // 외부 AI 호출은 서버 타임아웃(Bedrock 기본 30초)보다 화면이 먼저 포기하지 않도록 여유를 둔다.
    const SUMMARY_TIMEOUT_MS = 45000;
    const state = { activities: [], activityById: new Map(), requestSequence: 0 };

    const elements = {
        globalStatus: document.querySelector("#global-status"),
        reloadButton: document.querySelector("#reload-button"),
        memberSelect: document.querySelector("#member-select"),
        projectSelect: document.querySelector("#project-select"),
        memberButton: document.querySelector("#member-summary-button"),
        projectButton: document.querySelector("#project-summary-button"),
        handoffButton: document.querySelector("#handoff-summary-button"),
        summaryRegion: document.querySelector("#summary-region"),
        summaryEmpty: document.querySelector("#summary-empty"),
        summaryLoading: document.querySelector("#summary-loading"),
        summaryError: document.querySelector("#summary-error"),
        summaryErrorMessage: document.querySelector("#summary-error-message"),
        summaryContent: document.querySelector("#summary-content"),
        summaryMode: document.querySelector("#summary-mode"),
        summaryTitle: document.querySelector("#summary-content-title"),
        summaryGenerated: document.querySelector("#summary-generated"),
        summarySections: document.querySelector("#summary-sections"),
        activityList: document.querySelector("#activity-list"),
        activityCount: document.querySelector("#activity-count"),
        activityError: document.querySelector("#activity-error"),
        activityErrorMessage: document.querySelector("#activity-error-message")
    };

    const sectionDefinitions = [
        { key: "completed", title: "완료한 일", empty: "확인된 완료 항목이 없습니다." },
        { key: "inProgress", title: "진행 중", empty: "확인된 진행 항목이 없습니다." },
        { key: "blockers", title: "막힌 점", empty: "활동 기록에서 확인된 막힌 점이 없습니다.", className: "is-blocker" },
        { key: "nextActions", title: "다음 행동", empty: "근거로 제안할 다음 행동이 없습니다." }
    ];

    function createElement(tag, className, text) {
        const element = document.createElement(tag);
        if (className) element.className = className;
        if (text !== undefined) element.textContent = text;
        return element;
    }

    function safeSourceUrl(value) {
        try {
            const url = new URL(value, window.location.origin);
            return url.protocol === "http:" || url.protocol === "https:" ? url.href : null;
        } catch (_) {
            return null;
        }
    }

    function formatDate(value, includeTime) {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return "시각 확인 불가";
        return new Intl.DateTimeFormat("ko-KR", {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: includeTime ? "2-digit" : undefined,
            minute: includeTime ? "2-digit" : undefined
        }).format(date);
    }

    function activityKind(kind) {
        const labels = {
            JIRA_ISSUE: { short: "Jira", label: "Jira 이슈", github: false },
            GITHUB_PULL_REQUEST: { short: "PR", label: "GitHub PR", github: true },
            GITHUB_COMMIT: { short: "Git", label: "GitHub 커밋", github: true }
        };
        return labels[kind] || { short: "Log", label: kind || "활동", github: false };
    }

    function setControlsDisabled(disabled) {
        const hasActivities = state.activities.length > 0;
        elements.memberSelect.disabled = disabled || !hasActivities;
        elements.projectSelect.disabled = disabled || !hasActivities;
        elements.memberButton.disabled = disabled || !elements.memberSelect.value;
        elements.projectButton.disabled = disabled || !elements.projectSelect.value;
        elements.handoffButton.disabled = disabled || !elements.projectSelect.value;
        elements.reloadButton.disabled = disabled;
    }

    function populateSelect(select, values, placeholder) {
        select.replaceChildren();
        if (!values.length) {
            const option = new Option(placeholder, "");
            select.append(option);
            return;
        }
        for (const value of values) select.append(new Option(value.label, value.id));
    }

    function getMembers(activities) {
        const members = new Map();
        for (const activity of activities) {
            if (activity.memberId && !members.has(activity.memberId)) {
                members.set(activity.memberId, activity.memberName || activity.memberId);
            }
        }
        return Array.from(members, ([id, label]) => ({ id, label }))
            .sort((a, b) => a.label.localeCompare(b.label, "ko"));
    }

    function getProjects(activities) {
        return Array.from(new Set(activities.map((item) => item.projectId).filter(Boolean)))
            .sort((a, b) => a.localeCompare(b, "ko"))
            .map((id) => ({ id, label: id }));
    }

    async function requestJson(path, timeoutMs = 15000) {
        const controller = new AbortController();
        const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);
        try {
            const response = await fetch(path, {
                method: "GET",
                headers: { Accept: "application/json" },
                credentials: "same-origin",
                signal: controller.signal
            });
            if (!response.ok) {
                const detail = response.status === 404
                    ? "선택한 데모 자료를 찾을 수 없습니다."
                    : response.status === 503
                        ? "AI 요약을 만들지 못했습니다. 잠시 후 다시 시도해 주세요."
                        : "요청이 실패했습니다 (HTTP " + response.status + ").";
                throw new Error(detail);
            }
            return await response.json();
        } catch (error) {
            if (error.name === "AbortError") throw new Error("요청 시간이 초과되었습니다. 서버 상태를 확인해 주세요.");
            throw error;
        } finally {
            window.clearTimeout(timeoutId);
        }
    }

    function renderActivities() {
        elements.activityList.replaceChildren();
        elements.activityCount.textContent = state.activities.length + "개 활동 · 최신순";

        for (const activity of state.activities) {
            const kind = activityKind(activity.kind);
            const card = createElement("article", "activity-card");
            card.id = "activity-" + String(activity.id).replace(/[^a-zA-Z0-9_-]/g, "-");

            const icon = createElement("span", "source-icon" + (kind.github ? " is-github" : ""), kind.short);
            icon.setAttribute("aria-hidden", "true");

            const body = createElement("div", "activity-body");
            const kicker = createElement("div", "activity-kicker");
            kicker.append(
                createElement("span", null, kind.label),
                createElement("span", null, activity.memberName || "가명 팀원"),
                createElement("span", null, activity.status || "상태 없음"),
                createElement("time", null, formatDate(activity.occurredAt, true))
            );
            const title = createElement("h3", null, activity.title || "제목 없음");
            body.append(kicker, title);
            if (activity.details) body.append(createElement("p", null, activity.details));

            const url = safeSourceUrl(activity.sourceUrl);
            const link = createElement(url ? "a" : "span", "source-link", url ? "원본 근거 ↗" : "원본 링크 없음");
            if (url) {
                link.href = url;
                link.target = "_blank";
                link.rel = "noopener noreferrer";
                link.setAttribute("aria-label", activity.title + " 원본 근거를 새 창에서 열기");
            }
            card.append(icon, body, link);
            elements.activityList.append(card);
        }
    }

    function showSummaryState(name) {
        elements.summaryEmpty.hidden = name !== "empty";
        elements.summaryLoading.hidden = name !== "loading";
        elements.summaryError.hidden = name !== "error";
        elements.summaryContent.hidden = name !== "content";
    }

    function evidenceNode(evidenceId) {
        const activity = state.activityById.get(evidenceId);
        if (!activity) return createElement("span", "missing-evidence", "근거 " + evidenceId);
        const url = safeSourceUrl(activity.sourceUrl);
        if (!url) return createElement("span", "missing-evidence", activityKind(activity.kind).label + " · " + activity.id);
        const link = createElement("a", "evidence-link", activityKind(activity.kind).label + " · " + activity.id + " ↗");
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.setAttribute("aria-label", activity.title + " 근거를 새 창에서 열기");
        return link;
    }

    function renderSummary(summary) {
        const modeLabels = { MEMBER: "팀원 요약", PROJECT: "프로젝트 요약", HANDOFF: "인수인계 요약" };
        elements.summaryMode.textContent = modeLabels[summary.mode] || summary.mode || "업무 요약";
        elements.summaryTitle.textContent = summary.title || "업무 요약";
        elements.summaryGenerated.textContent = "생성: " + formatDate(summary.generatedAt, true) + " · " + (summary.generatedBy || "생성 방식 미확인");
        elements.summarySections.replaceChildren();

        for (const definition of sectionDefinitions) {
            const points = Array.isArray(summary[definition.key]) ? summary[definition.key] : [];
            const section = createElement("section", "summary-section " + (definition.className || ""));
            const heading = createElement("h3", null, definition.title);
            heading.append(createElement("span", null, points.length + "개"));
            section.append(heading);

            if (!points.length) {
                section.append(createElement("p", "no-points", definition.empty));
            } else {
                const list = createElement("ul");
                for (const point of points) {
                    const item = createElement("li");
                    item.append(createElement("div", null, point.text || "내용 없음"));
                    const links = createElement("div", "evidence-links");
                    const ids = Array.isArray(point.evidenceIds) ? point.evidenceIds : [];
                    for (const id of ids) links.append(evidenceNode(id));
                    item.append(links);
                    list.append(item);
                }
                section.append(list);
            }
            elements.summarySections.append(section);
        }
        showSummaryState("content");
    }

    async function loadActivities(announce) {
        setControlsDisabled(true);
        elements.activityError.hidden = true;
        elements.globalStatus.textContent = announce ? "가명 활동 데이터를 새로 불러오고 있습니다." : "";
        try {
            const payload = await requestJson(API_BASE + "/activities");
            const activities = Array.isArray(payload.activities) ? payload.activities : [];
            state.activities = activities;
            state.activityById = new Map(activities.map((activity) => [activity.id, activity]));
            populateSelect(elements.memberSelect, getMembers(activities), "선택할 팀원이 없습니다");
            populateSelect(elements.projectSelect, getProjects(activities), "선택할 프로젝트가 없습니다");
            renderActivities();
            elements.globalStatus.textContent = announce ? activities.length + "개 활동을 새로 불러왔습니다." : "";
        } catch (error) {
            state.activities = [];
            state.activityById = new Map();
            populateSelect(elements.memberSelect, [], "팀원을 불러올 수 없습니다");
            populateSelect(elements.projectSelect, [], "프로젝트를 불러올 수 없습니다");
            elements.activityList.replaceChildren();
            elements.activityCount.textContent = "";
            elements.activityErrorMessage.textContent = error.message;
            elements.activityError.hidden = false;
            elements.globalStatus.textContent = "";
        } finally {
            setControlsDisabled(false);
        }
    }

    async function loadSummary(path) {
        const sequence = ++state.requestSequence;
        showSummaryState("loading");
        setControlsDisabled(true);
        elements.summaryRegion.scrollIntoView({ behavior: "smooth", block: "start" });
        try {
            const summary = await requestJson(path, SUMMARY_TIMEOUT_MS);
            if (sequence !== state.requestSequence) return;
            renderSummary(summary);
        } catch (error) {
            if (sequence !== state.requestSequence) return;
            elements.summaryErrorMessage.textContent = error.message;
            showSummaryState("error");
            elements.summaryError.focus();
        } finally {
            if (sequence === state.requestSequence) setControlsDisabled(false);
        }
    }

    elements.memberSelect.addEventListener("change", () => setControlsDisabled(false));
    elements.projectSelect.addEventListener("change", () => setControlsDisabled(false));
    elements.reloadButton.addEventListener("click", () => loadActivities(true));
    elements.memberButton.addEventListener("click", () => {
        if (!elements.memberSelect.value) return;
        loadSummary(API_BASE + "/members/" + encodeURIComponent(elements.memberSelect.value) + "/summary");
    });
    elements.projectButton.addEventListener("click", () => {
        if (!elements.projectSelect.value) return;
        loadSummary(API_BASE + "/projects/" + encodeURIComponent(elements.projectSelect.value) + "/summary?mode=PROJECT");
    });
    elements.handoffButton.addEventListener("click", () => {
        if (!elements.projectSelect.value) return;
        loadSummary(API_BASE + "/projects/" + encodeURIComponent(elements.projectSelect.value) + "/summary?mode=HANDOFF");
    });

    loadActivities(false);
}());
