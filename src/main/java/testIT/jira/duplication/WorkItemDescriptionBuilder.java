package testIT.jira.duplication;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import testIT.jira.tms.TmsJson;

/**
 * Builds Jira wiki-markup Description panels from a Test IT work item.
 */
final class WorkItemDescriptionBuilder {
    private static final Pattern MENTION_SPAN = Pattern.compile(
            "(?i)<span[^>]*class=\"[^\"]*mention[^\"]*\"[^>]*>(.*?)</span>");
    private static final Pattern MENTION_DATA_VALUE = Pattern.compile(
            "(?i)data-value=\"([^\"]*)\"");
    private static final Pattern MENTION_DENOTATION = Pattern.compile(
            "(?i)data-denotation-char=\"([^\"]*)\"");
    private static final Pattern BR_OR_BLOCK_END = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private WorkItemDescriptionBuilder() {
    }

    static String buildWiki(JsonObject workItem) {
        StringBuilder sb = new StringBuilder();
        appendInfoPanel(sb, workItem);
        appendStepsPanel(sb, "Preconditions", stepsArray(workItem, "preconditionSteps"));
        appendStepsPanel(sb, "Steps", stepsArray(workItem, "steps"));
        appendStepsPanel(sb, "Postconditions", stepsArray(workItem, "postconditionSteps"));
        return sb.toString().trim();
    }

    private static void appendInfoPanel(StringBuilder sb, JsonObject workItem) {
        sb.append("{panel:title=Info}\n");
        sb.append("||Type||Priority||State||Is automated||\n");
        sb.append('|')
                .append(cell(entityType(workItem)))
                .append('|')
                .append(cell(scalar(workItem, "priority")))
                .append('|')
                .append(cell(scalar(workItem, "state")))
                .append('|')
                .append(isAutomated(workItem) ? "True" : "False")
                .append("|\n");
        sb.append("{panel}\n");
    }

    private static String entityType(JsonObject workItem) {
        String type = scalar(workItem, "entityTypeName");
        if (type == null || type.trim().isEmpty()) {
            type = scalar(workItem, "type");
        }
        return type;
    }

    private static void appendStepsPanel(StringBuilder sb, String title, JsonArray steps) {
        sb.append("{panel:title=").append(title).append("}\n");
        sb.append("||#||Action||Expected||TestData||Comment||StepLink||Last result||\n");
        if (steps != null) {
            int index = 1;
            for (JsonElement element : steps) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject step = element.getAsJsonObject();
                sb.append('|')
                        .append(index++)
                        .append('|')
                        .append(cell(TmsJson.getString(step, "action")))
                        .append('|')
                        .append(cell(TmsJson.getString(step, "expected")))
                        .append('|')
                        .append(cell(TmsJson.getString(step, "testData")))
                        .append('|')
                        .append(cell(TmsJson.getString(step, "comments")))
                        .append('|')
                        .append(cell(stepLink(step)))
                        .append('|')
                        .append(cell(null))
                        .append("|\n");
            }
        }
        sb.append("{panel}\n");
    }

    private static JsonArray stepsArray(JsonObject workItem, String field) {
        if (workItem == null || !workItem.has(field) || !workItem.get(field).isJsonArray()) {
            return new JsonArray();
        }
        return workItem.getAsJsonArray(field);
    }

    private static boolean isAutomated(JsonObject workItem) {
        if (workItem == null || !workItem.has("autoTests") || workItem.get("autoTests").isJsonNull()) {
            return false;
        }
        if (workItem.get("autoTests").isJsonArray()) {
            return workItem.getAsJsonArray("autoTests").size() > 0;
        }
        return false;
    }

    private static String stepLink(JsonObject step) {
        if (step == null) {
            return null;
        }
        if (step.has("workItem") && step.get("workItem").isJsonObject()) {
            String name = TmsJson.getString(step.getAsJsonObject("workItem"), "name");
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return TmsJson.getString(step, "workItemId");
    }

    private static String scalar(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            String name = TmsJson.getString(element.getAsJsonObject(), "name");
            if (name != null) {
                return name;
            }
            String value = TmsJson.getString(element.getAsJsonObject(), "value");
            if (value != null) {
                return value;
            }
        }
        return element.toString();
    }

    /**
     * Wiki table cell: plain text, empty cells keep a space so columns do not collapse.
     */
    static String cell(String value) {
        String plain = toPlainText(value);
        if (plain == null || plain.isEmpty()) {
            return " ";
        }
        String cleaned = plain
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('|', '/')
                .trim()
                .replaceAll(" +", " ");
        return cleaned.isEmpty() ? " " : cleaned;
    }

    static String toPlainText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (text.indexOf('<') >= 0) {
            text = htmlToPlain(text);
        }
        text = decodeBasicEntities(text);
        text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    private static String htmlToPlain(String html) {
        String text = html;
        Matcher mention = MENTION_SPAN.matcher(text);
        StringBuffer withMentions = new StringBuffer();
        while (mention.find()) {
            String full = mention.group(0);
            String inner = mention.group(1) != null ? mention.group(1) : "";
            String innerPlain = ANY_TAG.matcher(inner).replaceAll("").trim();
            String replacement = innerPlain;
            if (replacement.isEmpty()) {
                Matcher denotation = MENTION_DENOTATION.matcher(full);
                Matcher dataValue = MENTION_DATA_VALUE.matcher(full);
                String prefix = denotation.find() ? denotation.group(1) : "";
                String value = dataValue.find() ? dataValue.group(1) : "";
                replacement = (prefix != null ? prefix : "") + (value != null ? value : "");
            }
            mention.appendReplacement(withMentions, Matcher.quoteReplacement(replacement));
        }
        mention.appendTail(withMentions);
        text = withMentions.toString();

        text = BR_OR_BLOCK_END.matcher(text).replaceAll(" ");
        text = ANY_TAG.matcher(text).replaceAll("");
        return text;
    }

    private static String decodeBasicEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        return text
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
