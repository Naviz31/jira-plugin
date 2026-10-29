package testIT.jira.tms;

import java.util.ArrayList;
import java.util.List;

/** A defect or bug-like link found among the test points of a Test IT test plan. */
public class TmsPlanBugDto {
    /** Issue key parsed from the URL (e.g. BUGS-10); null when the URL has no key. */
    public String key;
    /** Title given in Test IT; null for links stored without one. */
    public String title;
    public String url;
    /** True when the link is a "Defect" attached to a test result. */
    public boolean defect;
    /** True when the link is attached to the work item itself (needs a bug-type check on the Jira side). */
    public boolean linked;
    public List<Test> tests = new ArrayList<>();

    /** A test of the plan the bug is attached to. */
    public static class Test {
        public String name;
        public Long globalId;
        public String url;
        public String status;
    }
}
