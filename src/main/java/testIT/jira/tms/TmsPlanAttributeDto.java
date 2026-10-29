package testIT.jira.tms;

import java.util.ArrayList;
import java.util.List;

/** A custom attribute of a Test IT test plan, already resolved to display values. */
public class TmsPlanAttributeDto {
    public String id;
    public String name;
    /** Test IT attribute type: string, options, multipleOptions, checkbox, user, datetime. */
    public String type;
    public boolean required;
    /** Display values (option titles, user names, formatted dates); empty when the attribute is not filled. */
    public List<String> values = new ArrayList<>();
    /** Only for checkbox attributes: the stored value, null when never set. */
    public Boolean checked;
}
