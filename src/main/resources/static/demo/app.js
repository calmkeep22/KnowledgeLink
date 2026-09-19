(function () {
    "use strict";

    const API_BASE = "/api/v1/demo";
    // 외부 AI 호출은 서버 타임아웃(Bedrock 기본 30초)보다 화면이 먼저 포기하지 않도록 여유를 둔다.
    const SUMMARY_TIMEOUT_MS = 45000;
    const MAX_QUERY_LENGTH = 500;
    const AVATAR_COLORS = ["#1868db", "#803fa5", "#4c6b1f", "#c75300", "#ae2e24", "#206a83", "#5e4db2"];
    const state = { activities: [], activityById: new Map(), requestSequence: 0, similarSequence: 0, similarLoading: false };

    const $ = (selector) => document.querySelector(selector);
    const elements = {
        globalStatus: $("#global-status"),
        reloadButton: $("#reload-button"),
        memberSelect: $("#member-select"),
        projectSelect: $("#project-select"),
        memberButton: $("#member-summary-button"),
        projectButton: $("#project-summary-button"),
        handoffButton: $("#handoff-summary-button"),
        summaryRegion: $("#summary-region"),
        summaryEmpty: $("#summary-empty"),
        summaryLoading: $("#summary-loading"),
        summaryError: $("#summary-error"),
        summaryErrorMessage: $("#summary-error-message"),
        summaryContent: $("#summary-content"),
        summaryMode: $("#summary-mode"),
        summaryTitle: $("#summary-content-title"),
        summaryGenerated: $("#summary-generated"),
        summarySections: $("#summary-sections"),
        activityList: $("#activity-list"),
        activityCount: $("#activity-count"),
        activityError: $("#activity-error"),
        activityErrorMessage: $("#activity-error-message"),
        similarRegion: $("#similar-region"),
        similarForm: $("#similar-form"),
        similarQuery: $("#similar-query"),
        similarCount: $("#similar-count"),
        similarSubmit: $("#similar-submit"),
        similarChips: document.querySelectorAll(".example-chips .chip"),
        similarLoading: $("#similar-loading"),
        similarError: $("#similar-error"),
        similarErrorMessage: $("#similar-error-message"),
        similarContent: $("#similar-content"),
        similarOverview: $("#similar-overview"),
        similarGenerated: $("#similar-generated"),
        similarPoints: $("#similar-points"),
        similarPointsCount: $("#similar-points-count"),
        similarApproach: $("#similar-approach"),
        similarApproachCount: $("#similar-approach-count"),
        similarMatches: $("#similar-matches"),
        similarMembers: $("#similar-members")
    };

    const boardColumns = [
        { key: "completed", title: "완료", empty: "확인된 완료 항목이 없습니다." },
        { key: "inProgress", title: "진행 중", empty: "확인된 진행 항목이 없습니다." },
        { key: "blockers", title: "막힘", empty: "막힌 점이 없습니다.", blocker: true },
        { key: "nextActions", title: "다음 행동", empty: "제안할 다음 행동이 없습니다." }
    ];

    // ---------- 공통 렌더링 도우미 ----------

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
            JIRA_ISSUE: { short: "J", label: "Jira 이슈", className: "type-jira" },
            GITHUB_PULL_REQUEST: { short: "PR", label: "GitHub PR", className: "type-pr" },
            GITHUB_COMMIT: { short: "C", label: "GitHub 커밋", className: "type-commit" }
        };
        return labels[kind] || { short: "?", label: kind || "활동", className: "type-jira" };
    }

    function typeIcon(kind) {
        const info = activityKind(kind);
        const icon = createElement("span", "type-icon " + info.className, info.short);
        icon.title = info.label;
        icon.setAttribute("aria-label", info.label);
        return icon;
    }

    function statusLozenge(status) {
        const value = String(status || "").toUpperCase();
        let className = "loz-default";
        if (value.includes("MERGED")) className = "loz-merged";
        else if (/(DONE|CLOSED|RESOLVED|COMMITTED)/.test(value)) className = "loz-success";
        else if (/(BLOCK)/.test(value)) className = "loz-danger";
        else if (/(PROGRESS|REVIEW|OPEN)/.test(value)) className = "loz-progress";
        return createElement("span", "lozenge " + className, value.replace(/_/g, " ") || "상태 없음");
    }

    function avatar(name, small) {
        const label = String(name || "?");
        let hash = 0;
        for (const character of label) hash = (hash * 31 + character.codePointAt(0)) >>> 0;
        const element = createElement("span", "avatar" + (small ? " avatar-sm" : ""), Array.from(label)[0] || "?");
        element.style.background = AVATAR_COLORS[hash % AVATAR_COLORS.length];
        element.setAttribute("aria-hidden", "true");
        return element;
    }

    function assignee(name) {
        const wrapper = createElement("span", "assignee");
        wrapper.append(avatar(name, true), createElement("span", null, name || "가명 팀원"));
        return wrapper;
    }

    function sourceLink(activity, label) {
        const url = safeSourceUrl(activity.sourceUrl);
        const link = createElement(url ? "a" : "span", "source-link", url ? label : "원본 없음");
        if (url) {
            link.href = url;
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.setAttribute("aria-label", (activity.title || "활동") + " 원본을 새 창에서 열기");
        }
        return link;
    }

    function evidenceNode(evidenceId, lookup = state.activityById) {
        const activity = lookup.get(evidenceId);
        if (!activity) return createElement("span", "missing-evidence", "근거 " + evidenceId);
        const label = activityKind(activity.kind).label + " · " + activity.id;
        const url = safeSourceUrl(activity.sourceUrl);
        if (!url) return createElement("span", "missing-evidence", label);
        const link = createElement("a", "evidence-link", label + " ↗");
        link.href = url;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.title = activity.title || "";
        link.setAttribute("aria-label", (activity.title || "근거") + " 근거를 새 창에서 열기");
        return link;
    }

    function evidenceLinks(ids, lookup) {
        const links = createElement("div", "evidence-links");
        for (const id of Array.isArray(ids) ? ids : []) links.append(evidenceNode(id, lookup));
        return links;
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
            if (error.name === "AbortError") throw new Error("요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.");
            throw error;
        } finally {
            window.clearTimeout(timeoutId);
        }
    }

    // ---------- 팀 현황 ----------

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
            select.append(new Option(placeholder, ""));
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

    function renderActivities() {
        const rows = [];
        const head = createElement("div", "table-row table-head");
        head.setAttribute("role", "presentation");
        head.append(
            createElement("span", null, ""),
            createElement("span", null, "제목"),
            createElement("span", "col-assignee", "담당자"),
            createElement("span", null, "상태"),
            createElement("span", "col-date", "날짜"),
            createElement("span", "col-link", "")
        );
        rows.push(head);

        for (const activity of state.activities) {
            const row = createElement("div", "table-row");
            row.setAttribute("role", "listitem");
            const title = createElement("div", "table-title");
            title.append(
                createElement("strong", null, activity.title || "제목 없음"),
                createElement("span", null, activity.id + (activity.details ? " · " + activity.details : ""))
            );
            const assigneeCell = createElement("span", "table-cell col-assignee");
            assigneeCell.append(assignee(activity.memberName));
            const statusCell = createElement("span", "table-cell");
            statusCell.append(statusLozenge(activity.status));
            const linkCell = createElement("span", "table-cell col-link");
            linkCell.append(sourceLink(activity, "원본 ↗"));
            row.append(
                typeIcon(activity.kind),
                title,
                assigneeCell,
                statusCell,
                createElement("span", "table-cell col-date", formatDate(activity.occurredAt, false)),
                linkCell
            );
            rows.push(row);
        }
        elements.activityList.replaceChildren(...rows);
        elements.activityCount.textContent = state.activities.length + "개 활동 · 최신순";
    }

    function showSummaryState(name) {
        elements.summaryEmpty.hidden = name !== "empty";
        elements.summaryLoading.hidden = name !== "loading";
        elements.summaryError.hidden = name !== "error";
        elements.summaryContent.hidden = name !== "content";
    }

    function renderSummary(summary) {
        const modeLabels = { MEMBER: "팀원 요약", PROJECT: "프로젝트 요약", HANDOFF: "인수인계 요약" };
        elements.summaryMode.textContent = modeLabels[summary.mode] || summary.mode || "업무 요약";
        elements.summaryTitle.textContent = summary.title || "업무 요약";
        elements.summaryGenerated.textContent = "생성 " + formatDate(summary.generatedAt, true) + " · " + (summary.generatedBy || "생성 방식 미확인");

        const columns = boardColumns.map((definition) => {
            const points = Array.isArray(summary[definition.key]) ? summary[definition.key] : [];
            const column = createElement("section", "board-column");
            const head = createElement("div", "board-column-head");
            head.append(createElement("span", null, definition.title), createElement("span", "count", String(points.length)));
            column.append(head);
            if (!points.length) column.append(createElement("p", "board-empty", definition.empty));
            for (const point of points) {
                const card = createElement("div", "board-card" + (definition.blocker ? " is-blocker" : ""));
                card.append(createElement("div", null, point.text || "내용 없음"), evidenceLinks(point.evidenceIds));
                column.append(card);
            }
            return column;
        });
        elements.summarySections.replaceChildren(...columns);
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
        elements.summaryRegion.scrollIntoView({ behavior: "smooth", block: "nearest" });
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

    // ---------- 유사 업무 검색 ----------

    function showSimilarState(name) {
        elements.similarLoading.hidden = name !== "loading";
        elements.similarError.hidden = name !== "error";
        elements.similarContent.hidden = name !== "content";
    }

    function currentQuery() {
        return elements.similarQuery.value.trim().replace(/\s+/g, " ");
    }

    function updateSimilarControls() {
        elements.similarCount.textContent = elements.similarQuery.value.length + " / " + MAX_QUERY_LENGTH;
        elements.similarSubmit.disabled = state.similarLoading || currentQuery().length < 2;
        elements.similarQuery.disabled = state.similarLoading;
        for (const chip of elements.similarChips) chip.disabled = state.similarLoading;
    }

    function renderPointList(list, countLabel, points, lookup, emptyText) {
        countLabel.textContent = String(points.length);
        if (!points.length) {
            list.replaceChildren(createElement("li", "no-points", emptyText));
            return;
        }
        list.replaceChildren(...points.map((point) => {
            const item = createElement("li");
            item.append(createElement("div", null, point.text || "내용 없음"), evidenceLinks(point.evidenceIds, lookup));
            return item;
        }));
    }

    function issueRow(match) {
        const activity = match.activity || {};
        const row = createElement("article", "issue-row");

        const main = createElement("div", "issue-main");
        const top = createElement("div", "issue-top");
        top.append(createElement("span", "issue-key", activity.id || ""), assignee(activity.memberName),
            createElement("span", null, formatDate(activity.occurredAt, false)));
        main.append(top, createElement("h4", "issue-title", activity.title || "제목 없음"));
        if (activity.details) main.append(createElement("p", "issue-desc", activity.details));

        const score = Math.max(0, Math.min(1, Number(match.score) || 0));
        const meter = createElement("div", "score");
        const bar = createElement("span", "score-bar");
        const fill = createElement("span", "score-fill");
        fill.style.width = Math.round(score * 100) + "%";
        bar.append(fill);
        bar.setAttribute("aria-hidden", "true");
        meter.append(bar, createElement("span", "score-label", "유사도 " + score.toFixed(2)));
        main.append(meter);

        const side = createElement("div", "issue-side");
        side.append(statusLozenge(activity.status), sourceLink(activity, "원본 ↗"));
        row.append(typeIcon(activity.kind), main, side);
        return row;
    }

    function renderSimilar(result) {
        const matches = Array.isArray(result.matches) ? result.matches : [];
        const lookup = new Map(matches.filter((match) => match.activity).map((match) => [match.activity.id, match.activity]));
        const explanation = result.explanation || {};

        elements.similarOverview.textContent = explanation.overview || "설명을 만들지 못했습니다.";
        elements.similarGenerated.textContent = formatDate(result.generatedAt, true)
            + " · 답변 " + (explanation.generatedBy || "미확인") + " · 임베딩 " + (result.embeddingModel || "미확인");

        renderPointList(elements.similarPoints, elements.similarPointsCount,
            Array.isArray(explanation.similarWork) ? explanation.similarWork : [], lookup,
            "관련성이 뚜렷한 과거 업무를 찾지 못했습니다.");
        renderPointList(elements.similarApproach, elements.similarApproachCount,
            Array.isArray(explanation.suggestedApproach) ? explanation.suggestedApproach : [], lookup,
            "과거 업무에서 확인되는 해결 방법이 없습니다.");

        elements.similarMatches.replaceChildren(...matches.map(issueRow));

        const members = Array.isArray(result.experiencedMembers) ? result.experiencedMembers : [];
        if (!members.length) {
            elements.similarMembers.replaceChildren(createElement("li", "no-points", "관련 경험을 확인할 팀원이 없습니다."));
        } else {
            elements.similarMembers.replaceChildren(...members.map((member) => {
                const item = createElement("li", "person");
                const body = createElement("div");
                body.append(createElement("strong", null, member.memberName || member.memberId), evidenceLinks(member.evidenceIds, lookup));
                item.append(avatar(member.memberName || member.memberId), body);
                return item;
            }));
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
        elements.similarRegion.scrollIntoView({ behavior: "smooth", block: "start" });
        try {
            const result = await requestJson(API_BASE + "/similar-work", SUMMARY_TIMEOUT_MS, { query });
            if (sequence !== state.similarSequence) return;
            renderSimilar(result);
            elements.similarContent.focus({ preventScroll: true });
        } catch (error) {
            if (sequence !== state.similarSequence) return;
            elements.similarErrorMessage.textContent = error.message;
            showSimilarState("error");
            elements.similarError.focus({ preventScroll: true });
        } finally {
            if (sequence === state.similarSequence) {
                state.similarLoading = false;
                updateSimilarControls();
            }
        }
    }

    // ---------- 이벤트 ----------

    elements.similarQuery.addEventListener("input", updateSimilarControls);
    elements.similarQuery.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
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

    updateSimilarControls();
    loadActivities(false);
}());
