(function ($) {
    var configUrl = AJS.contextPath() + "/rest/testit-conf/1.0/";
    var projectsUrl = configUrl + "projects";
    var statusUrl = configUrl + "duplication/status";
    var tmsProjectsUrl = configUrl + "tms/projects";

    var jiraProjects = {};
    var tmsProjects = [];
    var mappingCounter = 0;
    var statusTimer = null;

    function formatAjaxError(jqXHR, textStatus, errorThrown) {
        var httpStatus = jqXHR && jqXHR.status ? jqXHR.status : null;
        var body = "";
        if (jqXHR && jqXHR.responseText) {
            try {
                var parsed = JSON.parse(jqXHR.responseText);
                body = parsed.message || parsed.error || jqXHR.responseText;
            } catch (e) {
                body = jqXHR.responseText;
            }
        } else if (jqXHR && jqXHR.responseJSON) {
            body = jqXHR.responseJSON.message || jqXHR.responseJSON.error || "";
        }
        if (!body) {
            body = textStatus || errorThrown || "Unknown error";
        }
        if (body.length > 500) {
            body = body.substring(0, 500) + "...";
        }
        return "Error" + (httpStatus ? " " + httpStatus : "") + ": " + body;
    }

    function formatDuration(ms) {
        if (ms == null || ms < 0) {
            return "—";
        }
        var totalSec = Math.floor(ms / 1000);
        var h = Math.floor(totalSec / 3600);
        var m = Math.floor((totalSec % 3600) / 60);
        var s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    function formatIso(iso) {
        if (!iso) {
            return "—";
        }
        try {
            return new Date(iso).toLocaleString();
        } catch (e) {
            return iso;
        }
    }

    function uuid() {
        if (window.crypto && crypto.randomUUID) {
            return crypto.randomUUID();
        }
        return "m-" + Date.now() + "-" + Math.floor(Math.random() * 1e9);
    }

    function jiraProjectOptionsHtml(selectedKey) {
        var html = '<option value="">— select —</option>';
        $.each(jiraProjects, function (key, name) {
            var sel = key === selectedKey ? " selected" : "";
            html += '<option value="' + escapeAttr(key) + '"' + sel + ">"
                + escapeHtml(name) + " (" + escapeHtml(key) + ")</option>";
        });
        return html;
    }

    function tmsProjectOptionsHtml(selectedId) {
        var html = '<option value="">— select —</option>';
        var found = false;
        $.each(tmsProjects, function (_, p) {
            var sel = p.id === selectedId ? " selected" : "";
            if (sel) {
                found = true;
            }
            var label = (p.name || p.id) + (p.globalId != null ? " [" + p.globalId + "]" : "");
            html += '<option value="' + escapeAttr(p.id) + '"' + sel + ' data-name="'
                + escapeAttr(p.name || "") + '">' + escapeHtml(label) + "</option>";
        });
        if (selectedId && !found) {
            html += '<option value="' + escapeAttr(selectedId) + '" selected>'
                + escapeHtml(selectedId) + " (saved)</option>";
        }
        return html;
    }

    function escapeHtml(value) {
        return String(value == null ? "" : value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function escapeAttr(value) {
        return escapeHtml(value).replace(/'/g, "&#39;");
    }

    function addMappingRow(mapping) {
        mapping = mapping || {};
        var rowId = "dup-row-" + (++mappingCounter);
        var id = mapping.id || uuid();
        var mode = (mapping.scheduleMode || "INTERVAL").toUpperCase();
        var interval = mapping.intervalMinutes || 60;
        var dailyAt = mapping.dailyAt || "02:00";
        var enabled = mapping.enabled === true;

        var $row = $('<div class="dup-row" data-mapping-id="' + escapeAttr(id) + '" id="' + rowId + '"></div>');
        $row.append(
            '<div class="field-group">'
            + '<label for="' + rowId + '-enabled">Enabled</label>'
            + '<input type="checkbox" class="dup-enabled" id="' + rowId + '-enabled"'
            + (enabled ? " checked" : "") + ">"
            + "</div>"
        );
        $row.append(
            '<div class="field-group">'
            + '<label>Jira project</label>'
            + '<select class="select dup-jira-project" name="dup-jira">' + jiraProjectOptionsHtml(mapping.jiraProjectKey || "") + "</select>"
            + "</div>"
        );
        $row.append(
            '<div class="field-group">'
            + '<label>Test IT project</label>'
            + '<select class="select dup-tms-project" name="dup-tms">' + tmsProjectOptionsHtml(mapping.tmsProjectId || "") + "</select>"
            + "</div>"
        );
        $row.append(
            '<div class="field-group">'
            + "<label>Schedule</label>"
            + '<select class="select dup-schedule-mode">'
            + '<option value="INTERVAL"' + (mode === "INTERVAL" ? " selected" : "") + ">Every N minutes</option>"
            + '<option value="DAILY"' + (mode === "DAILY" ? " selected" : "") + ">Daily at time</option>"
            + "</select>"
            + "</div>"
        );
        $row.append(
            '<div class="field-group dup-interval-group">'
            + "<label>Interval (minutes)</label>"
            + '<select class="select dup-interval">'
            + [15, 30, 60, 180, 360, 1440].map(function (v) {
                return '<option value="' + v + '"' + (v === interval ? " selected" : "") + ">" + v + "</option>";
            }).join("")
            + "</select>"
            + "</div>"
        );
        $row.append(
            '<div class="field-group dup-daily-group">'
            + '<label>Daily at (server time)</label>'
            + '<input type="time" class="text dup-daily-at" value="' + escapeAttr(dailyAt) + '">'
            + "</div>"
        );
        $row.append(
            '<div class="field-group">'
            + '<button type="button" class="aui-button aui-button-link dup-remove">Remove</button>'
            + "</div>"
        );
        $row.append('<div class="dup-stats">No sync status yet.</div>');

        $("#duplication-mappings").append($row);
        toggleScheduleFields($row);
        $row.find(".dup-schedule-mode").on("change", function () {
            toggleScheduleFields($row);
        });
        $row.find(".dup-remove").on("click", function () {
            $row.remove();
        });
    }

    function toggleScheduleFields($row) {
        var mode = $row.find(".dup-schedule-mode").val();
        $row.find(".dup-interval-group").toggle(mode === "INTERVAL");
        $row.find(".dup-daily-group").toggle(mode === "DAILY");
    }

    function collectDuplication() {
        var mappings = [];
        var pairs = {};
        $("#duplication-mappings .dup-row").each(function () {
            var $row = $(this);
            var jiraKey = ($row.find(".dup-jira-project").val() || "").trim();
            var $tms = $row.find(".dup-tms-project");
            var tmsId = ($tms.val() || "").trim();
            var tmsName = ($tms.find("option:selected").data("name") || $tms.find("option:selected").text() || "").toString();
            if (!jiraKey || !tmsId) {
                throw new Error("Each mapping requires Jira project and Test IT project");
            }
            var pair = jiraKey.toUpperCase() + "|" + tmsId.toLowerCase();
            if (pairs[pair]) {
                throw new Error("Duplicate mapping pair: " + jiraKey + " → " + tmsId);
            }
            pairs[pair] = true;
            mappings.push({
                id: $row.attr("data-mapping-id"),
                enabled: $row.find(".dup-enabled").is(":checked"),
                jiraProjectKey: jiraKey,
                tmsProjectId: tmsId,
                tmsProjectName: tmsName,
                scheduleMode: $row.find(".dup-schedule-mode").val(),
                intervalMinutes: parseInt($row.find(".dup-interval").val(), 10) || 60,
                dailyAt: $row.find(".dup-daily-at").val() || "02:00"
            });
        });
        return { mappings: mappings };
    }

    function renderStats(byMappingId) {
        byMappingId = byMappingId || {};
        $("#duplication-mappings .dup-row").each(function () {
            var $row = $(this);
            var id = $row.attr("data-mapping-id");
            var stats = byMappingId[id];
            var $box = $row.find(".dup-stats");
            if (!stats) {
                $box.text("No sync status yet.");
                return;
            }
            var lines = [
                "<strong>State:</strong> " + escapeHtml(stats.state || "idle"),
                "<strong>Last started:</strong> " + escapeHtml(formatIso(stats.lastStartedAt)),
                "<strong>Last finished:</strong> " + escapeHtml(formatIso(stats.lastFinishedAt)),
                "<strong>Last duration:</strong> " + escapeHtml(formatDuration(stats.lastDurationMs)),
                "<strong>Created / updated / already linked / errors:</strong> "
                    + (stats.createdCount || 0) + " / "
                    + (stats.updatedCount || 0) + " / "
                    + (stats.skippedAlreadyLinkedCount || 0) + " / "
                    + (stats.errorCount || 0)
            ];
            if (stats.lastError) {
                lines.push("<strong>Last error:</strong> " + escapeHtml(stats.lastError));
            }
            if (stats.lastSkippedAt) {
                lines.push("<strong>Last skip:</strong> " + escapeHtml(formatIso(stats.lastSkippedAt))
                    + " — " + escapeHtml(stats.lastSkipReason || ""));
            }
            $box.html(lines.join("<br>"));
        });
    }

    function refreshStatus() {
        $.ajax({ url: statusUrl, dataType: "json" })
            .done(function (body) {
                renderStats(body && body.byMappingId ? body.byMappingId : {});
            });
    }

    function startStatusPolling() {
        if (statusTimer) {
            clearInterval(statusTimer);
        }
        refreshStatus();
        statusTimer = setInterval(refreshStatus, 15000);
    }

    function loadTmsProjects() {
        $("#tms-project-loading").show();
        return $.ajax({
            url: tmsProjectsUrl,
            dataType: "json",
            data: { skip: 0, take: 100 }
        }).done(function (page) {
            tmsProjects = (page && page.items) ? page.items : [];
        }).fail(function () {
            tmsProjects = [];
        }).always(function () {
            $("#tms-project-loading").hide();
            $("#duplication-mappings .dup-tms-project").each(function () {
                var current = $(this).val();
                $(this).html(tmsProjectOptionsHtml(current));
            });
        });
    }

    function getProjects() {
        var result = [];
        $("#projects input[type='checkbox']:checked").each(function () {
            result.push($(this).val());
        });
        return result;
    }

    function setProjects(projects) {
        $.each(projects || [], function (index, value) {
            if (value) {
                $("#projects #" + value).prop("checked", true);
            }
        });
    }

    function parseRetryInt(selector, fallback, min, max) {
        var raw = $(selector).val();
        var n = parseInt(raw, 10);
        if (isNaN(n)) {
            n = fallback;
        }
        if (n < min) {
            n = min;
        }
        if (n > max) {
            n = max;
        }
        return n;
    }

    function syncRetryFieldsEnabled() {
        var on = $("#tmsRetryEnabled").is(":checked");
        $("#tmsRetryAttempts, #tmsRetryIntervalMs").prop("disabled", !on);
    }

    function applyConfig(config) {
        $("#url").val(config.url || "");
        $("#privateToken").val(config.privateToken || "");
        $("#tmsRetryEnabled").prop("checked", !!config.tmsRetryEnabled);
        $("#tmsRetryAttempts").val(
            config.tmsRetryAttempts != null ? config.tmsRetryAttempts : 3);
        $("#tmsRetryIntervalMs").val(
            config.tmsRetryIntervalMs != null ? config.tmsRetryIntervalMs : 2000);
        syncRetryFieldsEnabled();
        setProjects(config.projects || []);
        var dup = config.duplication || { mappings: [] };
        $("#duplication-mappings").empty();
        $.each(dup.mappings || [], function (_, mapping) {
            addMappingRow(mapping);
        });
        startStatusPolling();
    }

    function updateConfig() {
        var duplication;
        try {
            duplication = collectDuplication();
        } catch (e) {
            alert(e.message || String(e));
            return;
        }
        AJS.$.ajax({
            url: configUrl,
            type: "PUT",
            contentType: "application/json",
            data: JSON.stringify({
                url: AJS.$("#url").val(),
                privateToken: AJS.$("#privateToken").val(),
                tmsRetryEnabled: $("#tmsRetryEnabled").is(":checked"),
                tmsRetryAttempts: parseRetryInt("#tmsRetryAttempts", 3, 1, 10),
                tmsRetryIntervalMs: parseRetryInt("#tmsRetryIntervalMs", 2000, 0, 60000),
                projects: getProjects(),
                duplication: duplication
            }),
            processData: false,
            success: function () {
                alert("Plugin configuration has been updated. Enabled mappings will sync shortly in the background.");
                startStatusPolling();
            },
            error: function (jqXHR, textStatus, errorThrown) {
                alert(formatAjaxError(jqXHR, textStatus, errorThrown));
            }
        });
    }

    $(document).ready(function () {
        $("#tmsRetryEnabled").on("change", syncRetryFieldsEnabled);
        syncRetryFieldsEnabled();
        $("#testit-conf").submit(function (e) {
            e.preventDefault();
            updateConfig();
        });
        $("#duplication-add").on("click", function () {
            addMappingRow({});
        });

        $.ajax({ url: projectsUrl, dataType: "json" })
            .done(function (projects) {
                jiraProjects = projects || {};
                $.each(jiraProjects, function (key, value) {
                    $("#projects").append(
                        $("<div><input type=\"checkbox\" id=\"" + key
                            + "\" name=\"project\" value=\"" + key
                            + "\"><label for=\"" + key + "\">" + value
                            + " (" + key + ")</label></div>")
                    );
                });
                $.ajax({ url: configUrl, dataType: "json" })
                    .done(function (config) {
                        applyConfig(config || {});
                        loadTmsProjects();
                    })
                    .fail(function (jqXHR, textStatus, errorThrown) {
                        alert(formatAjaxError(jqXHR, textStatus, errorThrown));
                    });
            })
            .fail(function (jqXHR, textStatus, errorThrown) {
                alert(formatAjaxError(jqXHR, textStatus, errorThrown));
            });
    });
})(AJS.$ || jQuery);
