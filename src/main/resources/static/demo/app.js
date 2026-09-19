(function () {
    "use strict";

    const API_BASE = "/api/v1/demo";
    // 외부 AI 호출은 서버 타임아웃(Bedrock 기본 30초)보다 화면이 먼저 포기하지 않도록 여유를 둔다.
    const SUMMARY_TIMEOUT_MS = 45000;
    const MAX_QUERY_LENGTH = 500;
    const state = { activities: [], activityById: new Map(), requestSequence: 0, similarSequence: 0, similarLoading: false };

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
        activityErrorMessage: document.querySelector("#activity-error-message"),
        similarForm: document.querySelector("#similar-form"),
        similarQuery: document.querySelector("#similar-query"),
        similarCount: document.querySelector("#similar-count"),
        similarSubmit: document.querySelector("#similar-submit"),
        similarChips: document.querySelectorAll("#similar-form .chip"),
        similarLoading: document.querySelector("#similar-loading"),
        similarError: document.querySelector("#similar-error"),
        similarErrorMessage: document.querySelector("#similar-error-message"),
        similarContent: document.querySelector("#similar-content"),
        similarOverview: document.querySelector("#similar-overview"),
        similarGenerated: document.querySelector("#similar-generated"),
        similarPoints: document.querySelector("#similar-points"),
        similarPointsCount: document.querySelector("#similar-points-count"),
        similarApproach: document.querySelector("#similar-approach"),
        similarApproachCount: document.querySelector("#similar-approach-count"),
        similarMatches: document.querySelector("#similar-matches"),
        similarMembers: document.querySelector("#similar-members")
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

    async function requestJson(path, timeoutMs = 15000, body) {
        const controller = new AbortController();
        const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);
        try {
            const headers = { Accept: "application/json" };
            if (body !== undefined) headers["Content-Type"] = "application/json";
            const response = await fetch(path, {
                method: body === undefined ? "GET" : "POST",
                headers,
                body: body === undefined ? undefined : JSON.stringify(body),
                credentials: "same-origin",
                signal: controller.signal
            });
            if (!response.ok) {
                const detail = response.status === 404
                    ? "선택한 데모 자료를 찾을 수 없습니다."
                    : response.status === 400
                        ? "입력한 내용을 확인해 주세요. 2자 이상 " + MAX_QUERY_LENGTH + "자 이하로 입력할 수 있습니다."
                        : response.status === 503
                            ? "AI 응답을 만들지 못했습니다. 잠시 후 다시 시도해 주세요."
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

    function evidenceNode(evidenceId, lookup = state.activityById) {
        const activity = lookup.get(evidenceId);
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

    function showSimilarState(name) {
        elements.similarLoading.hidden = name !== "loading";
        elements.similarError.hidden = name !== "error";
        elements.similarContent.hidden = name !== "content";
    }

    function currentQuery() {
        return elements.similarQuery.value.trim().replace(/\s+/g, " ");
    }

    function updateSimilarControls() {
        const length = elements.similarQuery.value.length;
        elements.similarCount.textContent = length + " / " + MAX_QUERY_LENGTH;
        elements.similarSubmit.disabled = state.similarLoading || currentQuery().length < 2;
        elements.similarQuery.disabled = state.similarLoading;
        for (const chip of elements.similarChips) chip.disabled = state.similarLoading;
    }

    function renderPointList(list, countLabel, points, lookup, emptyText) {
        list.replaceChildren();
        countLabel.textContent = points.length + "개";
        if (!points.length) {
            list.append(createElement("li", "no-points", emptyText));
            return;
        }
        for (const point of points) {
            const item = createElement("li");
            item.append(createElement("div", null, point.text || "내용 없음"));
            const links = createElement("div", "evidence-links");
            const ids = Array.isArray(point.evidenceIds) ? point.evidenceIds : [];
            for (const id of ids) links.append(evidenceNode(id, lookup));
            item.append(links);
            list.append(item);
        }
    }

    function matchCard(match) {
        const activity = match.activity || {};
        const kind = activityKind(activity.kind);
        const card = createElement("article", "activity-card match-card");

        const icon = createElement("span", "source-icon" + (kind.github ? " is-github" : ""), kind.short);
        icon.setAttribute("aria-hidden", "true");

        const body = createElement("div", "activity-body");
        const kicker = createElement("div", "activity-kicker");
        kicker.append(
            createElement("span", null, kind.label + " · " + activity.id),
            createElement("span", null, activity.memberName || "가명 팀원"),
            createElement("span", null, activity.status || "상태 없음"),
            createElement("time", null, formatDate(activity.occurredAt, false))
        );
        body.append(kicker, createElement("h3", null, activity.title || "제목 없음"));
        if (activity.details) body.append(createElement("p", null, activity.details));

        const score = Math.max(0, Math.min(1, Number(match.score) || 0));
        const meter = createElement("div", "score");
        const bar = createElement("span", "score-bar");
        const fill = createElement("span", "score-fill");
        fill.style.width = Math.round(score * 100) + "%";
        bar.append(fill);
        bar.setAttribute("aria-hidden", "true");
        meter.append(bar, createElement("span", "score-label", "유사도 " + score.toFixed(2)));
        body.append(meter);

        const url = safeSourceUrl(activity.sourceUrl);
        const link = createElement(url ? "a" : "span", "source-link", url ? "원본 ↗" : "원본 링크 없음");
        if (url) {
            link.href = url;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.setAttribute("aria-label", (activity.title || "과거 업무") + " 원본을 새 창에서 열기");
        }
        card.append(icon, body, link);
        return card;
    }

    function renderSimilar(result) {
        const matches = Array.isArray(result.matches) ? result.matches : [];
        const lookup = new Map(matches.filter((match) => match.activity).map((match) => [match.activity.id, match.activity]));
        const explanation = result.explanation || {};

        elements.similarOverview.textContent = explanation.overview || "설명을 만들지 못했습니다.";
        elements.similarGenerated.textContent = "생성: " + formatDate(result.generatedAt, true)
            + " · 설명 " + (explanation.generatedBy || "미확인") + " · 임베딩 " + (result.embeddingModel || "미확인");

        renderPointList(elements.similarPoints, elements.similarPointsCount,
            Array.isArray(explanation.similarWork) ? explanation.similarWork : [], lookup,
            "관련성이 뚜렷한 과거 업무를 찾지 못했습니다.");
        renderPointList(elements.similarApproach, elements.similarApproachCount,
            Array.isArray(explanation.suggestedApproach) ? explanation.suggestedApproach : [], lookup,
            "과거 업무에서 확인되는 해결 방법이 없습니다.");

        elements.similarMatches.replaceChildren(...matches.map(matchCard));

        const members = Array.isArray(result.experiencedMembers) ? result.experiencedMembers : [];
        elements.similarMembers.replaceChildren();
        if (!members.length) {
            elements.similarMembers.append(createElement("li", "no-points", "관련 경험을 확인할 팀원이 없습니다."));
        }
        for (const member of members) {
            const item = createElement("li", "member-item");
            item.append(createElement("strong", null, member.memberName || member.memberId));
            const links = createElement("div", "evidence-links");
            for (const id of Array.isArray(member.evidenceIds) ? member.evidenceIds : []) links.append(evidenceNode(id, lookup));
            item.append(links);
            elements.similarMembers.append(item);
        }
        showSimilarState("content");
    }

    async function searchSimilar() {
        const query = currentQuery();
        if (query.length < 2 || state.similarLoading) return;
        const sequence = ++state.similarSequence;
        state.similarLoading = true;
        updateSimilarControls();
        showSimilarState("loading");
        try {
            const result = await requestJson(API_BASE + "/similar-work", SUMMARY_TIMEOUT_MS, { query });
            if (sequence !== state.similarSequence) return;
            renderSimilar(result);
            elements.similarContent.focus({ preventScroll: true });
        } catch (error) {
            if (sequence !== state.similarSequence) return;
            elements.similarErrorMessage.textContent = error.message;
            showSimilarState("error");
            elements.similarError.focus();
        } finally {
            if (sequence === state.similarSequence) {
                state.similarLoading = false;
                updateSimilarControls();
            }
        }
    }

    elements.similarQuery.addEventListener("input", updateSimilarControls);
    elements.similarQuery.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            searchSimilar();
        }
    });
    elements.similarForm.addEventListener("submit", (event) => {
        event.preventDefault();
        searchSimilar();
    });
    for (const chip of elements.similarChips) {
        chip.addEventListener("click", () => {
            elements.similarQuery.value = chip.dataset.example || "";
            updateSimilarControls();
            searchSimilar();
        });
    }
    updateSimilarControls();

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
