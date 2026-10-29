(function ($) {
    var currentIssueKey = null;
    var eventsBound = false;
    var activeContextRequest = null;
    var contextLoadSeq = 0;

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

    function resolveIssueKeyFromUrl() {
        return TestITCommon.resolveIssueKeyFromUrl();
    }

    function resolveIssueKey($panel, explicitKey) {
        return TestITCommon.resolveIssueKey({ mode: "panel", explicitKey: explicitKey, $element: $panel });
    }

    // Maps a result status label (English or Russian) to a colour key.
    function statusKey(statusLabel) {
        var upper = String(statusLabel || "").toUpperCase();
        if (/^(PASSED|SUCCEEDED|УСПЕШЕН|УСПЕШНО|ПРОЙДЕН)/.test(upper)) {
            return "pass";
        }
        if (/^(FAILED|ПРОВАЛЕН|ПРОВАЛ)/.test(upper)) {
            return "fail";
        }
        if (/^(SKIPPED|ПРОПУЩЕН)/.test(upper)) {
            return "skip";
        }
        if (/^(BLOCKED|ЗАБЛОКИРОВАН)/.test(upper)) {
            return "blocked";
        }
        if (/^(IN ?PROGRESS|В ПРОЦЕССЕ)/.test(upper)) {
            return "progress";
        }
        return "";
    }

    function statusLozengeClass(statusLabel) {
        var key = statusKey(statusLabel);
        return key ? "aui-lozenge testit-st testit-st-" + key : "aui-lozenge";
    }

    // Order-independent summary of a work item's results: one coloured badge per status.
    function renderResultsSummary(results) {
        var $summary = $("<span>").addClass("testit-results-summary");
        if (!results || !results.length) {
            return $summary;
        }
        var counts = {};
        var order = [];
        $.each(results, function (_, result) {
            var key = statusKey(result.statusLabel) || "other";
            if (!counts[key]) {
                counts[key] = { n: 0, label: result.statusLabel || "—" };
                order.push(key);
            }
            counts[key].n++;
        });
        $.each(["pass", "fail", "skip", "blocked", "progress", "other"], function (_, key) {
            if (!counts[key]) {
                return;
            }
            $summary.append(
                $("<span>").addClass("testit-results-badge testit-st-" + key)
                    .attr("title", counts[key].label + ": " + counts[key].n)
                    .text(counts[key].n)
            );
        });
        return $summary.attr("title", "Результатов: " + results.length);
    }

    function formatTestCaseLabel(testCase) {
        if (testCase.name) {
            return testCase.name;
        }
        return "Test case " + testCase.id;
    }

    function renderResults(results) {
        if (!results || !results.length) {
            return $("<div>").addClass("description").css({ marginTop: "4px" }).text("No test results yet");
        }
        var $table = $("<table>").addClass("aui testit-results-table").css({ marginTop: "6px" });
        var $colgroup = $("<colgroup>").append(
            $("<col>").addClass("testit-col-date"),
            $("<col>").addClass("testit-col-plan"),
            $("<col>").addClass("testit-col-status")
        );
        var $thead = $("<thead>").append(
            $("<tr>").append(
                $("<th>").text("Date"),
                $("<th>").text("Test plan"),
                $("<th>").text("Status")
            )
        );
        var $tbody = $("<tbody>");
        $.each(results, function (_, result) {
            var planUrl = result.testPlanUrl || result.testRunUrl;
            var planName = result.testPlanName || result.testRunName;
            var $planCell = $("<td>").addClass("testit-col-plan-cell");
            if (planUrl) {
                $planCell.append(
                    $("<a>").attr({ href: planUrl, target: "_blank", rel: "noopener noreferrer" })
                        .text(planName || "Test plan")
                );
            } else {
                $planCell.text(planName || "—");
            }
            $tbody.append(
                $("<tr>").append(
                    $("<td>").addClass("testit-col-date-cell").text(result.date || "—"),
                    $planCell,
                    $("<td>").addClass("testit-col-status-cell").append(
                        $("<span>").addClass(statusLozengeClass(result.statusLabel)).text(result.statusLabel || "—")
                    )
                )
            );
        });
        $table.append($colgroup).append($thead).append($tbody);
        return $("<div>").addClass("testit-table-scroll").append($table);
    }

    function renderTestCaseTitle(testCase) {
        var $title = $("<span>").addClass("testit-testcase-title");
        if (testCase.url) {
            $title.append(
                $("<a>").attr({ href: testCase.url, target: "_blank", rel: "noopener noreferrer" })
                    .text(formatTestCaseLabel(testCase))
            );
        } else {
            $title.append($("<strong>").text(formatTestCaseLabel(testCase)));
        }
        return $title;
    }

    function renderUnlinkButton(testCase) {
        return $("<button>")
            .attr({
                type: "button",
                title: "Отвязать",
                "aria-label": "Отвязать",
                "data-workitem-id": testCase.id || ""
            })
            .addClass("testit-unlink-x testit-testcase-unlink-btn")
            .append(
                $("<span>").addClass("aui-icon aui-icon-small aui-iconfont-close-dialog").attr("aria-hidden", "true")
            );
    }

    function renderTestCaseBlock(testCase) {
        var $toggle = $("<span>").addClass("testit-testcase-toggle")
            .attr({ role: "button", "aria-expanded": "false", tabindex: "0", title: "Expand/collapse test results" })
            .append(
                $("<span>").addClass("aui-icon aui-icon-small aui-iconfont-chevron-right testit-testcase-chevron")
            );
        
        var $header = $("<div>").addClass("testit-testcase-header");
        
        // Добавляем кнопку "отвязать" первой
        if (testCase.id) {
            $header.append(renderUnlinkButton(testCase));
        }
        
        // Затем добавляем toggle
        $header.append($toggle);
        
        // И в конце заголовок
        $header.append(renderTestCaseTitle(testCase));
        $header.append(renderResultsSummary(testCase.results));
        
        var $body = $("<div>").addClass("testit-testcase-body").append(renderResults(testCase.results));
        return $("<div>").addClass("testit-testcase-wrap testit-testcase-collapsed")
            .append($header)
            .append($body);
    }

    function toggleTestCaseBlock($wrap) {
        var expanded = $wrap.toggleClass("testit-testcase-collapsed").hasClass("testit-testcase-collapsed") === false;
        $wrap.find(".testit-testcase-toggle").attr("aria-expanded", expanded ? "true" : "false");
    }

    function renderContext(context) {
        var $content = $("#testit-issue-panel-content").empty();
        var testCases = context && context.testCases ? context.testCases : [];
        if (!testCases.length) {
            $content.append($("<div>").addClass("description").text("No linked Test IT test cases"));
            return;
        }
        $.each(testCases, function (_, testCase) {
            $content.append(renderTestCaseBlock(testCase));
        });
    }

    function renderOptimisticTestCase(data) {
        if (!data || (!data.url && data.globalId == null)) {
            return;
        }
        renderContext({
            testCases: [{
                globalId: data.globalId,
                name: data.name,
                url: data.url,
                results: []
            }]
        });
        $("#testit-issue-panel-loading").hide();
        $("#testit-issue-panel-content").show();
        $("#testit-issue-panel-error").hide().text("");
    }

    function loadIssueContext(issueKey, options) {
        options = options || {};
        var background = !!options.background;
        if (!issueKey) {
            $("#testit-issue-panel-loading").hide();
            $("#testit-issue-panel-error").text("Cannot determine issue key").show();
            return;
        }
        if (activeContextRequest) {
            activeContextRequest.abort();
            activeContextRequest = null;
        }
        var seq = ++contextLoadSeq;
        if (!background) {
            $("#testit-issue-panel-loading").show();
            $("#testit-issue-panel-content").hide();
            $("#testit-issue-panel-error").hide().text("");
        }
        var request = $.ajax({
            url: restBase() + encodeURIComponent(issueKey) + "/context",
            dataType: "json",
            timeout: 45000
        });
        activeContextRequest = request;
        request.done(function (context) {
            if (seq !== contextLoadSeq) {
                return;
            }
            if (context && context.issueKey && !issueKeysEqual(context.issueKey, issueKey)) {
                return;
            }
            if (!issueKeysEqual(currentIssueKey, issueKey)) {
                return;
            }
            renderContext(context);
            $("#testit-issue-panel-loading").hide();
            $("#testit-issue-panel-content").show();
            $("#testit-issue-panel-error").hide().text("");
        }).fail(function (jqXHR, textStatus, errorThrown) {
            if (seq !== contextLoadSeq || textStatus === "abort") {
                return;
            }
            if (!background) {
                $("#testit-issue-panel-loading").hide();
                $("#testit-issue-panel-error").text(formatAjaxError(jqXHR, textStatus, errorThrown)).show();
            }
        }).always(function () {
            if (activeContextRequest === request) {
                activeContextRequest = null;
            }
        });
    }

    function unlinkWorkItem(issueKey, workItemId, $btn) {
        if (!issueKey || !workItemId) {
            return;
        }
        $btn.prop("disabled", true);
        $.ajax({
            url: restBase() + encodeURIComponent(issueKey) + "/workitems/" + encodeURIComponent(workItemId),
            method: "DELETE",
            dataType: "json",
            timeout: 45000
        }).done(function () {
            loadIssueContext(issueKey);
        }).fail(function (jqXHR, textStatus, errorThrown) {
            $("#testit-issue-panel-error").text(formatAjaxError(jqXHR, textStatus, errorThrown)).show();
            $btn.prop("disabled", false);
        });
    }

    function bindEvents() {
        if (eventsBound) {
            return;
        }
        eventsBound = true;
        $(document).on("click", "#testit-issue-panel .testit-testcase-toggle", function (e) {
            e.preventDefault();
            e.stopPropagation();
            toggleTestCaseBlock($(this).closest(".testit-testcase-wrap"));
        });
        $(document).on("keydown", "#testit-issue-panel .testit-testcase-toggle", function (e) {
            if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                toggleTestCaseBlock($(this).closest(".testit-testcase-wrap"));
            }
        });
        $(document).on("click", "#testit-issue-panel .testit-testcase-unlink-btn", function (e) {
            e.preventDefault();
            e.stopPropagation();
            var $btn = $(this);
            unlinkWorkItem(currentIssueKey, $btn.attr("data-workitem-id"), $btn);
        });
        $(document).on("testit:workitem-created", function (_event, data) {
            var eventKey = data && data.issueKey ? data.issueKey : null;
            if (!eventKey || !currentIssueKey || eventKey.toUpperCase() === currentIssueKey.toUpperCase()) {
                if (data && data.url) {
                    renderOptimisticTestCase(data);
                }
                loadIssueContext(currentIssueKey || eventKey, { background: true });
            }
        });
    }

    function boot(issueKey) {
        var $panel = $("#testit-issue-panel");
        var key = resolveIssueKey($panel, issueKey);
        if (key) {
            currentIssueKey = key;
            if ($panel.length) {
                $panel.attr("data-issue-key", key);
            }
        }
        bindEvents();
        loadIssueContext(key);
    }

    window.TestITIssuePanel = {
        boot: boot,
        loadIssueContext: loadIssueContext
    };

    $(document).ready(function () {
        var $panel = $("#testit-issue-panel");
        if ($panel.length) {
            boot(resolveIssueKey($panel));
        }
    });

    if (window.JIRA && JIRA.Events && typeof JIRA.bind === "function") {
        JIRA.bind(JIRA.Events.NEW_CONTENT_ADDED, function () {
            var $panel = $("#testit-issue-panel");
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
