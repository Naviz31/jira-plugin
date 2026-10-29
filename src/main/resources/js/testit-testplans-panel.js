(function ($) {
    var currentIssueKey = null;
    var eventsBound = false;
    var activeRequest = null;
    var loadSeq = 0;

    function restBase() {
        return AJS.contextPath() + "/rest/testit-conf/1.0/tms/issues/";
    }

    function formatAjaxError(jqXHR, textStatus, errorThrown) {
        return TestITCommon.formatAjaxError(jqXHR, textStatus, errorThrown);
    }

    function issueKeysEqual(a, b) {
        if (!a || !b) {
            return false;
        }
        return String(a).toUpperCase() === String(b).toUpperCase();
    }

    function resolveIssueKey($panel, explicitKey) {
        return TestITCommon.resolveIssueKey({ mode: "panel", explicitKey: explicitKey, $element: $panel });
    }

    function formatPlanLabel(plan) {
        return plan.name ? plan.name : "Тест-план";
    }

    var SVG_NS = "http://www.w3.org/2000/svg";

    function statusLozengeClass(statusType) {
        if (!statusType) {
            return "aui-lozenge";
        }
        if (statusType === "Completed") {
            return "aui-lozenge aui-lozenge-success";
        }
        if (statusType === "InProgress") {
            return "aui-lozenge testit-tp-status-inprogress";
        }
        return "aui-lozenge";
    }

    // Colour and sort order of a test status: label first (Skipped/Blocked share the
    // "Incomplete" type in Test IT), then the status type.
    function statusMeta(status) {
        var label = String(status.label || "").toLowerCase();
        var type = status.statusType || "";
        if (/блок|block/.test(label)) {
            return { color: "#6b778c", order: 5 };
        }
        if (/пропущ|skip/.test(label)) {
            return { color: "#ff991f", order: 4 };
        }
        if (type === "Succeeded") {
            return { color: "#00875a", order: 1 };
        }
        if (type === "Failed") {
            return { color: "#de350b", order: 2 };
        }
        if (type === "InProgress") {
            return { color: "#6554c0", order: 3 };
        }
        if (type === "Incomplete") {
            return { color: "#ff991f", order: 4 };
        }
        if (type === "Pending") {
            return { color: "#00b8d9", order: 6 };
        }
        return { color: "#97a0af", order: 7 };
    }

    var SHADES = {
        "#00875a": ["#36b37e", "#79f2c0"],
        "#de350b": ["#ff5630", "#ff8f73"],
        "#6554c0": ["#8777d9", "#c0b6f2"],
        "#ff991f": ["#ffab00", "#ffc400"],
        "#6b778c": ["#8993a4", "#b3bac5"],
        "#00b8d9": ["#00c7e6", "#79e2f2"],
        "#97a0af": ["#b3bac5", "#c1c7d0"]
    };

    function svgEl(name, attrs) {
        var el = document.createElementNS(SVG_NS, name);
        $.each(attrs || {}, function (k, v) {
            el.setAttribute(k, v);
        });
        return el;
    }

    function percent(part, total) {
        return total > 0 ? Math.round((part / total) * 1000) / 10 : 0;
    }

    function formatPercent(value) {
        return (value % 1 === 0 ? String(value) : value.toFixed(1)).replace(".", ",") + "%";
    }

    function normalizeStatuses(tests) {
        var list = [];
        $.each(tests && tests.statuses ? tests.statuses : [], function (_, status) {
            var count = status.count != null ? status.count : 0;
            var meta = statusMeta(status);
            list.push({ label: status.label || "—", count: count, color: meta.color, order: meta.order, type: status.statusType });
        });
        list.sort(function (x, y) {
            return x.order - y.order;
        });
        // Custom statuses often share a type (and so a colour); give every status its own shade.
        var used = {};
        $.each(list, function (_, st) {
            var shades = SHADES[st.color] || [];
            var color = st.color;
            var i = 0;
            while (used[color] && i < shades.length) {
                color = shades[i++];
            }
            used[color] = true;
            st.color = color;
        });
        return list;
    }

    // onPick(label), when given, makes the chart parts clickable: they open the list of tests in that status.
    function renderDonut(statuses, total, passPercent, onPick) {
        var $wrap = $("<div>").addClass("testit-tp-donut");
        var svg = svgEl("svg", { viewBox: "0 0 42 42", "aria-hidden": "true" });
        svg.appendChild(svgEl("circle", {
            cx: 21, cy: 21, r: 15.9155, fill: "none", stroke: "#ebecf0", "stroke-width": 5
        }));
        var offset = 25; // start at 12 o'clock
        if (total > 0) {
            $.each(statuses, function (_, st) {
                if (st.count <= 0) {
                    return;
                }
                var len = (st.count / total) * 100;
                var seg = svgEl("circle", {
                    cx: 21, cy: 21, r: 15.9155, fill: "none",
                    stroke: st.color, "stroke-width": 5,
                    "stroke-dasharray": len + " " + (100 - len),
                    "stroke-dashoffset": offset
                });
                var title = svgEl("title");
                title.textContent = st.label + ": " + st.count + " (" + formatPercent(percent(st.count, total)) + ")"
                    + (onPick ? " — показать тесты" : "");
                seg.appendChild(title);
                if (onPick) {
                    seg.setAttribute("class", "testit-tp-seg-click");
                    seg.addEventListener("click", function () {
                        onPick(st.label);
                    });
                }
                svg.appendChild(seg);
                offset -= len;
            });
        }
        $wrap.append(svg);
        $wrap.append(
            $("<div>").addClass("testit-tp-donut-center").append(
                $("<div>").addClass("testit-tp-donut-value").text(total > 0 ? formatPercent(passPercent) : "—"),
                $("<div>").addClass("testit-tp-donut-caption").text(total > 0 ? "успешно" : "нет тестов")
            )
        );
        return $wrap;
    }

    function renderStackedBar(statuses, total, onPick) {
        var $bar = $("<div>").addClass("testit-tp-bar");
        if (total <= 0) {
            return $bar;
        }
        $.each(statuses, function (_, st) {
            if (st.count <= 0) {
                return;
            }
            var $seg = $("<span>").addClass("testit-tp-bar-seg")
                .css({ width: percent(st.count, total) + "%", backgroundColor: st.color })
                .attr("title", st.label + ": " + st.count + (onPick ? " — показать тесты" : ""));
            if (onPick) {
                $seg.addClass("testit-tp-seg-click").on("click", function () {
                    onPick(st.label);
                });
            }
            $bar.append($seg);
        });
        return $bar;
    }

    function renderLegend(statuses, total, onPick) {
        var $legend = $("<ul>").addClass("testit-tp-legend");
        $.each(statuses, function (_, st) {
            var $item = $("<li>").toggleClass("testit-tp-legend-zero", st.count === 0).append(
                $("<span>").addClass("testit-tp-dot").css("backgroundColor", st.color),
                $("<span>").addClass("testit-tp-legend-label").text(st.label),
                $("<span>").addClass("testit-tp-legend-count").text(st.count),
                $("<span>").addClass("testit-tp-legend-pct").text(total > 0 ? formatPercent(percent(st.count, total)) : "")
            );
            if (onPick && st.count > 0) {
                $item.addClass("testit-tp-legend-click")
                    .attr({ role: "button", tabindex: "0", title: "Показать тесты со статусом «" + st.label + "»" })
                    .on("click", function () {
                        onPick(st.label);
                    }).on("keydown", function (e) {
                        if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            onPick(st.label);
                        }
                    });
            }
            $legend.append($item);
        });
        return $legend;
    }

    function metaChip(label, value) {
        return $("<span>").addClass("testit-tp-chip").append(
            $("<span>").addClass("testit-tp-chip-label").text(label),
            $("<span>").addClass("testit-tp-chip-value").text(value)
        );
    }

    // Soft colour pairs for option chips; the pair is picked by a hash of the option title so the
    // same option always gets the same colour.
    var CHIP_PALETTE = [
        { bg: "#deebff", fg: "#0747a6" },
        { bg: "#e3fcef", fg: "#006644" },
        { bg: "#eae6ff", fg: "#403294" },
        { bg: "#fff0b3", fg: "#7f5f01" },
        { bg: "#ffebe6", fg: "#bf2600" },
        { bg: "#e6fcff", fg: "#008da6" },
        { bg: "#ffe2f1", fg: "#a1176f" },
        { bg: "#ebecf0", fg: "#42526e" }
    ];

    function hashString(text) {
        var hash = 0;
        for (var i = 0; i < text.length; i++) {
            hash = (hash * 31 + text.charCodeAt(i)) % 2147483647;
        }
        return hash;
    }

    function chipColors(text) {
        return CHIP_PALETTE[hashString(String(text)) % CHIP_PALETTE.length];
    }

    // First letters of the first and last word; Array.from keeps emoji and other astral characters whole.
    function initials(name) {
        var parts = String(name).trim().split(/\s+/);
        function first(word) {
            return word ? (Array.from(word)[0] || "") : "";
        }
        var letters = first(parts[0]) + (parts.length > 1 ? first(parts[parts.length - 1]) : "");
        return letters.toUpperCase() || "?";
    }

    function appendTextWithLinks($target, text) {
        var parts = String(text).split(/(https?:\/\/[^\s<>"']+)/i);
        $.each(parts, function (_, part) {
            if (!part) {
                return;
            }
            if (/^https?:\/\//i.test(part)) {
                // Punctuation right after a link ("see https://x.y/z.") belongs to the sentence, not the URL.
                var trailing = /[.,;:!?)\]]+$/.exec(part);
                var url = trailing ? part.slice(0, part.length - trailing[0].length) : part;
                $target.append(
                    $("<a>").attr({ href: url, target: "_blank", rel: "noopener noreferrer" }).text(url)
                );
                if (trailing) {
                    $target.append(document.createTextNode(trailing[0]));
                }
            } else {
                $target.append(document.createTextNode(part));
            }
        });
    }

    function attributeEmpty() {
        return $("<span>").addClass("testit-attr-empty").text("не заполнено");
    }

    function renderAttributeValue(attr) {
        var $value = $("<div>").addClass("testit-attr-value");
        var values = attr.values || [];
        switch (attr.type) {
            case "checkbox":
                if (attr.checked === true) {
                    $value.append($("<span>").addClass("testit-attr-check testit-attr-check-on").text("✓ Да"));
                } else if (attr.checked === false) {
                    $value.append($("<span>").addClass("testit-attr-check testit-attr-check-off").text("✕ Нет"));
                } else {
                    $value.append(attributeEmpty());
                }
                return $value;
            case "options":
            case "multipleOptions":
                if (!values.length) {
                    return $value.append(attributeEmpty());
                }
                $.each(values, function (_, text) {
                    var colors = chipColors(text);
                    $value.append(
                        $("<span>").addClass("testit-attr-chip").text(text)
                            .css({ backgroundColor: colors.bg, color: colors.fg })
                    );
                });
                return $value;
            case "user":
                if (!values.length) {
                    return $value.append(attributeEmpty());
                }
                $.each(values, function (_, text) {
                    var colors = chipColors(text);
                    $value.append(
                        $("<span>").addClass("testit-attr-user").append(
                            $("<span>").addClass("testit-attr-avatar").text(initials(text))
                                .css({ backgroundColor: colors.bg, color: colors.fg }),
                            $("<span>").addClass("testit-attr-user-name").text(text)
                        )
                    );
                });
                return $value;
            case "datetime":
                if (!values.length) {
                    return $value.append(attributeEmpty());
                }
                return $value.append($("<span>").addClass("testit-attr-date").text(values.join(", ")));
            default:
                if (!values.length) {
                    return $value.append(attributeEmpty());
                }
                $.each(values, function (_, text) {
                    var $line = $("<div>").addClass("testit-attr-text");
                    appendTextWithLinks($line, text);
                    $value.append($line);
                });
                return $value;
        }
    }

    function renderAttributes(attributes) {
        if (!attributes || !attributes.length) {
            return null;
        }
        var $grid = $("<div>").addClass("testit-attr-grid");
        $.each(attributes, function (_, attr) {
            var $label = $("<div>").addClass("testit-attr-label").text(attr.name || "—");
            if (attr.required) {
                $label.append($("<span>").addClass("testit-attr-required").attr("title", "Обязательный атрибут").text(" *"));
            }
            $grid.append($("<div>").addClass("testit-attr-tile").append($label, renderAttributeValue(attr)));
        });
        return $("<div>").addClass("testit-attr-section").append(
            $("<div>").addClass("testit-attr-heading").text("Атрибуты"),
            $grid
        );
    }

    // ---- bugs of the plan: defects from Test IT enriched with Jira issue data ----

    var activeTabs = {};
    var activeFilters = {};
    var jiraIssueCache = {};
    var jiraCacheStamp = 0;
    var BUGS_CHUNK = 40;
    var BUGS_MIN_HEIGHT = 380;
    var BUGS_MAX_HEIGHT = 560;
    var VISIBLE_TESTS = 2;

    function jiraBaseHost() {
        try {
            var base = window.AJS && AJS.Meta && AJS.Meta.get ? AJS.Meta.get("base-url") : null;
            return base ? new URL(base).host : null;
        } catch (e) {
            return null;
        }
    }

    // A link points to this Jira when its host is the one the user is on or the configured base URL.
    function isLocalJiraUrl(url) {
        try {
            var host = new URL(url).host;
            return host === window.location.host || host === jiraBaseHost();
        } catch (e) {
            return false;
        }
    }

    function isBugIssueType(name) {
        return /bug|баг|дефект|defect|ошибк|инцидент|incident/i.test(String(name || ""));
    }

    function safeHttpUrl(url) {
        return /^https?:\/\//i.test(String(url || "")) ? url : null;
    }

    var ISSUE_FIELDS = "summary,status,priority,assignee,issuetype";

    // Each cache entry is { deferred, data }. Requests keep a reference to their own entry, so resetting
    // the cache while a slow request is still running can neither break nor leave it unresolved.
    function settleIssue(entry, issue) {
        entry.data = issue || null;
        entry.deferred.resolve();
    }

    function fetchIssuesIndividually(keys, entries) {
        $.each(keys, function (_, key) {
            $.ajax({
                url: AJS.contextPath() + "/rest/api/2/issue/" + encodeURIComponent(key) + "?fields=" + ISSUE_FIELDS,
                dataType: "json",
                timeout: 20000
            }).done(function (issue) {
                settleIssue(entries[key], issue);
            }).fail(function () {
                settleIssue(entries[key], null);
            });
        });
    }

    // One JQL request per chunk of keys; falls back to per-issue requests when Jira rejects the query
    // (for example when a key of a project the user cannot see makes the whole query invalid).
    // Resolves with the cache entries of the requested keys, in order.
    function ensureIssues(keys) {
        var entries = {};
        var pending = [];
        $.each(keys, function (_, key) {
            if (!jiraIssueCache[key]) {
                jiraIssueCache[key] = { deferred: $.Deferred(), data: null };
                pending.push(key);
            }
            entries[key] = jiraIssueCache[key];
        });
        for (var i = 0; i < pending.length; i += BUGS_CHUNK) {
            (function (chunk) {
                $.ajax({
                    url: AJS.contextPath() + "/rest/api/2/search",
                    data: {
                        jql: "key in (\"" + chunk.join("\",\"") + "\")",
                        fields: ISSUE_FIELDS,
                        maxResults: chunk.length,
                        validateQuery: "warn"
                    },
                    dataType: "json",
                    timeout: 30000
                }).done(function (result) {
                    var found = {};
                    $.each(result && result.issues ? result.issues : [], function (_, issue) {
                        found[String(issue.key).toUpperCase()] = issue;
                    });
                    var missing = [];
                    $.each(chunk, function (_, key) {
                        if (found[key]) {
                            settleIssue(entries[key], found[key]);
                        } else {
                            missing.push(key);
                        }
                    });
                    // Moved/renamed issues are not matched by key in JQL; ask for them one by one.
                    fetchIssuesIndividually(missing, entries);
                }).fail(function () {
                    fetchIssuesIndividually(chunk, entries);
                });
            })(pending.slice(i, i + BUGS_CHUNK));
        }
        var promises = $.map(keys, function (key) {
            return entries[key].deferred.promise();
        });
        return $.when.apply($, promises).then(function () {
            return entries;
        });
    }

    function priorityRank(priority) {
        var id = priority && priority.id ? parseInt(priority.id, 10) : NaN;
        return isNaN(id) ? 99 : id;
    }

    function categoryKey(statusCategory) {
        var key = statusCategory ? statusCategory.key : null;
        if (key === "done") {
            return "done";
        }
        if (key === "indeterminate") {
            return "progress";
        }
        if (key === "new") {
            return "todo";
        }
        return "unknown";
    }

    // Resolves to the bugs of the plan as view models: open first, then by priority and key.
    function resolveBugs(plan) {
        // Jira data is reused between renders for a few seconds only, so a refresh shows fresh statuses.
        if (Date.now() - jiraCacheStamp > 15000) {
            jiraIssueCache = {};
            jiraCacheStamp = Date.now();
        }
        var bugs = plan.bugs || [];
        var localKeys = [];
        $.each(bugs, function (_, bug) {
            if (bug.key && isLocalJiraUrl(bug.url) && $.inArray(bug.key, localKeys) < 0) {
                localKeys.push(bug.key);
            }
        });
        var lookup = localKeys.length ? ensureIssues(localKeys) : $.Deferred().resolve({}).promise();
        return lookup.then(function (entries) {
            var list = [];
            $.each(bugs, function (_, bug) {
                var local = !!(bug.key && isLocalJiraUrl(bug.url));
                var data = local && entries[bug.key] ? entries[bug.key].data : null;
                var fields = data && data.fields ? data.fields : null;
                var typeName = fields && fields.issuetype ? fields.issuetype.name : null;
                // A plain link only counts as a bug when Jira says the issue is a bug.
                if (!bug.defect && !(fields && isBugIssueType(typeName))) {
                    return;
                }
                var status = fields && fields.status ? fields.status : null;
                list.push({
                    key: bug.key,
                    title: fields && fields.summary ? fields.summary : (bug.title || bug.key || bug.url),
                    url: local && bug.key ? AJS.contextPath() + "/browse/" + encodeURIComponent(bug.key) : bug.url,
                    hint: fields ? null : (local ? "нет доступа к задаче или она удалена" : "задача из другой Jira"),
                    defect: !!bug.defect,
                    linked: !!bug.linked,
                    typeName: typeName,
                    statusName: status ? status.name : null,
                    category: status ? categoryKey(status.statusCategory) : "unknown",
                    priority: fields && fields.priority ? fields.priority : null,
                    assignee: fields && fields.assignee ? fields.assignee : null,
                    tests: bug.tests || []
                });
            });
            list.sort(function (a, b) {
                var ad = a.category === "done" ? 1 : 0;
                var bd = b.category === "done" ? 1 : 0;
                if (ad !== bd) {
                    return ad - bd;
                }
                var ap = priorityRank(a.priority);
                var bp = priorityRank(b.priority);
                if (ap !== bp) {
                    return ap - bp;
                }
                return String(a.key || a.title).localeCompare(String(b.key || b.title), undefined, { numeric: true });
            });
            return list;
        });
    }

    var BUG_CATEGORIES = [
        { key: "todo", label: "К выполнению", color: "#97a0af" },
        { key: "progress", label: "В работе", color: "#2684ff" },
        { key: "done", label: "Закрыто", color: "#00875a" },
        { key: "unknown", label: "Нет данных Jira", color: "#dfe1e6" }
    ];

    function pluralBugs(n) {
        var mod10 = n % 10;
        var mod100 = n % 100;
        if (mod10 === 1 && mod100 !== 11) {
            return "дефект";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "дефекта";
        }
        return "дефектов";
    }

    function countBugs(bugs) {
        var counts = { todo: 0, progress: 0, done: 0, unknown: 0, open: 0 };
        $.each(bugs, function (_, vm) {
            counts[vm.category]++;
            if (vm.category !== "done") {
                counts.open++;
            }
        });
        return counts;
    }

    function renderBugsSummary(bugs, counts) {
        var $head = $("<div>").addClass("testit-bug-summary-head").append(
            $("<span>").addClass("testit-bug-total").text(bugs.length),
            $("<span>").addClass("testit-bug-total-label").text(pluralBugs(bugs.length)),
            $("<span>").addClass("testit-bug-open" + (counts.open > 0 ? " testit-bug-open-has" : ""))
                .text(counts.open > 0 ? "открыто: " + counts.open : "все закрыты")
        );
        var $bar = $("<div>").addClass("testit-tp-bar testit-bug-bar");
        var $legend = $("<ul>").addClass("testit-bug-legend");
        $.each(BUG_CATEGORIES, function (_, cat) {
            var count = counts[cat.key];
            if (count > 0) {
                $bar.append($("<span>").addClass("testit-tp-bar-seg")
                    .css({ width: percent(count, bugs.length) + "%", backgroundColor: cat.color })
                    .attr("title", cat.label + ": " + count));
                $legend.append(
                    $("<li>").append(
                        $("<span>").addClass("testit-tp-dot").css("backgroundColor", cat.color),
                        $("<span>").addClass("testit-bug-legend-label").text(cat.label),
                        $("<span>").addClass("testit-bug-legend-count").text(count)
                    )
                );
            }
        });
        return $("<div>").addClass("testit-bug-summary").append($head, $bar, $legend);
    }

    function renderBugAssignee(assignee) {
        var $cell = $("<div>").addClass("testit-bug-col testit-bug-col-assignee");
        if (!assignee) {
            return $cell.append($("<span>").addClass("testit-attr-empty").text("не назначен"));
        }
        var name = assignee.displayName || assignee.name || "—";
        var colors = chipColors(name);
        var avatarUrl = assignee.avatarUrls ? (assignee.avatarUrls["24x24"] || assignee.avatarUrls["32x32"]) : null;
        var $avatar;
        if (safeHttpUrl(avatarUrl)) {
            $avatar = $("<img>").addClass("testit-attr-avatar testit-attr-avatar-img").attr({ src: avatarUrl, alt: "" });
        } else {
            $avatar = $("<span>").addClass("testit-attr-avatar").text(initials(name))
                .css({ backgroundColor: colors.bg, color: colors.fg });
        }
        return $cell.append($("<span>").addClass("testit-attr-user").append(
            $avatar, $("<span>").addClass("testit-bug-assignee-name").attr("title", name).text(name)));
    }

    function renderBugTests(tests) {
        var $wrap = $("<div>").addClass("testit-bug-tests");
        if (!tests.length) {
            return $wrap;
        }
        function chip(test) {
            var label = test.name || ("Тест " + test.globalId);
            var $chip = safeHttpUrl(test.url)
                ? $("<a>").attr({ href: test.url, target: "_blank", rel: "noopener noreferrer" })
                : $("<span>");
            return $chip.addClass("testit-bug-test").attr("title", label +
                (test.status ? " · " + test.status : "")).text(label);
        }
        $wrap.append($("<span>").addClass("testit-bug-tests-label").text("Тесты:"));
        $.each(tests.slice(0, VISIBLE_TESTS), function (_, test) {
            $wrap.append(chip(test));
        });
        if (tests.length > VISIBLE_TESTS) {
            var $more = $("<button>").attr("type", "button").addClass("testit-bug-test-more")
                .text("ещё " + (tests.length - VISIBLE_TESTS));
            $more.on("click", function (e) {
                e.preventDefault();
                $more.remove();
                $.each(tests.slice(VISIBLE_TESTS), function (_, test) {
                    $wrap.append(chip(test));
                });
            });
            $wrap.append($more);
        }
        return $wrap;
    }

    function renderBugRow(vm) {
        var $main = $("<div>").addClass("testit-bug-col testit-bug-col-main");
        var $top = $("<div>").addClass("testit-bug-top");
        if (vm.key) {
            $top.append($("<span>").addClass("testit-bug-key").text(vm.key));
        }
        if (vm.defect) {
            $top.append($("<span>").addClass("testit-bug-src testit-bug-src-defect")
                .attr("title", "Дефект, прикреплённый к результату теста").text("Дефект"));
        }
        if (vm.linked) {
            $top.append($("<span>").addClass("testit-bug-src testit-bug-src-linked")
                .attr("title", "Задача привязана к тест-кейсу").text("Связан с тестом"));
        }
        // The URL comes from Test IT data: only http(s) links are made clickable.
        var $title = safeHttpUrl(vm.url)
            ? $("<a>").attr({ href: vm.url, target: "_blank", rel: "noopener noreferrer" })
            : $("<span>");
        $main.append($top, $title.addClass("testit-bug-title").attr("title", vm.title).text(vm.title));
        if (vm.hint) {
            $main.append($("<div>").addClass("testit-bug-hint").text(vm.hint));
        }
        $main.append(renderBugTests(vm.tests));

        var $status = $("<div>").addClass("testit-bug-col testit-bug-col-status").attr("data-label", "Статус");
        $status.append(vm.statusName
            ? $("<span>").addClass("testit-bug-status testit-bug-status-" + vm.category)
                .attr("title", vm.statusName).text(vm.statusName)
            : $("<span>").addClass("testit-attr-empty").text("—"));

        var $priority = $("<div>").addClass("testit-bug-col testit-bug-col-priority").attr("data-label", "Приоритет");
        if (vm.priority && vm.priority.name) {
            var $p = $("<span>").addClass("testit-bug-priority");
            if (safeHttpUrl(vm.priority.iconUrl)) {
                $p.append($("<img>").attr({ src: vm.priority.iconUrl, alt: "" }));
            }
            $priority.append($p.append($("<span>").text(vm.priority.name)));
        } else {
            $priority.append($("<span>").addClass("testit-attr-empty").text("—"));
        }

        var $assignee = renderBugAssignee(vm.assignee).attr("data-label", "Исполнитель");
        return $("<div>").addClass("testit-bug-row").append($main, $status, $priority, $assignee);
    }

    function renderBugFilters(counts, total, current, onChange) {
        var defs = [
            { key: "all", label: "Все", count: total },
            { key: "open", label: "Открытые", count: counts.open },
            { key: "done", label: "Закрытые", count: counts.done }
        ];
        var $filters = $("<div>").addClass("testit-bug-filters").attr("role", "group");
        $.each(defs, function (_, def) {
            $("<button>").attr({ type: "button", "data-filter": def.key })
                .addClass("testit-bug-filter" + (def.key === current ? " testit-bug-filter-active" : ""))
                .append($("<span>").text(def.label), $("<span>").addClass("testit-bug-filter-count").text(def.count))
                .on("click", function (e) {
                    e.preventDefault();
                    onChange(def.key);
                }).appendTo($filters);
        });
        return $filters;
    }

    function filterBugs(bugs, filter) {
        if (filter === "open") {
            return $.grep(bugs, function (vm) {
                return vm.category !== "done";
            });
        }
        if (filter === "done") {
            return $.grep(bugs, function (vm) {
                return vm.category === "done";
            });
        }
        return bugs;
    }

    function renderBugsList(bugs, filter) {
        var visible = filterBugs(bugs, filter);
        var $list = $("<div>").addClass("testit-bugs-list");
        if (!visible.length) {
            return $list.append($("<div>").addClass("testit-bugs-none description")
                .text(filter === "done" ? "Закрытых дефектов нет" : "Открытых дефектов нет — всё исправлено"));
        }
        $list.append($("<div>").addClass("testit-bug-row testit-bug-header").append(
            $("<div>").addClass("testit-bug-col").text("Дефект"),
            $("<div>").addClass("testit-bug-col").text("Статус"),
            $("<div>").addClass("testit-bug-col").text("Приоритет"),
            $("<div>").addClass("testit-bug-col").text("Исполнитель")
        ));
        $.each(visible, function (_, vm) {
            $list.append(renderBugRow(vm));
        });
        return $list;
    }

    function skeletonRows() {
        var $box = $("<div>").addClass("testit-bugs-skeleton").attr("aria-label", "Загрузка дефектов");
        for (var i = 0; i < 3; i++) {
            $box.append($("<div>").addClass("testit-skel-row").append(
                $("<span>").addClass("testit-skel testit-skel-wide"),
                $("<span>").addClass("testit-skel testit-skel-mid"),
                $("<span>").addClass("testit-skel testit-skel-short")));
        }
        return $box;
    }

    function planKeyOf(plan) {
        return String(plan.globalId != null ? plan.globalId : plan.url);
    }

    function renderBugsPane($pane, $badge, plan, onResolved) {
        var planKey = planKeyOf(plan);
        $pane.empty().append(skeletonRows());
        resolveBugs(plan).done(function (bugs) {
            if (onResolved) {
                onResolved(bugs);
            }
            $pane.empty();
            var counts = countBugs(bugs);
            $badge.removeClass("testit-tab-badge-loading").text(bugs.length)
                .toggleClass("testit-tab-badge-alert", counts.open > 0);
            if (!bugs.length) {
                $pane.append($("<div>").addClass("testit-bugs-empty").append(
                    $("<div>").addClass("testit-bugs-empty-icon").text("✓"),
                    $("<div>").addClass("testit-bugs-empty-title").text("В этом тест-плане нет дефектов"),
                    $("<div>").addClass("description").text(
                        "Здесь появятся дефекты из результатов тестов и задачи-дефекты Jira, привязанные к тест-кейсам плана.")
                ));
                return;
            }
            var $listHolder = $("<div>").addClass("testit-bugs-list-holder");
            var $toolbar = $("<div>").addClass("testit-bugs-toolbar");
            function show(filter) {
                activeFilters[planKey] = filter;
                $toolbar.empty().append(renderBugFilters(counts, bugs.length, filter, show));
                $listHolder.empty().append(renderBugsList(bugs, filter));
            }
            $pane.append(renderBugsSummary(bugs, counts), $toolbar, $listHolder);
            var initial = activeFilters[planKey] || "all";
            if (initial === "open" && !counts.open) {
                initial = "all";
            }
            if (initial === "done" && !counts.done) {
                initial = "all";
            }
            show(initial);
        }).fail(function () {
            $pane.empty().append($("<div>").addClass("testit-bugs-empty").append(
                $("<div>").addClass("testit-bugs-empty-title").text("Не удалось загрузить дефекты"),
                $("<div>").addClass("description").text("Обновите тест-планы, чтобы повторить попытку.")));
            $badge.removeClass("testit-tab-badge-loading").text("!").addClass("testit-tab-badge-alert");
        });
    }

    // ---- tests of the plan: status filter, list of tests with links back to Test IT ----

    var activeTestFilters = {};
    var TESTS_OTHER = "__other__";
    var TESTS_PAGE = 100;

    // The status entry a test belongs to: by title first, then by type when only one status has that type.
    function statusKeyForTest(test, statuses) {
        var found = null;
        $.each(statuses, function (_, st) {
            if (!found && st.label === test.status) {
                found = st.label;
            }
        });
        if (found) {
            return found;
        }
        var sameType = $.grep(statuses, function (st) {
            return st.type && st.type === test.statusType;
        });
        return sameType.length === 1 ? sameType[0].label : TESTS_OTHER;
    }

    function renderTestRow(test, color, bugKeys) {
        var label = test.name || ("Тест " + test.globalId);
        var $name = safeHttpUrl(test.url)
            ? $("<a>").attr({ href: test.url, target: "_blank", rel: "noopener noreferrer" })
            : $("<span>");
        var $main = $("<div>").addClass("testit-tst-col testit-tst-col-main").append(
            $name.addClass("testit-tst-name").attr("title", label).text(label));
        var where = $.grep([test.section, test.suite ? "набор «" + test.suite + "»" : null], Boolean).join(" · ");
        if (where) {
            $main.append($("<div>").addClass("testit-tst-where").text(where));
        }
        // Configuration and assignee sit under the title so the name keeps the widest column.
        var who = $.grep([test.configuration, test.tester], Boolean).join(" · ");
        if (who) {
            $main.append($("<div>").addClass("testit-tst-who").attr("title", who).text(who));
        }

        var $status = $("<div>").addClass("testit-tst-col").attr("data-label", "Статус").append(
            $("<span>").addClass("testit-tst-status").attr("title", test.status || "")
                .css({ color: color, borderColor: color })
                .append($("<span>").addClass("testit-tp-dot").css("backgroundColor", color),
                    document.createTextNode(test.status || "—")));

        var $defects = $("<div>").addClass("testit-tst-col testit-tst-col-defects").attr("data-label", "Дефекты");
        var shown = 0;
        $.each(test.links || [], function (_, link) {
            // A defect attached to a result always counts; a plain link only when Jira says it is a bug.
            if (!(link.defect || (link.key && bugKeys[link.key]))) {
                return;
            }
            var $chip = safeHttpUrl(link.url)
                ? $("<a>").attr({ href: link.url, target: "_blank", rel: "noopener noreferrer" })
                : $("<span>");
            var text = link.key || "Дефект";
            $defects.append($chip.addClass("testit-tst-defect").attr("title", "Дефект " + (link.key || link.url || "")).text(text));
            shown++;
        });
        if (!shown) {
            $defects.append($("<span>").addClass("testit-attr-empty").text("—"));
        }
        return $("<div>").addClass("testit-tst-row").append($main, $status, $defects);
    }

    // The tests tab. Returns { $content, $badge, setFilter(label), refresh() }.
    function renderTestsTab(plan, statuses, total, getBugKeys) {
        var planKey = planKeyOf(plan);
        var tests = plan.points || [];
        var colors = {};
        $.each(statuses, function (_, st) {
            colors[st.label] = st.color;
        });
        colors[TESTS_OTHER] = "#6b778c";

        var byStatus = {};
        $.each(tests, function (_, test) {
            var key = statusKeyForTest(test, statuses);
            test._statusKey = key;
            byStatus[key] = (byStatus[key] || 0) + 1;
        });

        var $content = $("<div>").addClass("testit-tests");
        var $chips = $("<div>").addClass("testit-tst-chips");
        var $search = $("<input>").attr({ type: "search", placeholder: "Поиск по названию", "aria-label": "Поиск по названию" })
            .addClass("testit-tst-search");
        var $toolbar = $("<div>").addClass("testit-tst-toolbar").append($chips, $search);
        var $header = $("<div>").addClass("testit-tst-row testit-tst-header").append(
            $("<div>").addClass("testit-tst-col").text("Тест"),
            $("<div>").addClass("testit-tst-col").text("Статус"),
            $("<div>").addClass("testit-tst-col").text("Дефекты"));
        var $list = $("<div>").addClass("testit-tst-list");
        // The toolbar and the column titles stay in view while the list scrolls.
        $content.append($("<div>").addClass("testit-tst-sticky").append($toolbar, $header), $list);

        // The remembered filter is only valid while its status still has tests (a refresh can change that).
        function currentFilter() {
            var f = activeTestFilters[planKey] || "all";
            var known = f === "all" || (f === TESTS_OTHER && !!byStatus[TESTS_OTHER]);
            $.each(statuses, function (_, st) {
                if (st.label === f && st.count > 0) {
                    known = true;
                }
            });
            return known ? f : "all";
        }

        function renderChips() {
            var filter = currentFilter();
            $chips.empty();
            function chip(key, label, count, color) {
                var $b = $("<button>").attr({ type: "button", "data-status": key })
                    .addClass("testit-tst-chip" + (key === filter ? " testit-tst-chip-active" : ""))
                    .on("click", function (e) {
                        e.preventDefault();
                        activeTestFilters[planKey] = key;
                        refresh();
                    });
                if (color) {
                    $b.append($("<span>").addClass("testit-tp-dot").css("backgroundColor", color));
                }
                $b.append($("<span>").text(label), $("<span>").addClass("testit-tst-chip-count").text(count));
                $chips.append($b);
            }
            chip("all", "Все", total, null);
            $.each(statuses, function (_, st) {
                if (st.count > 0) {
                    chip(st.label, st.label, st.count, st.color);
                }
            });
            if (byStatus[TESTS_OTHER]) {
                chip(TESTS_OTHER, "Прочие", byStatus[TESTS_OTHER], colors[TESTS_OTHER]);
            }
        }

        function renderList() {
            var filter = currentFilter();
            var query = $.trim($search.val()).toLowerCase();
            var bugKeys = getBugKeys();
            var visible = $.grep(tests, function (test) {
                if (filter !== "all" && test._statusKey !== filter) {
                    return false;
                }
                return !query || String(test.name || "").toLowerCase().indexOf(query) >= 0;
            });
            $list.empty();
            if (!tests.length && total > 0) {
                // Test IT did not return the tests (an error or no access): say so instead of an empty list.
                var $failed = $("<div>").addClass("testit-tst-none description")
                    .text("Не удалось загрузить список тестов. Обновите тест-планы или откройте план в Test IT.");
                if (safeHttpUrl(plan.url)) {
                    $failed.append(document.createTextNode(" "),
                        $("<a>").attr({ href: plan.url, target: "_blank", rel: "noopener noreferrer" }).text("Открыть"));
                }
                $list.append($failed);
                return;
            }
            if (!visible.length) {
                $list.append($("<div>").addClass("testit-tst-none description").text(
                    query ? "Ничего не найдено" : "Нет тестов с таким статусом"));
                return;
            }
            // Thousands of rows would make the page sluggish: the rest is revealed on demand.
            var shownRows = Math.min(visible.length, TESTS_PAGE);
            $.each(visible.slice(0, shownRows), function (_, test) {
                $list.append(renderTestRow(test, colors[test._statusKey] || "#6b778c", bugKeys));
            });
            if (visible.length > shownRows) {
                var $more = $("<button>").attr("type", "button").addClass("testit-tst-more")
                    .text("Показать ещё " + Math.min(TESTS_PAGE, visible.length - shownRows) + " из " + (visible.length - shownRows));
                $more.on("click", function (e) {
                    e.preventDefault();
                    var next = visible.slice(shownRows, shownRows + TESTS_PAGE);
                    $.each(next, function (_, test) {
                        $more.before(renderTestRow(test, colors[test._statusKey] || "#6b778c", bugKeys));
                    });
                    shownRows += next.length;
                    if (shownRows >= visible.length) {
                        $more.remove();
                    } else {
                        $more.text("Показать ещё " + Math.min(TESTS_PAGE, visible.length - shownRows) + " из " + (visible.length - shownRows));
                    }
                });
                $list.append($more);
            }
            var shownOfStatus = filter === "all" ? tests.length : (byStatus[filter] || 0);
            var wanted = 0;
            $.each(statuses, function (_, st) {
                if (filter === "all" || st.label === filter) {
                    wanted += st.count;
                }
            });
            if (plan.pointsTruncated && !query && shownOfStatus < wanted) {
                var $note = $("<div>").addClass("testit-tst-note").text(
                    "Показано " + shownOfStatus + " из " + wanted + ". Остальные тесты — в Test IT: ");
                if (safeHttpUrl(plan.url)) {
                    $note.append($("<a>").attr({ href: plan.url, target: "_blank", rel: "noopener noreferrer" })
                        .text("открыть тест-план"));
                }
                $list.append($note);
            }
        }

        function refresh() {
            renderChips();
            renderList();
        }

        $search.on("input", renderList);
        refresh();

        var $badge = $("<span>").addClass("testit-tab-badge").text(total);
        return {
            $content: $content,
            $badge: $badge,
            setFilter: function (label) {
                activeTestFilters[planKey] = label;
                $search.val("");
                refresh();
            },
            refresh: refresh
        };
    }

    // ---- plan card with tabs ----

    // All panes sit in the same grid cell, so the card keeps one height whichever tab is selected.
    function renderTabs(plan, panes) {
        var $tabs = $("<div>").addClass("testit-tp-tabs").attr("role", "tablist");
        var planKey = planKeyOf(plan);
        var $panes = $("<div>").addClass("testit-tp-panes");
        var current = activeTabs[planKey];
        var known = false;
        $.each(panes, function (_, pane) {
            if (pane.key === current) {
                known = true;
            }
        });
        if (!known) {
            current = panes[0].key;
        }

        function select(key, focus) {
            activeTabs[planKey] = key;
            $tabs.children().each(function () {
                var on = $(this).attr("data-tab") === key;
                $(this).toggleClass("testit-tp-tab-active", on).attr({
                    "aria-selected": on ? "true" : "false",
                    tabindex: on ? "0" : "-1"
                });
                if (on && focus) {
                    this.focus();
                }
            });
            $panes.children().each(function () {
                var on = $(this).attr("data-pane") === key;
                $(this).toggleClass("testit-tp-pane-hidden", !on).attr("aria-hidden", on ? "false" : "true");
            });
        }

        $.each(panes, function (index, pane) {
            var $tab = $("<button>").attr({ type: "button", role: "tab", "data-tab": pane.key })
                .addClass("testit-tp-tab").append($("<span>").text(pane.title));
            if (pane.$badge) {
                $tab.append(pane.$badge);
            }
            $tab.on("click", function (e) {
                e.preventDefault();
                select(pane.key, false);
            }).on("keydown", function (e) {
                var step = e.key === "ArrowRight" ? 1 : (e.key === "ArrowLeft" ? -1 : 0);
                if (step) {
                    e.preventDefault();
                    select(panes[(index + step + panes.length) % panes.length].key, true);
                }
            });
            $tabs.append($tab);
            $panes.append($("<div>").addClass("testit-tp-pane").attr({ "data-pane": pane.key, role: "tabpanel" })
                .append(pane.$content));
        });
        select(current, false);
        var $tabbed = $("<div>").addClass("testit-tp-tabbed").append($tabs, $panes);
        $tabbed.data("select", select);
        return $tabbed;
    }

    function renderPlanCard(plan) {
        var tests = plan.tests || null;
        var total = tests && tests.total != null ? tests.total : 0;
        var statuses = normalizeStatuses(tests);
        // Status counts can add up to more than the reported total (for example while results are being
        // re-run); never let the charts exceed 100%.
        var counted = 0;
        $.each(statuses, function (_, st) {
            counted += st.count;
        });
        total = Math.max(total, counted);
        var passed = 0;
        var executed = 0;
        $.each(statuses, function (_, st) {
            if (st.type === "Succeeded") {
                passed += st.count;
            }
            if (st.type !== "Pending" && st.type !== "InProgress") {
                executed += st.count;
            }
        });
        var passPercent = percent(passed, total);

        // Clicking a status in the legend, the bar or the donut opens the tests tab filtered by that status.
        var tabbed = null;
        var testsTab = null;
        function openStatus(label) {
            if (testsTab && tabbed) {
                testsTab.setFilter(label);
                tabbed.data("select")("tests", false);
            }
        }

        var $title = $("<div>").addClass("testit-tp-title");
        var $name;
        if (plan.url) {
            $name = $("<a>").attr({ href: plan.url, target: "_blank", rel: "noopener noreferrer" })
                .text(formatPlanLabel(plan));
        } else {
            $name = $("<span>").text(formatPlanLabel(plan));
        }
        $title.append($name.addClass("testit-tp-name"));
        if (plan.globalId != null) {
            $title.append($("<span>").addClass("testit-tp-id").text("#" + plan.globalId));
        }
        $title.append($("<span>").addClass(statusLozengeClass(plan.statusType) + " testit-tp-status")
            .text(plan.statusLabel || "—"));

        var $meta = $("<div>").addClass("testit-tp-meta");
        $meta.append(metaChip("Начало", plan.startDate || "—"));
        $meta.append(metaChip("Окончание", plan.endDate || "—"));
        if (plan.build) {
            $meta.append(metaChip("Версия", plan.build));
        }
        if (plan.productName) {
            $meta.append(metaChip("Продукт", plan.productName));
        }

        var $summary = $("<div>").addClass("testit-tp-summary");
        if (total > 0) {
            $summary.append(
                $("<span>").addClass("testit-tp-summary-main").text("Всего тестов: " + total),
                $("<span>").addClass("testit-tp-summary-sub").text(
                    "выполнено " + executed + " из " + total + " (" + formatPercent(percent(executed, total)) + ")"
                )
            );
        }
        var $info = $("<div>").addClass("testit-tp-info");
        if (total > 0) {
            $info.append($summary, renderStackedBar(statuses, total, openStatus), renderLegend(statuses, total, openStatus));
        } else {
            $info.append($("<div>").addClass("description").text("В тест-плане нет тестов"));
        }
        var $overview = $("<div>").addClass("testit-tp-overview")
            .append(renderDonut(statuses, total, passPercent, openStatus), $info);

        // All custom attributes live on the overview tab, under the charts.
        var $attributes = renderAttributes(plan.attributes);
        if ($attributes) {
            $overview.append($attributes);
        }

        var bugKeys = {};
        testsTab = renderTestsTab(plan, statuses, total, function () {
            return bugKeys;
        });
        var panes = [
            { key: "overview", title: "Обзор", $content: $overview },
            { key: "tests", title: "Тесты", $badge: testsTab.$badge, $content: testsTab.$content }
        ];
        var $bugsBadge = $("<span>").addClass("testit-tab-badge testit-tab-badge-loading").text("…");
        var $bugsPane = $("<div>").addClass("testit-bugs");
        panes.push({ key: "bugs", title: "Дефекты", $badge: $bugsBadge, $content: $bugsPane });
        renderBugsPane($bugsPane, $bugsBadge, plan, function (bugs) {
            $.each(bugs, function (_, vm) {
                if (vm.key) {
                    bugKeys[vm.key] = true;
                }
            });
            testsTab.refresh();
        });

        // The bugs list scrolls inside a box as tall as the overview (at least BUGS_MIN_HEIGHT), so the
        // card keeps its size when the tab changes and a long list never stretches the page.
        if (window.ResizeObserver) {
            new ResizeObserver(function () {
                var height = $overview[0].offsetHeight;
                if (height > 0) {
                    var limit = Math.min(BUGS_MAX_HEIGHT, Math.max(BUGS_MIN_HEIGHT, height)) + "px";
                    $bugsPane.css("max-height", limit);
                    testsTab.$content.css("max-height", limit);
                }
            }).observe($overview[0]);
        }

        var $card = $("<div>").addClass("testit-tp-card");
        if (plan.url) {
            $card.append(
                $("<button>")
                    .attr({
                        type: "button",
                        title: "Отвязать",
                        "aria-label": "Отвязать",
                        "data-plan-url": plan.url
                    })
                    .addClass("testit-unlink-x testit-testplan-unlink-btn")
                    .append(
                        $("<span>").addClass("aui-icon aui-icon-small aui-iconfont-close-dialog")
                            .attr("aria-hidden", "true")
                    )
            );
        }
        tabbed = renderTabs(plan, panes);
        $card.append($("<div>").addClass("testit-tp-head").append($title, $meta), tabbed);
        return $card;
    }

    function renderTable(testPlans) {
        if (!testPlans || !testPlans.length) {
            return $("<div>").addClass("description").text("Нет связанных тест-планов");
        }
        var $list = $("<div>").addClass("testit-tp-cards");
        $.each(testPlans, function (_, plan) {
            $list.append(renderPlanCard(plan));
        });
        return $list;
    }

    function renderData(data) {
        var $content = $("#testit-testplans-content").empty();
        $content.append(renderTable(data && data.testPlans ? data.testPlans : []));
    }

    function showError(message) {
        $("#testit-testplans-loading").hide();
        $("#testit-testplans-content").hide();
        $("#testit-testplans-error").text(message || "Error").show();
    }

    function showContent() {
        $("#testit-testplans-loading").hide();
        $("#testit-testplans-error").hide().text("");
        $("#testit-testplans-content").show();
    }

    function loadTestPlans(issueKey, options) {
        options = options || {};
        if (!issueKey) {
            showError("Cannot determine issue key");
            return;
        }
        if (activeRequest) {
            activeRequest.abort();
            activeRequest = null;
        }
        var seq = ++loadSeq;
        if (!options.background) {
            $("#testit-testplans-loading").show();
            $("#testit-testplans-content").hide();
            $("#testit-testplans-error").hide().text("");
        }
        var request = $.ajax({
            url: restBase() + encodeURIComponent(issueKey) + "/testplans",
            dataType: "json",
            timeout: 45000
        });
        activeRequest = request;
        request.done(function (data) {
            if (seq !== loadSeq) {
                return;
            }
            if (data && data.issueKey && !issueKeysEqual(data.issueKey, issueKey)) {
                return;
            }
            if (!issueKeysEqual(currentIssueKey, issueKey)) {
                return;
            }
            renderData(data);
            showContent();
        }).fail(function (jqXHR, textStatus, errorThrown) {
            if (seq !== loadSeq || textStatus === "abort") {
                return;
            }
            if (!options.background) {
                showError(formatAjaxError(jqXHR, textStatus, errorThrown));
            }
        }).always(function () {
            if (activeRequest === request) {
                activeRequest = null;
            }
        });
    }

    function addTestPlan(issueKey) {
        var url = $.trim($("#testit-testplan-url").val());
        if (!url) {
            showError("Введите URL тест-плана");
            return;
        }
        if (!issueKey) {
            showError("Cannot determine issue key");
            return;
        }
        var $btn = $(".testit-testplan-add-btn").prop("disabled", true);
        $.ajax({
            url: restBase() + encodeURIComponent(issueKey) + "/testplans",
            method: "POST",
            contentType: "application/json",
            data: JSON.stringify({ url: url }),
            dataType: "json",
            timeout: 45000
        }).done(function () {
            $("#testit-testplan-url").val("");
            loadTestPlans(issueKey);
        }).fail(function (jqXHR, textStatus, errorThrown) {
            showError(formatAjaxError(jqXHR, textStatus, errorThrown));
        }).always(function () {
            $btn.prop("disabled", false);
        });
    }

    function unlinkTestPlan(issueKey, planUrl, $btn) {
        if (!issueKey || !planUrl) {
            return;
        }
        $btn.prop("disabled", true);
        $.ajax({
            url: restBase() + encodeURIComponent(issueKey) + "/testplans",
            method: "DELETE",
            contentType: "application/json",
            data: JSON.stringify({ url: planUrl }),
            dataType: "json",
            timeout: 45000
        }).done(function () {
            loadTestPlans(issueKey);
        }).fail(function (jqXHR, textStatus, errorThrown) {
            showError(formatAjaxError(jqXHR, textStatus, errorThrown));
            $btn.prop("disabled", false);
        });
    }

    function bindEvents() {
        if (eventsBound) {
            return;
        }
        eventsBound = true;
        $(document).on("click", "#testit-testplans-panel .testit-testplan-add-btn", function (e) {
            e.preventDefault();
            addTestPlan(currentIssueKey);
        });
        $(document).on("keydown", "#testit-testplan-url", function (e) {
            if (e.key === "Enter") {
                e.preventDefault();
                addTestPlan(currentIssueKey);
            }
        });
        $(document).on("click", "#testit-testplans-panel .testit-testplans-refresh-btn", function (e) {
            e.preventDefault();
            loadTestPlans(currentIssueKey);
        });
        $(document).on("click", "#testit-testplans-panel .testit-testplan-unlink-btn", function (e) {
            e.preventDefault();
            var $btn = $(this);
            unlinkTestPlan(currentIssueKey, $btn.attr("data-plan-url"), $btn);
        });
    }

    function boot(issueKey) {
        var $panel = $("#testit-testplans-panel");
        var key = resolveIssueKey($panel, issueKey);
        if (key) {
            currentIssueKey = key;
            if ($panel.length) {
                $panel.attr("data-issue-key", key);
            }
        }
        bindEvents();
        loadTestPlans(key);
    }

    window.TestITTestPlansPanel = {
        boot: boot,
        loadTestPlans: loadTestPlans
    };

    $(document).ready(function () {
        var $panel = $("#testit-testplans-panel");
        if ($panel.length) {
            boot(resolveIssueKey($panel));
        }
    });

    if (window.JIRA && JIRA.Events && typeof JIRA.bind === "function") {
        JIRA.bind(JIRA.Events.NEW_CONTENT_ADDED, function () {
            var $panel = $("#testit-testplans-panel");
            if (!$panel.length) {
                return;
            }
            var key = resolveIssueKey($panel);
            if (key && key.toUpperCase() !== (currentIssueKey || "").toUpperCase()) {
                boot(key);
            }
        });
    }
})(AJS.$ || jQuery);
