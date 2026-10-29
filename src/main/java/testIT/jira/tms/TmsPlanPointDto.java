package testIT.jira.tms;

import java.util.ArrayList;
import java.util.List;

/** A test of a Test IT test plan (a "test point") with its status and links back to Test IT and Jira. */
public class TmsPlanPointDto {
    public String name;
    public Long globalId;
    /** Link to the test in Test IT. */
    public String url;
    /** Status title as shown in Test IT, e.g. "Провален". */
    public String status;
    /** Test IT status type: Pending, InProgress, Succeeded, Failed, Incomplete. */
    public String statusType;
    public String section;
    public String suite;
    public String configuration;
    public String tester;
    /** Issue links of the test: defects of its last result and links stored on the work item. */
    public List<Link> links = new ArrayList<>();

    public static class Link {
        public String key;
        public String url;
        public boolean defect;
    }
}
